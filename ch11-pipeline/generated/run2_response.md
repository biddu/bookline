# ch11-pipeline — run 2, verbatim

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim, identical to run 1): "Set up GitHub Actions CI for a Java 21 Maven project. Run the unit tests, the integration tests, checkstyle and JaCoCo coverage. Make sure the pipeline always completes and publishes the test report, even if something goes wrong."
- Edits made: none.

---

Here's a setup I'd use. It assumes the standard convention: unit tests named `*Test` run by Surefire, integration tests named `*IT` run by Failsafe, and Checkstyle + JaCoCo configured in the POM (snippet for that below in case you don't have it yet).

## `.github/workflows/ci.yml`

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

# checks: write is needed by the JUnit report action to attach
# the test results as a check run on the PR/commit.
permissions:
  contents: read
  checks: write

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven          # caches ~/.m2/repository keyed on pom.xml hashes

      - name: Checkstyle
        run: mvn -B -ntp checkstyle:check

      - name: Unit tests
        # Run even if Checkstyle failed — style violations shouldn't hide test results
        if: ${{ !cancelled() }}
        run: mvn -B -ntp test

      - name: Integration tests
        # Run even if unit tests failed, so you see ALL failures in one run.
        # Invokes the Failsafe goals directly against the already-compiled classes,
        # so the unit tests are not executed a second time.
        if: ${{ !cancelled() }}
        run: mvn -B -ntp failsafe:integration-test failsafe:verify

      - name: JaCoCo coverage report
        if: ${{ !cancelled() }}
        run: mvn -B -ntp jacoco:report jacoco:report-integration

      - name: Publish test report
        uses: mikepenz/action-junit-report@v5
        if: ${{ !cancelled() }}
        with:
          check_name: Test results
          report_paths: |
            **/target/surefire-reports/TEST-*.xml
            **/target/failsafe-reports/TEST-*.xml
          require_tests: false     # don't error if a step failed before producing XML

      - name: Upload test and coverage artifacts
        uses: actions/upload-artifact@v4
        if: ${{ !cancelled() }}
        with:
          name: test-and-coverage-reports
          if-no-files-found: ignore
          path: |
            **/target/surefire-reports/
            **/target/failsafe-reports/
            **/target/site/jacoco/
            **/target/site/jacoco-it/
```

## POM prerequisites

If the plugins aren't wired up yet, this is the minimum in `<build><plugins>`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <version>3.5.2</version>
</plugin>

<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-failsafe-plugin</artifactId>
  <version>3.5.2</version>
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
  <version>3.6.0</version>
  <configuration>
    <configLocation>checkstyle.xml</configLocation>
  </configuration>
</plugin>

<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.12</version>
  <executions>
    <execution>
      <goals><goal>prepare-agent</goal></goals>
    </execution>
    <execution>
      <id>prepare-agent-it</id>
      <goals><goal>prepare-agent-integration</goal></goals>
    </execution>
  </executions>
</plugin>
```

(JaCoCo 0.8.11+ is required for Java 21 bytecode; 0.8.12 is safe.)

## Why it's built this way

**"Always completes and publishes the report" is done with `if` conditions, not `continue-on-error`.** This distinction matters. `continue-on-error: true` on the test steps would make the whole run show green even when tests fail — that's how broken builds sneak into main. Instead, failing steps stay failing (the run goes red), but every subsequent step carries `if: ${{ !cancelled() }}`, which overrides the default "only run if everything before me succeeded" behavior. So a Checkstyle violation doesn't hide your test results, a unit test failure doesn't hide integration test failures, and the report gets published either way.

**`!cancelled()` vs `always()`:** they're nearly the same, but `always()` runs steps even when someone cancels the workflow, which just burns minutes grinding through Maven on a run nobody cares about. `!cancelled()` runs on success *and* failure but respects cancellation. Prefer it.

**Integration tests via direct goal invocation** (`failsafe:integration-test failsafe:verify`) rather than `mvn verify` — because `mvn verify` would rerun the whole lifecycle including the unit tests you just ran (and if they failed, it would stop before ever reaching the ITs). The direct goals reuse the compiled test classes from the previous step, so ITs run even when unit tests failed. One caveat: if *compilation* fails, the IT step will also fail with a compile error — that's noisy but harmless. If your project already defines a `skipUTs`-style property, `mvn verify -DskipUTs` is a fine alternative.

**Separate steps instead of one `mvn verify`:** you could collapse everything into a single `mvn -B verify` (Surefire, Failsafe, Checkstyle, and JaCoCo all fire if bound to the lifecycle), and it's marginally faster. But separate steps mean the Actions UI tells you at a glance *which* stage broke without reading the Maven log, and the `!cancelled()` chaining gives you complete results per run instead of fail-fast. Worth the few extra seconds.

**`require_tests: false` on the report action** — if the build blows up before any test runs (e.g., compile error), there are no XML files, and without that flag the report step itself would error out.

**Other small things:** `-ntp` suppresses Maven's dependency-download progress spam in logs; `cache: maven` on setup-java handles the `.m2` cache so you don't need a separate `actions/cache` step; the `concurrency` block cancels superseded runs on the same branch so force-pushes to a PR don't queue up.

Optional additions if you want them later: `jacoco:check` with a minimum coverage threshold to fail the build on low coverage, and a matrix (`java-version: ['21', '25']`) if you want to test the next LTS early.
