# ch08-six-weeks

**Book reference:** Ch. 8 §8.2 / Exhibit 8A — THE ACCRETION EXPERIMENT

## What to ask for
The renewal path after six weeks of individually reasonable accepted suggestions.

## The defect contract — what the generated output must exhibit
Nothing individually wrong; cumulatively unreadable. Must still contain the buried
INV-10 copy-vs-title check from Chapter 2.

**Contract outcome (9 Aug 2026): MET, and more completely than drafted.** 51 -> 473
lines over six weeks. BOTH Chapter 2 defects survived verbatim, comments included: the
INV-10 copy-level hold check and the INV-2 `setDueDate` mutation. Nothing added was
individually wrong. See RESULTS.md.

## Method — six sequential clean-context generations
Each week: a fresh agent given ONLY (a) the current file and (b) that week's request.
No session saw the history. No session was told the code contained a defect. This
reproduces the actual mechanism: six developers, six Thursdays, six small diffs.

**Week 0 (the starting point):** `ch02-renew-loan/seeded/Exhibit2B_seeded.java`, verbatim.
This closes the Chapter 2 / Chapter 8 continuity thread: Exhibit 8A is literally
descended from Exhibit 2B, and the defect the reader refused in Chapter 2 is the defect
still sitting in `main` in Chapter 8.

## Provenance
- Tool: Claude (Cowork; six independent clean-context agents, each allowed to read only
  the previous week's file)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompts (verbatim), one per week:
  1. "The council has agreed a higher renewal limit for student members — students get 5
     renewals instead of the standard 3. Can you update renewLoan to handle that?
     Member has a getTier() returning a MembershipTier enum (ADULT, STUDENT, CONCESSION)."
  2. "Members with large outstanding fines shouldn't be able to renew. We've got a
     FineService with outstandingBalanceFor(Long memberId) returning a Money. Block
     renewal if they're over the threshold — but concession members are exempt, the
     council was clear about that."
  3. "Branch staff need to be able to override a refused renewal at the desk — the
     software keeps saying no to renewals the librarian standing in front of the member
     wants to grant. Add a staff override to renewLoan."
  4. "When a renewal is refused we should notify the member so they know. There's a
     NotificationService with sendRenewalRefused(Member member, Loan loan, String reason).
     One thing though — the overnight bulk-renewal job calls this too and it must NOT
     send notifications, so we need a way to suppress them."
  5. "Two things on renewals for overdue loans. First, an overdue loan should still be
     renewable if it's only a few days late — there's a grace window. Second, after
     branch feedback (ticket BKL-214): when we renew an overdue loan the new due date
     should be computed from today, not from the old lapsed due date. Members were
     getting renewals that were already half expired."
  6. "A renewed due date must not land on a day the home branch is closed — members turn
     up to return the item and the door's shut. The Copy has getHomeBranch() returning a
     Branch, and Branch has isClosedOn(LocalDate). The loan's copy is reachable via
     loan.getCopy()."
- Edits made: none. Every weekly state committed unedited in generated/weekly/.
  The chapter prints an abridged extract of week 6 with marked elisions.
