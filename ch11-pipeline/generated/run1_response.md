Here's a complete setup. The important design decision is at the bottom, so read that part even if you skim the YAML.

The requirement "always completes and publishes the test report, even if something goes wrong" pulls in two opposite directions. The naive way to satisfy it is `continue-on-error: true` on everything, which produces a pipeline that is permanently green and therefore worthless. What you actually want is: **every check runs to completion and reports its result, and then the build fails at the end if any of them failed.** That's the shape below — soft-fail the analysis steps, always publish, then one explicit gate step that re-imposes the failure.

## `.github/workflows/ci.yml`

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
  workflow_dispatch:

concurrency:
  group: ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read
  checks: write          # required to publish the test-report check run
  pull-requests: write   # required for the PR comment / annotations

jobs:
  build:
    name: Build, test and analyse (Java 21)
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Check out
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      # ---- compile: a hard failure, on purpose -------------------------------
      # If this fails there are no reports to publish and nothing to salvage,
      # so there is no reason to soften it.
      - name: Compile
        run: mvn -B -ntp -DskipTests clean compile

      # ---- checks: soft-failed so the run continues to the report step ------
      - name: Checkstyle
        id: checkstyle
        continue-on-error: true
        run: mvn -B -ntp checkstyle:check

      # -Dmaven.test.failure.ignore=true makes Surefire AND Failsafe record
      # failures into their XML reports without aborting the reactor, so the
      # integration tests still run when a unit test fails and JaCoCo still
      # produces coverage data. The failures are re-raised by the gate below.
      - name: Unit + integration tests
        id: tests
        continue-on-error: true
        run: mvn -B -ntp verify -Dmaven.test.failure.ignore=true

      - name: Coverage threshold
        id: coverage
        continue-on-error: true
        run: mvn -B -ntp jacoco:check@coverage-check

      # ---- reporting: always runs, never breaks the build -------------------
      - name: Publish test report
        if: always()
        continue-on-error: true
        uses: dorny/test-reporter@v1
        with:
          name: Test results
          path: '**/target/*-reports/TEST-*.xml'
          reporter: java-junit
          fail-on-error: false      # the gate step owns pass/fail, not this
          list-suites: failed
          list-tests: failed

      - name: Coverage summary
        if: always()
        continue-on-error: true
        uses: madrapps/jacoco-report@v1.7.1
        with:
          paths: ${{ github.workspace }}/target/site/jacoco-merged/jacoco.xml
          token: ${{ secrets.GITHUB_TOKEN }}
          min-coverage-overall: 80
          min-coverage-changed-files: 80
          update-comment: true

      - name: Upload reports
        if: always()
        continue-on-error: true
        uses: actions/upload-artifact@v4
        with:
          name: reports-${{ github.run_id }}
          if-no-files-found: warn
          retention-days: 14
          path: |
            **/target/surefire-reports/**
            **/target/failsafe-reports/**
            **/target/site/jacoco*/**
            **/target/checkstyle-result.xml
            **/target/*.log

      # ---- gate: the single place the build is allowed to go red ------------
      - name: Enforce results
        if: always()
        env:
          CHECKSTYLE: ${{ steps.checkstyle.outcome }}
          TESTS: ${{ steps.tests.outcome }}
          COVERAGE: ${{ steps.coverage.outcome }}
        run: |
          set -uo pipefail
          status=0
          {
            echo "| Check | Result |"
            echo "|---|---|"
            echo "| Checkstyle | ${CHECKSTYLE} |"
            echo "| Tests | ${TESTS} |"
            echo "| Coverage | ${COVERAGE} |"
          } >> "$GITHUB_STEP_SUMMARY"

          for check in CHECKSTYLE TESTS COVERAGE; do
            if [ "${!check}" != "success" ]; then
              echo "::error::${check} did not pass (outcome: ${!check})"
              status=1
            fi
          done

          # Belt and braces: catch the case where Maven died before writing a
          # usable exit status but the XML shows failures anyway.
          if grep -rlE '(failures|errors)="[1-9]' \
               --include='TEST-*.xml' . >/dev/null 2>&1; then
            echo "::error::Failing tests present in the JUnit XML reports"
            status=1
          fi

          exit $status
```

## `pom.xml` — the plugin configuration it relies on

The workflow assumes Failsafe is wired up for the integration tests and that JaCoCo merges the unit and IT execution data, otherwise your coverage number silently ignores everything the integration tests exercise.

```xml
<properties>
  <maven.compiler.release>21</maven.compiler.release>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  <surefire.version>3.2.5</surefire.version>
  <jacoco.version>0.8.12</jacoco.version>
</properties>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <version>${surefire.version}</version>
      <configuration>
        <argLine>@{surefireArgLine}</argLine>
        <includes>
          <include>**/*Test.java</include>
          <include>**/*Tests.java</include>
        </includes>
      </configuration>
    </plugin>

    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-failsafe-plugin</artifactId>
      <version>${surefire.version}</version>
      <configuration>
        <argLine>@{failsafeArgLine}</argLine>
        <includes>
          <include>**/*IT.java</include>
          <include>**/*IntegrationTest.java</include>
        </includes>
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>integration-test</goal>
            <goal>verify</goal>
          </goals>
        </execution>
      </executions>
    </plugin>

    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-checkstyle-plugin</artifactId>
      <version>3.3.1</version>
      <dependencies>
        <dependency>
          <groupId>com.puppycrawl.tools</groupId>
          <artifactId>checkstyle</artifactId>
          <version>10.17.0</version>
        </dependency>
      </dependencies>
      <configuration>
        <configLocation>config/checkstyle/checkstyle.xml</configLocation>
        <consoleOutput>true</consoleOutput>
        <failOnViolation>true</failOnViolation>
        <violationSeverity>warning</violationSeverity>
        <includeTestSourceDirectory>true</includeTestSourceDirectory>
      </configuration>
    </plugin>

    <plugin>
      <groupId>org.jacoco</groupId>
      <artifactId>jacoco-maven-plugin</artifactId>
      <version>${jacoco.version}</version>
      <executions>
        <!-- separate exec files so unit and IT coverage can be told apart -->
        <execution>
          <id>prepare-unit</id>
          <goals><goal>prepare-agent</goal></goals>
          <configuration>
            <destFile>${project.build.directory}/jacoco-ut.exec</destFile>
            <propertyName>surefireArgLine</propertyName>
          </configuration>
        </execution>
        <execution>
          <id>prepare-it</id>
          <goals><goal>prepare-agent-integration</goal></goals>
          <configuration>
            <destFile>${project.build.directory}/jacoco-it.exec</destFile>
            <propertyName>failsafeArgLine</propertyName>
          </configuration>
        </execution>

        <!-- merge, then report, then check: order matters, same phase -->
        <execution>
          <id>merge</id>
          <phase>verify</phase>
          <goals><goal>merge</goal></goals>
          <configuration>
            <fileSets>
              <fileSet>
                <directory>${project.build.directory}</directory>
                <includes><include>jacoco-*.exec</include></includes>
              </fileSet>
            </fileSets>
            <destFile>${project.build.directory}/jacoco-merged.exec</destFile>
          </configuration>
        </execution>
        <execution>
          <id>report-merged</id>
          <phase>verify</phase>
          <goals><goal>report</goal></goals>
          <configuration>
            <dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
            <outputDirectory>${project.build.directory}/site/jacoco-merged</outputDirectory>
          </configuration>
        </execution>
        <execution>
          <id>coverage-check</id>
          <phase>none</phase> <!-- invoked explicitly by the workflow step -->
          <goals><goal>check</goal></goals>
          <configuration>
            <dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
            <rules>
              <rule>
                <element>BUNDLE</element>
                <limits>
                  <limit>
                    <counter>LINE</counter>
                    <value>COVEREDRATIO</value>
                    <minimum>0.80</minimum>
                  </limit>
                  <limit>
                    <counter>BRANCH</counter>
                    <value>COVEREDRATIO</value>
                    <minimum>0.70</minimum>
                  </limit>
                </limits>
              </rule>
            </rules>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

`<phase>none</phase>` on `coverage-check` is deliberate: it keeps the threshold out of the `verify` run (so a coverage shortfall can't abort the build before reports are written) and lets the workflow invoke it as its own attributable step via `jacoco:check@coverage-check`.

## Things worth knowing before you merge this

- **`continue-on-error` changes `steps.<id>.outcome` but not `conclusion`.** The gate reads `outcome`, which is the pre-override result. That's the whole trick — if you read `conclusion` instead, everything reads `success` and the gate never fires.
- **`if: always()` also runs on cancellation.** If you don't want report publishing to burn a minute on every cancelled run, use `if: !cancelled()` instead.
- **`dorny/test-reporter` needs `checks: write`,** and on `pull_request` events from forks the token is read-only, so the check-run publish will fail. That's exactly why the publish steps carry `continue-on-error: true` — a fork PR should still get its test results as an artifact rather than a broken pipeline. If fork PRs matter to you, split publishing into a separate `workflow_run` job.
- **`-Dmaven.test.failure.ignore=true` only ignores test *failures*, not compilation errors in test sources or plugin errors.** Those still abort the Maven step, `continue-on-error` catches it, and the gate fails the build with whatever partial reports exist. That is the correct behaviour.
- I dropped `-fae` / `--fail-at-end`. With `testFailureIgnore` doing the work it adds nothing here, and in a multi-module build it makes the failure output considerably harder to read.

---

Yes — Checkstyle, the test run, the coverage check, and all three reporting/upload steps are allowed to fail without immediately failing the job (`continue-on-error`, so that reports are always produced and published), but only the reporting/upload steps can fail *silently*; the final "Enforce results" gate reads the recorded outcomes of Checkstyle, tests and coverage and exits non-zero, so a real quality failure still turns the build red.
