# ch12-consortium-schema

**Book reference:** Ch. 12 §12.5 / Exhibit 12B

## What to ask for
Ask for the schema (DDL) for the consortium-facing data.

## The defect contract
The four schema failures: over-normalised joins on the search path, missing constraints
(INV-1 at the data layer), no soft delete (breaks INV-8 propagation), naive time handling
(INV-9).

**Contract outcome (10 Aug 2026): all four refused, in both clean runs.** Both produced
three sensible tables, the INV-1 partial-index pattern unprompted, status-based
deactivation rather than deletion, and TIMESTAMPTZ throughout. What survives is different
and local: no partner time zone in either run, so INV-9's second half has nothing to
compute against; no idempotency key at all in clean run A; personal data (contact name,
email, phone, free-text notes) invented into the schema with no erasure path; and the
cross-table INV-1 gap correctly identified by both runs and closed by neither.

**Methodology note, disclosed in §12.5 and in RESULTS.md.** A first pair of runs was
launched without a no-tool instruction and read this project's own locked system-design
document. Those runs are NOT clean-context evidence. They are preserved in
generated/contrast_informed_runs.md and used only as a disclosed contrast condition,
because they differ from the clean runs in exactly one variable and show what the
specification buys: the time zone, the policy-as-data columns, the named idempotency key,
and the remedy for the cross-table gap.

## Provenance
- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls confirmed)
- Model: claude-fable-5
- Date: 10 August 2026
- Exact prompt, all runs (verbatim): "Design the database schema for our consortium
  interlending feature. We need to track who the partner libraries are, what items they
  have requested from us, and which copies are currently out on loan to them. PostgreSQL,
  as a Flyway migration."
- Edits made: none. Exhibit 12B is generated/clean_runA_response.md.
