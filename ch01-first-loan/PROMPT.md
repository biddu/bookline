# ch01-first-loan

**Book reference:** Ch. 1 §1.2 / Exhibit 1A, and Ch. 1 §1.4 / Prompt Pair 1A

## What to ask for
A one-line prompt asking for a Loan class for a library system.

## The defect contract
Presented as evidence of volume and confidence, not dissected. Should show: mutable
setters incl. setDueDate() (INV-2 seed), no validation, plausible naming.

**Contract outcome (10 Aug 2026): refused in all five runs.** Every run used private
fields with no setters, constructor validation, and guarded state transitions; three
explained that overdue status is derived rather than stored. The chapter's four claims
survive on better evidence: 13 words of prompt produced 120–184 lines of Java plus
329–436 words of commentary each time; the loan period was invented at **14 days in four
runs and 21 days in one**; four of five modelled the loan against an ISBN rather than a
physical copy, and the fifth said in terms why that is wrong; two runs put
`LocalDate.now()` back inside the entity in the same response whose notes claim derived
state cannot go stale. Exhibit 1A is run 1. See RESULTS.md, §1.2 and §1.4.

## Provenance
- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Exact prompt, all five runs (verbatim): "Write a Java class to represent a book loan in
  a library system."
- Prompt Pair 1A, chat arm (verbatim): "Our library system should allow a short grace
  period after a loan's due date before the loan counts as overdue. Can you add support
  for this to our Loan class?" plus the current `Loan.java`.
- Prompt Pair 1A, completion arm: the same `Loan.java` truncated at
  `public boolean isOverdueAfterGracePeriod`, with the model instructed to emit only the
  raw continuation. **This is an approximation of an editor plugin and is disclosed as
  such** in `generated/pair1A_completion_runs.md` and in §1.4.
- Edits made: none. Full responses in generated/run1..run5_response.md,
  generated/pair1A_completion_runs.md, generated/pair1A_chat_run.md.
