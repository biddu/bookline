# Diagnostic record — Exhibit 4A (the nine-class tree)

Exhibit 4A in Chapter 4 §4.3 is an **author-constructed diagnostic artefact**, not a
model run. It is disclosed as such in the chapter text, immediately after the exhibit,
per the course's seeded-fault convention (cf. ch02-renew-loan/seeded/SEED_RECORD.md).

Why a diagnostic rather than a run:

- The defect contract asks for all four traps in one artefact (Wrong IS-A, Forced
  Override, Leaky Abstraction, Naming Trap).
- The claude-fable-5 run of 9 Aug 2026 (generated/hierarchy_response.md, committed
  unedited) does not exhibit the Forced Override, the deep tree, or the leaked
  circulation state. It models reference stock as a `CirculationPolicy` enum and says
  why. Re-prompting a frontier model into producing the 2023-era tree would be staging
  without disclosure, which the book's evidence rule forbids.
- The four traps remain real and teachable: they are the standard shape of tutorial
  and textbook hierarchies (the models' training genre) and of assistant output two
  model generations back, and they still appear in older codebases and smaller models.

Construction: drafted by the author (with Claude as drafting assistant) to contain the
four traps in nine classes / five levels; every trap mechanism (override that throws,
format-as-type, circulation state on the catalogue class, name-driven subclassing) is
drawn from the tutorial genre the chapter dissects.

Chapter treatment:
- §4.3 marker labels 4A "diagnostic exhibit, author-constructed" and the following
  paragraph discloses provenance in full.
- §4.6 reads the real run against Checklist 4A: questions 1–2 pass, 3–4 fail
  (format-as-type; Title/Copy fusion). The migration of failure modes is the lesson.

Decision trail: follows the seed-and-disclose approach approved by AN on 9 Aug 2026
for ch02 ("I am happy with the verdicts"); ch04 application recorded here.
