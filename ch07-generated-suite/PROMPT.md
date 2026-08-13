# ch07-generated-suite

**Book reference:** Ch. 7 §7.2 / Exhibit 7A — THE MUTATION EXPERIMENT; also §7.3, §7.5, §7.7

## What to ask for
Ask for a full test suite for the circulation core.

## The defect contract — what the generated output must exhibit
A suite that passes with high coverage, then three seeded defects and the count of tests
that still pass. THE NUMBERS IN §7.2 MUST COME FROM ACTUALLY RUNNING THIS. They do; the
chapter reports condition 2 throughout.

## Provenance — CONDITION 1, documented core (9 August 2026)
- Tool: Claude (Cowork; clean context, fresh agent given only the request and the two source files)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim):

```
Write JUnit 5 unit tests for the circulation and billing services.
Use Mockito for the dependencies. Aim for high coverage.
```

- Edits made: none. Result: 34 tests. Numbers in RESULTS.md.
- CAVEAT that forced condition 2: the sources carried Javadoc naming the invariants
  ("INV-5 closure clause"), so the generator may have been writing tests for comments.

## Provenance — CONDITION 2, undocumented core (9 August 2026) — THE ONE THE CHAPTER USES
- Tool: Claude (Cowork; clean context, fresh agent, allowed to read only core_stripped/)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim): identical to condition 1, above.
- Source under test: core_stripped/ — same logic, every comment removed
  (Core.java 227->208 lines, Billing.java 97->83; zero comment characters remain).
- Edits made: none. Result: 65 test methods / 72 executions, all green,
  96.9% line coverage, 100% branch coverage.
- Full numbers, the mutation classification, and the coverage experiment: RESULTS2.md.

## Provenance — the specification-first prompt (§7.5, Prompt 7A-2) (9 August 2026)
- Tool: Claude (Cowork; clean context, allowed to read only core_stripped/Billing.java)
- Model: claude-fable-5
- Exact prompt (verbatim): the four numbered rules printed in §7.5, ending "I expect some
  of these tests to fail against the current code; do not fix them or the code."
- Result: 4 tests, one per rule. Rule 1 FAILS against the real implementation:
  expected [11,12,13,14,15 March], got [11,12,13,14]. Rules 2, 3, 4 pass.
  The failing rule is the same behaviour the code-first suite pinned as correct in
  `doesNotChargeForToday`. Committed in specfirst/.
