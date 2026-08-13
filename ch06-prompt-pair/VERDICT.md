# Verdict — ch06-prompt-pair (runs of 9 Aug 2026)

**Prompt A** (pattern requested): see ch06-strategy-no-interface — invented tiers,
rates, currency, caps; INV-5 machinery absent. ~230 lines.

**Prompt B** (problem described): 228 lines. Everything asked for, where asked:
- Only the per-day amount behind an interface (DailyFineRate; 30c adult, 15c student
  after 2 grace days, 0 concession — exactly as specified).
- Tier resolved via loan.policy().dailyFineRate(); tier names never appear in shared
  code.
- Accrual keyed (loan_id, accrual_date), insertIfAbsent; closure days skipped;
  cap clamped to headroom at the write ("the final accrual may be partial and the
  running total lands exactly on the cap").
- No singletons, no factories, nothing extra beyond a UNIQUE-constraint note.

The drafted expectation (cap in a getter, surviving early review) did NOT occur.
What occurred instead: two flagged interpretation decisions —
1. zero-amount days write no accrual row;
2. student grace counts calendar days, not open days ("I kept the simpler reading").
Both inside the stated rules; both policy questions the prompt never answered.

**Book use:** §6.5's honest accounting rewritten around these facts. Theme: a good
prompt buys structure and stated invariants; completeness of the statement is the
reviewer's, and the model now surfaces the gaps as questions.
