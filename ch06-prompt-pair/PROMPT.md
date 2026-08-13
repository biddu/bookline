# ch06-prompt-pair

**Book reference:** Ch. 6 §6.5 / Prompt Pair 6A

## What to ask for
Pattern-requested vs problem-described prompts for fine calculation, both printed in
the chapter.

## The defect contract — what the generated output must exhibit
Prompt A: pattern ceremony without the problem. Prompt B: the §6.2 structure.

**Contract outcome (9 Aug 2026):** Prompt A = the ch06-strategy-no-interface run
(structurally correct Strategy with a fabricated axis; see that exhibit). Prompt B
(committed here): 228 lines — DailyFineRate interface, three tier implementations,
rate resolved through the loan's LoanPolicy, NightlyFineAssessor keying accruals on
(loan_id, accrual_date) via insertIfAbsent, skipping closure days, clamping the day's
amount to remaining headroom at the write so the total lands exactly on the cap; plus
two flagged interpretation notes (zero-amount days write no row; grace counts calendar
days, not open days). Structure and stated invariants correct on the first attempt;
the flags are unanswered policy questions the reviewer must decide.

## Provenance — Prompt B
- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim): the specified prompt printed in §6.5, beginning "Bookline
  assesses overdue fines nightly." and ending "No singletons, no factories, no
  configuration options I have not asked for."
- Edits made: none. Full response in generated/specified_response.md.
