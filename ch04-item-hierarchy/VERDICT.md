# Verdict — ch04-item-hierarchy (run of 9 Aug 2026)

**Contract:** all four traps in one generated hierarchy. **Outcome: not met naturally.**

What the run produced (generated/hierarchy_response.md, unedited):

- Two-level tree: abstract `LibraryItem` → `Book`, `Dvd`, `Audiobook`, `Periodical`
  (JPA SINGLE_TABLE, discriminator column).
- Reference stock as data: `CirculationPolicy { LENDABLE, REFERENCE_ONLY }` on the
  base class, `isLendable()`, enforced once in `LoanService.checkOut(...)` with a
  domain exception. The model explicitly argued against a `ReferenceItem` subclass
  (combinatorial explosion; JPA identity loss on reclassification).
- Policy-aware touches: `loanPeriodDays()` default 21, overridden to 7 for DVD and
  periodical; sealed-hierarchy option discussed.

Checklist 4A applied (recorded in §4.6):

| Q | Result |
|---|---|
| 1 substitution | PASS — no subtype breaks the base contract |
| 2 override | PASS — no throw/dummy overrides (`loanPeriodDays` specialises honestly) |
| 3 variance | FAIL — `Book`/`Dvd`/`Audiobook`/`Periodical` differ only in fields; format is still a type |
| 4 next subclass | FAIL — large print is a new class + mapping, not an enum constant |
| 5 name test | Mixed — tree survives the name test at depth 2, but only because it is shallow |

Also carried through: work/object fusion (barcode, title, isbn, circulationPolicy on
one class), i.e. the Title/Copy split is absent — the expensive Wrong IS-A survives.

**Book use:** Exhibit 4A = authored diagnostic (disclosed; see DIAGNOSTIC_RECORD.md).
This run = §4.6 confirmation reading, quoted verbatim on the reference-policy refusal.
Theme: failure modes migrate; the loud traps died, the quiet structural one survived.
