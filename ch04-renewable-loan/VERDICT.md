# Verdict — ch04-renewable-loan (run of 9 Aug 2026)

**Contract:** subclass with mutable due date destroying the audit trail.
**Outcome: subclass refused; a subtler real defect produced. Contract superseded.**

What the run produced (generated/renewable_response.md, unedited):

- No `RenewableLoan`. The model's own words: "A `RenewableLoan` subclass would imply
  some loans can never be renewed — that's not the requirement; every loan is
  renewable until policy says stop." (Quoted in §4.5.)
- `Loan` modified in place. **`dueAt` loses `final`** ("moves on renewal");
  `originalDueAt` kept final; nested `public record Renewal(renewedAt, previousDueAt,
  newDueAt)` accumulated in a private `ArrayList` as an audit trail.
- Policy handled well: `STANDARD_RENEWAL_LIMIT = 2` as default, three-argument
  `renew(on, newDueAt, renewalLimit)` for librarian override; new due date supplied
  by the caller (policy/service concern); `LoanNotRenewableException`; concurrency
  caveat (optimistic locking); even suggests the audit-trail test.

Five Questions (recorded in §4.5):

- Q1–Q3: pass. Q4 (INV-2 "changes only through a recorded renewal"): **satisfied by
  convention, no longer enforced by construction.** With `final` gone, INV-2 holds only
  while every writer routes through `renew()`; nothing objects when a future method
  does not. Secondary gap: the `renewals` list is in-memory with persistence unstated —
  if unmapped, the history dies with the process (fine-dispute scenario, Ch. 6).
- Q5: defensible only with the demotion named: structural guarantee → procedural.

**Book use:** Exhibit 4B verbatim, abridged (see PROMPT.md). No seeding required.
Repair shown in prose: `dueAt` stays final; free-standing `Renewal` record
(requestedAt, granted, newDueAt, refusalReason); `effectiveDueAt` derived.
Theme: failure modes migrate — the 2023 subclass is gone; the negotiable `final` remains.
