# Verdict — ch05-solid-fix (run of 9 Aug 2026)

**Contract:** interface ceremony without separation (the 2023 costume).
**Outcome: not produced; a genuine, larger thing was. Contract superseded.**

What the run produced (generated/solidfix_response.md, ~1,700 lines, unedited):

- Opening analysis naming the SOLID violations correctly, then a **13-item quirk
  inventory** with the ground rule "behaviour-preserving, bug-for-bug". The
  inventory found every defect planted in the diagnostic (three divergent loan-limit
  sites; notice/ledger rate disagreement; self-contradicting receipt) and four the
  author had not knowingly planted (QUEUED-holds stat that always reports 0;
  branch-stats "active members" not filtered by branch; generateBarcode ignoring
  branchCode; validateBarcode not checking the hyphen).
- 39 types: 9 services, 6 repositories, infra behind ConnectionProvider/EmailSender/
  AuditLog, CopyTypePolicy replacing instanceof (BookPolicy 21d + 0.25 capped at 60;
  PeriodicalPolicy 7d + 0.50 uncapped, divergences preserved with warning comments),
  injected Clock, LibraryManager kept as facade + composition root, every original
  signature intact including main().
- Three deliberate, disclosed behaviour changes: PreparedStatement everywhere
  (O'Brien now works; injection closed), per-operation connections with
  try-with-resources, ConcurrentHashMap cache.
- Closing recommendation: characterization tests first, "before anyone starts
  fixing" the quirks; quirk fixes need library-policy sign-off.

Five Questions (recorded in §5.5):

- Q1–Q2: pass, modulo the three disclosed changes. Q3: **the changes live in prose,
  not in any single diff line** — Q3 has moved into the notes.
- Q4: INV-3 and the March fines problem are structurally isolated but deliberately
  unfixed; the grace period still does not exist. Policy authority correctly
  identified as human.
- Q5: defensible only as a scheduled Rewrite with tests first; not as a drive-by.

**Book use:** Exhibit 5B = verbatim opening + quirk items 1–2, abridged (see
PROMPT.md). The 2023 costume shape remains in drafted/ and is described in prose.
Theme: failure modes migrate — then a costume in the code, now decisions in the notes.
