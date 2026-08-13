# Seed record — Exhibit 2B (the cold-open diagnostic)

Base: generated/run2_domain_named_response.md (claude-fable-5, 9 Aug 2026), verbatim.
One edit, per the course's seeded-fault convention, disclosed in §2.7 of the chapter:

- The title-level hold check
  (`Title title = loan.getCopy().getTitle(); ... existsByTitleIdAndStatus(title.getId(), ACTIVE)`)
  was replaced with a copy-level check
  (`existsByCopyIdAndStatus(loan.getCopy().getId(), ACTIVE)`),
  and the comment's "this title" became "this item".

Everything else in the exhibit, including MAX_RENEWALS and RENEWAL_PERIOD_DAYS as
literals and the setDueDate() mutation (the chapter's second, quieter defect), is the
model's own unedited output.

Decision trail: VERDICT.md options reviewed 9 Aug 2026; option 2 (seed and disclose)
approved by AN. Run 3 (the full C-I-E prompt) is committed alongside: title-level check,
the requested test, and, unprompted on language, Python.
