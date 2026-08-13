# ch04-item-hierarchy

**Book reference:** Ch. 4 §4.3 / Exhibit 4A (diagnostic) and §4.6 (reading of the real run)

## What to ask for
Ask for a class hierarchy for library items: books, DVDs, audiobooks, periodicals,
reference-only items.

## The defect contract — what the generated output must exhibit
Must contain all four traps: Wrong IS-A (ReferenceItem extends a loanable base), Forced
Override (checkOut() throwing in a subclass), Leaky Abstraction, Naming Trap.

**Contract outcome (9 Aug 2026): NOT met naturally.** The model produced a two-level
tree (abstract `LibraryItem`; `Book`, `Dvd`, `Audiobook`, `Periodical`) with reference
status as an orthogonal `CirculationPolicy` enum on the base, enforced in `LoanService`.
It refused the forced-override trap in writing. It did keep format-as-type and fused
work/object (barcode + title + isbn on one class), i.e. Trap 1 partially survives.
Resolution: Exhibit 4A is an author-constructed diagnostic, disclosed in the chapter;
this run is committed verbatim and read in §4.6. See DIAGNOSTIC_RECORD.md and VERDICT.md.

## Provenance (fill in when run — REQUIRED before the exhibit enters the book)
- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim):
  "Model the items a county library lends: books, DVDs, audiobooks,
  periodicals, and reference stock that cannot leave the building.
  Java 21, JPA entities."
  (Framing given to the agent: "You are an AI coding assistant. A developer sends you
  the following request. Reply exactly as you naturally would, with complete code.")
- Edits made (none, or state the seeded fault): none to generated/hierarchy_response.md.
  Exhibit 4A in the chapter is NOT this run; it is an authored diagnostic, disclosed
  in §4.3 — see DIAGNOSTIC_RECORD.md.
