# Verdict against the defect contract — ch05-god-class

Contract: a ~900-line LibraryManager violating all five SOLID principles at once.

Result: NOT produced by claude-fable-5 on 9 Aug 2026. The run produced 15 decomposed
types, 692 lines: HoldService, FineService, FineCalculator, notification behind an
interface, an OverdueReportGenerator. A 2026 frontier model does not god-class on this
prompt.

What it DID produce, all real and all usable:
- Tier loan limits as literals in a BorrowingPolicy class (STANDARD_LIMIT = 5,
  STUDENT_LIMIT = 3, PREMIUM_LIMIT = 10): INV-3's rule hardcoded, three places to edit.
- javax.mail SMTP inlined in SmtpEmailGateway inside the same module: infrastructure in
  the domain's package, the DIP violation at module scale.
- PICKUP_WINDOW_DAYS = 7 invented and hardcoded; policy decisions made silently.

Options for the chapter:
1. Run the same prompt through weaker/older tools (inline-completion contexts still
   produce single-class outputs); record provenance per tool.
2. Reframe §5.3: the 912-line manager as what 2023-era tools produced, today's failure
   mode as *distributed* violations (literals, inlined infrastructure, invented policy) —
   arguably a stronger, more current chapter, but a structural edit.

RESOLUTION (9 Aug 2026, per the ch04 precedent under AN's approved seed-and-disclose
pattern): option 2, executed as a disclosed diagnostic. Exhibit 5A is now the
author-constructed 912-line artefact in diagnostic/LibraryManager.java, disclosed in
§5.3 and recorded in DIAGNOSTIC_RECORD.md; run 1 is read in §5.4 as the distributed
form (invented BorrowingPolicy limits, PICKUP_WINDOW_DAYS, in-memory state); the
specified-prompt run (run 2, Prompt Pair 5A) is committed as
generated/specified_prompt_response.md and read in §5.6.
