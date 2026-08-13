# Mutation experiment, condition 2 — undocumented core (REAL RESULTS, 9 Aug 2026)

This is the re-run that RESULTS.md called for. Condition 1 (documented core) is in
RESULTS.md; its caveat was that `Core.java` and `Billing.java` carried Javadoc naming
the invariants ("INV-5 closure clause"), so the generator may have been writing tests
for the comments rather than reasoning about the code.

**Condition 2 removes that confound.** Same two source files, same generator, same
verbatim prompt. Every comment stripped — Javadoc, block and line comments, including
every INV-n mention. Logic byte-identical; only comments removed (Core.java 227→208
lines, Billing.java 97→83 lines; `grep -c "//\|/\*" == 0`).

Prompt (verbatim, identical to condition 1):

```
Write JUnit 5 unit tests for the circulation and billing services.
Use Mockito for the dependencies. Aim for high coverage.
```

## The suite as generated

| | Condition 1 (documented) | Condition 2 (undocumented) |
|---|---|---|
| Test classes | 4 | 5 |
| Test methods | 34 | 65 |
| Executions (parameterised rows expanded) | 34 | 72 |
| Baseline | all pass | all pass |
| Line coverage | not measured | **126/130 = 96.9%** |
| Branch coverage | not measured | **22/22 = 100%** |

Stripping the documentation did **not** degrade the suite. It produced nearly twice as
many tests. The RESULTS.md hypothesis ("documentation quality is what the suite quality
tracked") is **not supported**. What the suite quality tracks is the *shape of the code*
— see the mutation results.

## Mutation results, condition 2

Tooling: JDK 21, JUnit 5.10.2, Mockito 5.11.0, maven-surefire 3.2.5. Each mutation is a
deletion of correct code, applied alone, reverted between runs (`mutate.py`).

| Mutation | Deleted | Executions red | Survived | Genuine behavioural kills |
|---|---|---|---|---|
| A (INV-1) | availability check in `checkout` | 3 / 72 | 69 | **1 test method** (`throwsWhenCopyNotAvailable`, 3 parameterised rows) |
| B (INV-5 closure) | branch-calendar guard in accrual loop | 14 / 72 | 58 | **1** (`skipsClosedDays`) |
| C (INV-5 cap) | replacement-cost clamp | 11 / 72 | 61 | **11**, all genuine assertion failures |

### Mutation B, classified precisely — this is the finding

Of the 14 red executions:

| Kind | Count | What it is |
|---|---|---|
| `UnnecessaryStubbingException` | **11** | Mockito strict stubbing noticing the `calendar.isOpenOn` stub went unused. **Not verification.** Under lenient stubs, or a real calendar, these 11 are silent. |
| `NullPointerException` | 1 | `writesNothingWhenLibraryClosedThroughout` crashes rather than asserts |
| `AssertionFailedError` | 1 | `skipsClosedDays` — the one clean behavioural claim about the closure rule |
| `Wanted but not invoked` | 1 | `stopsAtTheReplacementCostCap` — collateral: extra days shift cap behaviour |

So a reviewer watching 14 red bars would be watching 11 framework complaints, one crash,
one piece of collateral damage, and **one actual test of the branch-closure rule**.
Detection by framework accident is not detection.

### Why C did so much better than A and B

`FineCalculator.dailyIncrement` is a **pure function** with a small input space, and the
generator table-drove it (`dailyIncrementTable`, parameterised, 6 rows) plus boundary
cases at, above and below the cap. `checkout` and `accrueUpTo` are **services with
mocked collaborators**, and there the generator asserted mostly on interactions and
pass-through values.

**The rule the experiment actually supports:** generated suites test pure functions
hard and service-level guards barely. Not "documentation drives quality" — code shape
drives quality.

## The coverage experiment (§7.7), actually run

All verification removed from the suite while preserving every Arrange and Act step
(`strip_assertions.py`): 83 assertions replaced with a `sink(...)` that still evaluates
every argument expression, 37 `verify(...)` statements deleted, 10 `assertThrows(T, () ->
BODY)` rewritten to `try { BODY; } catch (Throwable ignored) {}` so the act still runs.
**130 verification statements removed in total.**

| | Line coverage | Branch coverage | Tests "passing" |
|---|---|---|---|
| Suite as generated | 126/130 = **96.9%** | 22/22 = **100%** | 72 / 72 |
| Every assertion removed | 126/130 = **96.9%** | 22/22 = **100%** | 68 / 72 |

Coverage is **identical to the decimal** with every claim in the suite deleted. The 4
non-passing are `ArgumentCaptor` plumbing that depended on the deleted `verify` call, an
artifact of the stripping, not a behavioural failure.

A metric that does not move when all verification is deleted is not a metric of
verification.

## The model's own disclosure (condition 2)

Unprompted, the generator opened its reply with a warning that is this chapter's thesis
in its own words:

> "there are several places where the code makes a decision and I cannot tell whether
> the decision is the intended rule or an accident. Where that happened I wrote the test
> to pin the **current** behaviour and named the test so the assumption is visible. If
> any of these are wrong, the test is wrong too, and it will now actively defend the bug"

It then listed five, including one it called out as probably a genuine defect:

> "**`renew` never moves `dueAt`.** It increments `renewalCount` and saves. Unless
> something downstream recomputes the due date, a renewal buys the borrower nothing. I
> have asserted the current behaviour in `doesNotChangeDueDate` — this is the one I
> would most expect to be a real bug."

It also flagged, unasked, that `existsForLoanAndDate` is check-then-act and "needs a
unique constraint on `(loan_id, accrual_date)`" — the exact repair §7.6 builds.

All of this is in the prose preamble. None of it is in the diff.

## Files

- `core_stripped/` — the undocumented sources
- `generated_condition2/` — the 5 generated test classes, verbatim
- `generated_response_condition2.md` — the model's full reply, verbatim
- `mutate.py`, `mutation_results_condition2.txt` — the mutation harness and raw output
- `strip_assertions.py` — the coverage experiment transformer
- `coverage_condition2.txt` — both coverage measurements
