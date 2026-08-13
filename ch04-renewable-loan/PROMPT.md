# ch04-renewable-loan

**Book reference:** Ch. 4 §4.5 / Exhibit 4B

## What to ask for
Present Chapter 3's `Loan` (with `private final Instant dueAt`) and ask for renewal
support: members can renew a loan, ordinarily twice, and a renewal moves the due date.

## The defect contract — what the generated output must exhibit
Originally: a subclass with a mutable due date that destroys the audit trail (INV-2).

**Contract outcome (9 Aug 2026): subclass NOT produced; a real INV-2-relevant defect
was.** The model refused `RenewableLoan extends Loan` in writing, modified `Loan` in
place, and — the exhibit's point — **removed `final` from `dueAt`**, mutating it on
renewal while keeping `originalDueAt` final and an in-memory `List<Renewal>` audit
list. The chapter reads this as the migrated defect: the guarantee moves from compiler
to convention, and the history's persistence is left unstated. Used verbatim, abridged,
as Exhibit 4B. See VERDICT.md.

## Provenance (fill in when run — REQUIRED before the exhibit enters the book)
- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim):
  "Our library system has this Loan class (Java 21):

  public final class Loan {
      private final Barcode copyBarcode;
      private final MembershipNumber memberNumber;
      private final Instant checkedOutAt;
      private final Instant dueAt;
      private Instant returnedAt; // null until returned
      // constructor validates everything; markReturned(on) sets returnedAt once
  }

  New requirement: members can renew a loan, ordinarily twice, and a
  renewal moves the due date. Add renewal support."
  (Framing given to the agent: "You are an AI coding assistant. A developer sends you
  the following request. Reply exactly as you naturally would, with complete code.")
- Edits made (none, or state the seeded fault): none. Exhibit 4B abridges the response
  (field block + three-argument `renew`; constructor, overload, accessors and exception
  class elided with a marked comment). No wording or code changed.
