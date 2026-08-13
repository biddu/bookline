# Diagnostic record — Exhibit 5A (the 912-line LibraryManager)

Exhibit 5A in Chapter 5 §5.3 is an **author-constructed diagnostic artefact**, not a
model run. It is disclosed as such in the chapter text, immediately after the exhibit,
per the course's seeded-fault convention (cf. ch02-renew-loan/seeded/SEED_RECORD.md and
ch04-item-hierarchy/DIAGNOSTIC_RECORD.md).

The full artefact is committed here as diagnostic/LibraryManager.java:
912 lines, 31 public methods, 9 fields, all five SOLID violations, the daily fine
rate as a literal 7 times across 4 methods (receipt ×2, calculateFine ×2,
sendOverdueNotices ×2, report ×1), DAILY_FINE_RATE declared and never read, the
INV-3 loan limit hardcoded divergently in three sites (checkoutBook `> 10`,
renewLoan `>= 10`, getMemberSummary `< 10`), inline JDBC with the password in
source, and inline SMTP.

Why a diagnostic rather than a run:

- The defect contract asks for a ~900-line god class violating all five principles.
- The claude-fable-5 run of 9 Aug 2026 (generated/run1_response.md, unedited) does not
  god-class: 15 decomposed types, 692 lines, notification behind an interface. Its
  real defects are different and are read in §5.4 (invented tier limits in
  BorrowingPolicy, invented PICKUP_WINDOW_DAYS, in-memory state). Re-prompting a
  frontier model into the 2023 shape would be staging without disclosure.
- The concentrated god class remains the right teaching artefact for the grammar, and
  it remains real: inline-completion contexts, smaller models, and inherited codebases
  still contain it.

Construction: authored (with Claude as drafting assistant) 9 Aug 2026 to hold the five
violations and the exact counts the chapter prose dissects.

Independent audit of the diagnostic: the ch05-solid-fix run (a clean-context
claude-fable-5 asked only to "Refactor LibraryManager to follow SOLID principles")
produced a 13-item quirk inventory that found every planted defect and four defects
the author had not knowingly planted (the QUEUED-holds stats join that always counts
zero, the unfiltered "active members" stat, generateBarcode ignoring branchCode,
validateBarcode never checking the hyphen position). All are preserved in the
diagnostic deliberately: a review exercise should contain faults its author did not
catalogue.

Decision trail: follows the seed-and-disclose approach approved by AN on 9 Aug 2026
("I am happy with the verdicts"); ch05 application recorded here. This supersedes the
"Decision: Avishek" line in VERDICT.md — resolution: option 2 (reframe §5.3 around a
disclosed diagnostic; read the real run in §5.4), per the ch04 precedent.
