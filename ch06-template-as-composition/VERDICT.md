# Verdict — ch06-template-as-composition (run of 9 Aug 2026)

**Contract:** steps-as-lambdas ceremony. **Outcome: not met — both shapes refused.**

The run: domain records + Loan; FinePolicy (@FunctionalInterface); sealed Disposition
(Reshelved, InTransit); CheckInResult carrying closedLoan, fineAssessed, disposition,
holdToNotify; ports (LoanRepository, CopyRepository, HoldQueue); ReturnService.checkIn
as one readable five-step method; wiring example with a lambda fine policy; four
deliberate-choice notes, including the route-vs-holds ordering caveat "worth confirming
with the domain folks."

Reading (recorded in §6.3 mode four):
- The model ran §6.4's two questions on its own output and refused speculative hooks.
- Open item it correctly left to humans: does the hold queue claim before routing
  (INV-4's business)?
- Invented number: 0.25/day, 10.00 cap in the wiring lambda, currency unstated.

**Book use:** Exhibit 6D stays the authored 2023 diagnostic (disclosed); the run is the
mode's honest counter-reading. Theme: review promoted, not retired.
