# Mutation experiment — REAL RESULTS (9 Aug 2026)

Suite as generated: 34 tests in 4 classes (CirculationServiceTest 14, HoldQueueServiceTest 5,
FineAccrualServiceTest 10, FineCalculatorTest 5). Baseline: all 34 pass.

| Mutation | What was deleted | Caught by |
|---|---|---|
| A (INV-1) | availability check in checkout | 1 of 34 (checkout_copyNotAvailable_throwsForEveryNonAvailableStatus) |
| B (INV-5 closure) | branch-calendar guard in accrual | 1 of 34 behaviourally (skipsClosedDaysWithoutCharging) + 6 Mockito strict-stub errors as side artifacts |
| C (INV-5 cap) | replacement-cost clamp | 6 of 34 |

26 of 34 tests never failed against any mutant.

## How this differs from the drafted §7.2, and why it matters
1. Drafted numbers (41 tests, 87% coverage, 38/41 survive, Defect B caught by nothing)
   are superseded. In particular the draft's central dramatic claim — the closure defect
   caught by ZERO tests — is falsified as run: this suite tested the closure rule.
2. Methodological caveat, disclose in the chapter: the core under test carries Javadoc
   naming the invariants (including "INV-5 closure clause" comments). The test generator
   read those comments and wrote tests for them. A code-first scenario in which the
   implementation and tests are generated together from a vague prompt, on undocumented
   code, is closer to §7.2's story and would likely produce weaker suites. Re-run under
   that condition before finalising the chapter, or rewrite §7.2 to the honest, more
   interesting finding: documentation quality is what the suite quality tracked.
3. The Mockito strict-stub artifacts under Mutation B are worth a sidebar: strictness
   flagged the behaviour change incidentally, which is not the same as testing it.
