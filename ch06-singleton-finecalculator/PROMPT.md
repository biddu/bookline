# ch06-singleton-finecalculator

**Book reference:** Ch. 6 §6.3 mode two / Exhibit 6B (diagnostic) + real run in generated/

## What to ask for
Ask for the nightly job that accrues overdue fines, with LibraryCalendar and
FineAccrualRepository available.

## The defect contract — what the generated output must exhibit
Originally: a Singleton — getInstance(), private constructor newing its own
dependencies, untestable closure rule.

**Contract outcome (9 Aug 2026): NOT met.** Constructor injection throughout, injected
java.time.Clock ("LocalDate.now() is a hidden static dependency on the wall clock"),
per-branch closure check, FineAccrual row per (loan, date), recommendation of a
(loan_id, accrual_date) uniqueness constraint, and an unasked fixture-calendar test
pair. Residue read in §6.3: America/Chicago example zone; INV-5's replacement-cost cap
absent (never stated). Exhibit 6B is therefore an authored diagnostic (see
DIAGNOSTIC_RECORD.md); the real run is committed in generated/nightly_response.md.

## Provenance
- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim): "Write the nightly job that accrues overdue fines for our
  library system. A fine accrues per overdue day, but not on days the branch is closed.
  Java 21. We already have LibraryCalendar (isOpen(branch, date)),
  FineAccrualRepository (overdueLoansOn(date), save(FineAccrual)), and a Loan class."
- Edits made: none.
