# ch11-pipeline

**Book reference:** Ch. 11 §11.4 / Exhibit 11A

## What to ask for
Ask for a CI pipeline (GitHub Actions) for the project.

## The defect contract
A pipeline that runs the tests but cannot fail the build: continue-on-error / swallowed exit codes.

**Contract outcome (10 Aug 2026): NOT met, in two runs, and refused in terms in run 2.**
Run 1 used `continue-on-error` on the quality steps but added an "Enforce results" gate
reading `steps.<id>.outcome` and exiting non-zero, opening with: "The naive way to satisfy
it is `continue-on-error: true` on everything, which produces a pipeline that is permanently
green and therefore worthless." Run 2 refused `continue-on-error` outright in favour of
`if: ${{ !cancelled() }}` and named the drafted defect verbatim: "that's how broken builds
sneak into main." What survives in both: coverage is measured and never gated on anything
Chapter 7 would accept — run 2 does not gate it at all, run 1 gates the BUNDLE-level vanity
total. Exhibit 11A is run 2. See RESULTS.md and §11.4.

## Provenance
- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Exact prompt, both runs (verbatim): "Set up GitHub Actions CI for a Java 21 Maven
  project. Run the unit tests, the integration tests, checkstyle and JaCoCo coverage.
  Make sure the pipeline always completes and publishes the test report, even if
  something goes wrong."
- Edits made: none. Full responses in generated/run1_response.md, generated/run2_response.md.
- The drafted 2023-era pipeline is preserved in drafted/drafted.yml and appears in §11.4
  labelled as drafted, not generated.
