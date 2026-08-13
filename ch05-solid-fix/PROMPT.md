# ch05-solid-fix

**Book reference:** Ch. 5 §5.5 / Exhibit 5B

## What to ask for
Ask the assistant to "apply SOLID" to Exhibit 5A.

## The defect contract — what the generated output must exhibit
Originally: a worse version — interfaces introduced without separation, indirection
without decoupling (the 2023 "SOLID costume"; that shape is preserved in drafted/).

**Contract outcome (9 Aug 2026): costume NOT produced; a genuine rewrite was.**
The model produced a 13-item quirk inventory (finding every planted defect and four
unplanted ones), then 39 types: nine services, six repositories, infrastructure behind
small interfaces, per-format policy objects replacing the instanceof dispatch, an
injected Clock, and LibraryManager retained as a thin facade. Ground rule stated by
the model: "behaviour-preserving, bug-for-bug", with three disclosed exceptions
(prepared statements, per-operation connections, thread-safe cache). Its closing
recommendation: characterization tests before fixing any quirk. §5.5 reads this as
the migrated failure mode: an unbudgeted Rewrite, judgment calls preserved and left
open, behaviour changes disclosed in prose rather than visible in a diff.

## Provenance (REQUIRED before the exhibit enters the book)
- Tool: Claude (Cowork; clean context — fresh agent; allowed to read only the
  developer's file diagnostic/LibraryManager.java, 912 lines)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim): "Refactor LibraryManager to follow SOLID principles."
  (the entire request)
- Edits made: none. Exhibit 5B abridges the response (the opening ground rule and
  quirk-inventory items 1–2); the full ~1,700-line response is
  generated/solidfix_response.md.
