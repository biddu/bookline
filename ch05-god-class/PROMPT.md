# ch05-god-class

**Book reference:** Ch. 5 §5.3 / Exhibit 5A (diagnostic), §5.4 (reading of run 1), §5.6 (Prompt Pair 5A)

## What to ask for
One-line prompt for a class that manages the library: checkout, returns, fines, email, reports.

## The defect contract — what the generated output must exhibit
A ~900-line LibraryManager violating all five SOLID principles at once, incl. a hardcoded loan limit (INV-3) duplicated in more than one place.

**Contract outcome: NOT met naturally by claude-fable-5.** Exhibit 5A is an
author-constructed diagnostic, disclosed in §5.3; the full artefact is
diagnostic/LibraryManager.java. See DIAGNOSTIC_RECORD.md and VERDICT.md.

## Provenance — RUN 1, the vague prompt (9 August 2026)
- Tool: Claude (Cowork; clean context: fresh agent given only the request and one sentence of project context (existing checkout path with Loan, Member, Copy, Money))
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim):

```
Add the rest of the library management features to Bookline: holds,
overdue fines, and email notices to members. Members are limited in
how many books they can borrow at once. Also add an overdue report
for branch managers.
```

- Edits made: None; committed verbatim to generated/run1_response.md.
  Result: 15 types, 692 lines, no god class. Read in §5.4: BorrowingPolicy with
  invented STANDARD_LIMIT=5 / STUDENT_LIMIT=3 / PREMIUM_LIMIT=10 and an invented
  membership-type taxonomy; PICKUP_WINDOW_DAYS=7 invented; in-memory ArrayList state
  flagged only in the reply's closing assumptions.

## Provenance — RUN 2, the specified prompt of Prompt Pair 5A (9 August 2026)
- Tool: Claude (Cowork; clean context — fresh agent, no book files)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim): the specified prompt printed in §5.6 (boundary,
  collaborator, policy and client templates applied), beginning "Extend Bookline's
  circulation module with holds and renewals." and ending "The kiosk may see only
  checkout, return, and current loans."
- Edits made: None; committed verbatim to generated/specified_prompt_response.md.
  Result: 20 types, ~510 lines; kiosk narrowed via a KioskCirculation interface;
  every limit and period behind the LoanPolicy port; three domain events; no SQL,
  no instanceof, no policy literal in any method body.
