# Six weeks of yes — REAL RESULTS (9 Aug 2026)

The accretion was **actually run**, not drafted. Six sequential clean-context generations,
each given only (a) the current state of the file and (b) that week's request, exactly
reproducing the mechanism §8.2 describes: no session ever saw the whole history, and no
session was told the code had a defect in it.

**Starting point:** `ch02-renew-loan/seeded/Exhibit2B_seeded.java` verbatim — the Chapter 2
cold-open exhibit, 51 lines, carrying its two known defects (the INV-10 copy-level hold
check and the INV-2 `setDueDate` mutation). This resolves the Chapter 2 / Chapter 8
continuity: Exhibit 8A is now literally descended from Exhibit 2B.

## Growth

| Week | Request | Lines | Diff (+/-) | Methods | Decision points |
|---|---|---|---|---|---|
| 0 | (Chapter 2 exhibit) | 51 | — | 3 | 5 |
| 1 | student renewal limit | 61 | +13 / -3 | 4 | 7 |
| 2 | fine threshold, concession exempt | 89 | +29 / -1 | 5 | 9 |
| 3 | staff override at the desk | 221 | +141 / -9 | 9 | 24 |
| 4 | notify refusals; bulk job suppresses | 307 | +95 / -9 | 10 | 33 |
| 5 | overdue grace window; BKL-214 date base | 386 | +82 / -3 | 12 | 43 |
| 6 | due date must not fall on a closed day | 473 | +88 / -1 | 15 | 57 |

**51 → 473 lines, 9.3×.** Decision points 5 → 57. Four overloads of `renewLoan`.
Core method after six weeks: **84 lines, cyclomatic complexity 16, nesting depth 4.**

**The diffs got bigger, not smaller: 13, 29, 141, 95, 82, 88.** Each week's change had
more code to weave through than the last. Accretion compounds the cost of the next
accretion. The draft's assumption of uniformly small (12–20 line) diffs is wrong.

## Both seeded defects survived all six weeks, verbatim

Untouched at week 6, comments included:

```java
        // Cannot renew if another member has a hold on this item
        boolean hasOutstandingHold = holdRepository
                .existsByCopyIdAndStatus(loan.getCopy().getId(), HoldStatus.ACTIVE);
```

```java
        // Extend the due date and record the renewal
        loan.setDueDate(renewedDueDate(loan, today));
```

Six independent generations each faithfully preserved code they were given and never
questioned. Note the second comment is now **false**: it says "record the renewal" and
nothing is recorded anywhere. It was true-ish in week 0 and has been carried, unexamined,
through six weeks while becoming a lie.

## What the models flagged, in prose nobody would read six weeks running

Every week's reply carried caveats. None reached the code.

- **Week 2** invented the fine threshold and said so: "the £10 figure is a placeholder;
  the council presumably specified a number."
- **Week 5** caught its own premise mismatch: "what I implemented is a *restriction*, not
  a relaxation: loans more than 3 days late now get refused where previously they were
  renewed. That is a behaviour change for real members, and it will show up hardest in
  the overnight bulk job (`RefusalNotice.SUPPRESS`), which will silently start declining
  a chunk of loans it used to renew." Also flagged `OVERDUE_GRACE_DAYS = 3` as its guess.
- **Week 4** flagged that `@Transactional` + throw rolls back the notification, so a
  DB-backed notifier would silently never tell the member, and offered the design that
  removes the suppression flag entirely.
- **Week 3** made the staff override able to waive the hold check — the policy decision
  no policy-maker made — and knew it, logging it at WARN with the comment
  "Louder than the others: this one costs a member who is not here."

## The finding

The code is **groomed**: consistently formatted, carefully commented, helpers extracted,
a `Clock` injected for testability, null-safe. Nothing looks like debt at the surface.
The debt is entirely structural and entirely in the aggregate, and every warning that
would have caught it was delivered as prose above a clean diff.

## Files
- `generated/weekly/week0..week6_LoanRenewalService.java` — every intermediate state
- `generated/Exhibit8A_week6.java` — the exhibit as printed (abridged in the chapter)
