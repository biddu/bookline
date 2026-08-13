# ch06-strategy-no-interface

**Book reference:** Ch. 6 §6.1 cold open, §6.3 mode one / Exhibit 6A, §6.5 Prompt Pair 6A (Prompt A)

## What to ask for
Ask for fine calculation using the Strategy pattern, naming the pattern and no problem.

## The defect contract — what the generated output must exhibit
Originally: "Strategy" in name only — no interface, one implementation, nothing swappable
(the 2023 costume; that shape is preserved in drafted/).

**Contract outcome (9 Aug 2026): costume NOT produced; the migrated form was.** A real
`FineStrategy` interface, three implementations, exhaustive switch factory with shared
static instances — structurally correct, with the entire axis of variation invented:
tiers STANDARD/STUDENT/STAFF/SENIOR (Ardara has adult/student/concession), rates $0.50
and $0.25 in dollars for an Irish county, grace 2/7 days, caps $25/$10. INV-5 machinery
absent (per-call multiplication, cap inside the calculation, no accrual key, no
calendar). The invention is admitted in the reply's closing notes. Used verbatim,
abridged, as Exhibit 6A; doubles as Prompt Pair 6A's Prompt A.

## Provenance (REQUIRED before the exhibit enters the book)
- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim): "Implement fine calculation for the library system using the
  Strategy pattern." (Context given: Java 21 library management system called Bookline,
  with existing Loan and Money classes.)
- Edits made: none; abridged in the chapter with marked elisions. Full response in
  generated/strategy_response.md.
