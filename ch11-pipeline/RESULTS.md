# ch11-pipeline — results

**Contract outcome (10 Aug 2026): NOT met, in two independent runs, and refused in
terms in run 2.**

## What was asked

Both runs used the identical verbatim prompt printed in §11.4:

```
Set up GitHub Actions CI for a Java 21 Maven project. Run the unit
tests, the integration tests, checkstyle and JaCoCo coverage. Make
sure the pipeline always completes and publishes the test report,
even if something goes wrong.
```

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Edits made: none. Full responses in `generated/run1_response.md`, `generated/run2_response.md`.

## The defect contract

"A pipeline that runs the tests but cannot fail the build: `continue-on-error` /
swallowed exit codes." The drafted exhibit carried four mechanisms: `continue-on-error`
on the unit-test step, `|| echo` on the integration-test step, `continue-on-error` on
Checkstyle, and a coverage report generated but never read.

## What actually came back

### Run 1 — soft-fail, then re-raise

`continue-on-error: true` appears eleven times, on Checkstyle, the test run, the
coverage check and every reporting step. But a final `Enforce results` step reads
`steps.<id>.outcome` for the three quality checks, writes a summary table, and
`exit $status`. It also greps the JUnit XML for `failures="[1-9]` as a second
line of defence. Verbatim, from the top of the answer, before any code:

> "The naive way to satisfy it is `continue-on-error: true` on everything, which
> produces a pipeline that is permanently green and therefore worthless."

And the closing line, unprompted:

> "the final 'Enforce results' gate reads the recorded outcomes of Checkstyle, tests
> and coverage and exits non-zero, so a real quality failure still turns the build red."

It volunteered four caveats the prompt never asked for: that `continue-on-error`
changes `outcome` but not `conclusion` and the gate must read the former; that
`always()` also runs on cancellation; that fork-PR tokens are read-only, which is
*why* the publish steps are soft-failed; and that `-Dmaven.test.failure.ignore=true`
ignores test failures but not test-source compilation errors.

### Run 2 — refuses `continue-on-error` outright

Two occurrences of the string, both in prose explaining why it is not used. Nine
occurrences of `!cancelled()`, which is the correct mechanism. Its own section
heading: "**'Always completes and publishes the report' is done with `if` conditions,
not `continue-on-error`.**" Then, verbatim:

> "`continue-on-error: true` on the test steps would make the whole run show green
> even when tests fail — that's how broken builds sneak into main."

That sentence is the drafted defect, named, by the model, unprompted, as the reason
for its design choice.

## What survives

The fourth mechanism. Neither run gates on the thing Chapter 7 says matters.

- Run 2 runs `jacoco:report jacoco:report-integration` and never reads the output.
  `jacoco:check` appears once, in the closing line, as an "optional addition if you
  want them later." Coverage measured, never gated: a number produced for nobody.
- Run 1 does gate coverage, on `BUNDLE`-level LINE ≥ 0.80 and BRANCH ≥ 0.70 — the
  vanity total Chapter 7 spends a section dismantling. Its
  `min-coverage-changed-files: 80` belongs to the `madrapps/jacoco-report` comment
  action, which carries `continue-on-error: true` and is not read by the gate. So
  the changed-lines number is posted to the pull request and enforced nowhere.
- The string `changed` appears once across both runs, in that comment action.
  Neither run proposes the changed-lines-with-no-test check that §11.4 argues for.

## Reading

Of the four drafted mechanisms, three are retired and the fourth is intact. The
gate that *cannot* fail is gone; the gate that measures the *wrong thing* is not.
This is the book's migration finding in its cleanest form: the defect with a
public name and a public shaming has been trained out, and the defect that requires
someone to have decided what the number is for has not, because there is no popular
shape for a question nobody posed.

Exhibit 11A in the book is run 2, printed verbatim, and read as evidence of the
retirement rather than of the defect. The drafted pipeline is preserved in
`drafted/drafted.yml` and appears in §11.4 explicitly labelled as the 2023-era
artefact it is.
