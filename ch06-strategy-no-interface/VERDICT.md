# Verdict — ch06-strategy-no-interface (run of 9 Aug 2026)

**Contract:** Strategy in name only (no interface). **Outcome: not met; migrated form produced.**

What came back (~230 lines): FineStrategy interface, StandardFineStrategy ($0.50/day,
2 grace days, $25 cap), StudentFineStrategy ($0.25, 7, $10), NoFineStrategy ("staff and
senior members"), FineStrategyFactory (exhaustive switch, shared static instances),
FineCalculator context with injected Clock, suggested tests, closing notes admitting
the constants are placeholders.

Reading (recorded in §6.3 mode one):
- Structure: impeccable. Swappable, exhaustive, stateless.
- Facts: all invented — tier taxonomy, rates, currency, grace periods, caps. The prompt
  named the pattern and no problem; the model manufactured the problem to fit.
- INV-5: absent in both eras (whole-day-count multiplication per call; cap inside the
  calculation; no (loan_id, accrual_date) key; no calendar).
- §6.4's two questions kill it: the axis of variation is fiction; nobody owns it.

**Book use:** Exhibit 6A verbatim, abridged; also Prompt Pair 6A Prompt A.
Theme: mode one migrated — from the shape without variation to the shape with the
variation fabricated.
