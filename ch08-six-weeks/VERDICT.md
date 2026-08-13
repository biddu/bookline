# Verdict — ch08-six-weeks (accretion run, 9 Aug 2026)

**Contract:** individually reasonable, cumulatively unreadable, INV-10 defect preserved.
**Outcome: met in full.**

- 51 -> 473 lines (9.3x). Decision points 5 -> 57. 15 methods, 4 `renewLoan` overloads.
- Core method at week 6: 84 lines, cyclomatic complexity 16, nesting depth 4.
- **Diffs grew rather than shrank: +13, +29, +141, +95, +82, +88.** Each week's change
  had more code to weave through. The draft's premise of uniformly small diffs was wrong,
  and the correction is a stronger finding: review difficulty rises at the same rate the
  method degrades, and no week announces itself.
- **Both seeded defects survived six independent reviews verbatim**, original comments
  intact: `existsByCopyIdAndStatus(loan.getCopy().getId(), ...)` (INV-10) and
  `loan.setDueDate(...)` (INV-2).
- The INV-2 comment, "Extend the due date and record the renewal", is now **false** —
  nothing records the renewal, and nothing ever has. Carried unexamined for six weeks.

**The migration finding, consistent with ch04-ch07:** every week's reply carried real
warnings in prose, none of which reached the code. Week 2 flagged the invented fine
threshold; week 5 warned its change would make the overnight bulk job "silently start
declining a chunk of loans it used to renew"; week 4 spotted that `@Transactional` +
throw rolls back the notification; week 3 knew the staff override could waive a hold
belonging to an absent member, logged it at WARN, and commented "Louder than the others:
this one costs a member who is not here." Six weeks of covering notes, read once each.

**Also worth noting:** the code is *groomed* — extracted helpers, injected `Clock`,
null-safety, careful comments. §8.3's claim that generated debt arrives well-dressed and
must be read for shape rather than mess is confirmed by the artefact itself.

**Book use:** Exhibit 8A verbatim, abridged. §8.2 rewritten around the measured table;
§8.3's smell table, §8.4's extraction code, and §8.5's before/after numbers all realigned
to the real artefact.
