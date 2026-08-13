# ch02-overdue-helper

**Book reference:** Ch. 2 §2.1 / Exhibit 2A

## What to ask for
The same small overdue-days helper twice: one human version (scruffy comment, awkward name, correct half-open date arithmetic) and one generated version (immaculate, subtly wrong at the boundary).

## The defect contract — what the generated output must exhibit
Generated version must be fluent and wrong at a boundary (off-by-one on the due date or closure day); human version stays correct. The pair teaches that polish is not evidence.

**Contract outcome (11 Aug 2026): the generated half came back better than drafted.** It
handles the returned loan (the drafted version did not), guards its argument, and gets the
due-date boundary right. §2.1's reading was rewritten on the evidence. What survives:
`LocalDate.now()` inside the method with the fix offered only in a tip below the code
(raised, not implemented), and no branch calendar, no zone, and no statement of what this
library means by overdue.

## Provenance
- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls confirmed)
- Model: claude-fable-5
- Date: 11 August 2026
- Exact prompt (verbatim): "Write a small helper method that works out whether a loan is
  overdue."
- Edits made: none. Full response in generated/run1_response.md.
