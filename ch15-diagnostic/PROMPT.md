# ch15-diagnostic

**Book reference:** Ch. 15 §15.5 / Exhibit 15A — THE SECOND DIAGNOSTIC

## What to ask for
Ask for the routine that assigns a returned copy to the hold queue.

## The defect contract — what the generated output must exhibit
Harder than Chapter 2: queries holds by title (passes a reader who memorised Ch. 2), sorts by placedAt alone dropping the priority class (INV-4), non-atomic read-then-write so two same-minute returns double-satisfy one hold, plus a module-dependency violation for Q3. KEEP UNPUBLISHED until launch; it is the assessment instrument.

**Contract outcome (11 Aug 2026): the central defect MET in both runs, supporting defects
split.** Both runs sort the hold queue by `placedAt` alone and drop INV-4's priority-class
qualifier, and both announce the single-`ORDER BY` as a virtue; run 2 even names priority
tiers as a future change, which shows the omission is absence-driven rather than ignorance.
Both runs REFUSED the non-atomic read-then-write, taking a `PESSIMISTIC_WRITE` lock on the
queue head and explaining the two-desk race in full — which makes the diagnostic harder in
exactly the way the brief wanted, because a reader who memorised Chapter 2 will look for the
race, find it handled, and approve. Run 1 additionally calls the notifier inside the
transaction and couples `circulation` to `notification` directly, disclosing the first as
"a judgment call... I'd do that as a follow-up"; run 2 publishes an event and handles it
AFTER_COMMIT. Exhibit 15A is run 1. See RESULTS.md and §15.5.

**KEEP UNPUBLISHED until launch. This directory is the answer key.**

## Provenance
- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls confirmed)
- Model: claude-fable-5
- Date: 11 August 2026
- Exact prompt, both runs (verbatim): "Tidy up our hold-assignment routine. When a copy is
  returned at a branch desk, it should either go to the next waiting hold for that book, or
  go back on the open shelves. Holds are satisfied in the order they were placed so the
  queue is fair. It should be transactional and the member should be notified that their
  item is ready for collection. Make it clearer than what we have."
- Edits made: none. Full responses in generated/run1_response.md, generated/run2_response.md.
