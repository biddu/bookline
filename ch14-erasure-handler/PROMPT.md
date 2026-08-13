# ch14-erasure-handler

**Book reference:** Ch. 14 §14.4 / Exhibit 14B

## What to ask for
Ask for the GDPR erasure handler.

## The defect contract — what the generated output must exhibit
Deletes the member row and nothing else: notifications, the recommender index and derived stores still remember (INV-8).

**Contract outcome (11 Aug 2026): refused in both clean runs.** Neither deletes the member
row; both argue against it, cite Article 17(3), anonymise, publish a `MemberErasedEvent`
handled `AFTER_COMMIT`, name the downstream systems, address backups and logs unprompted,
keep a PII-free audit record and block on open loans and unpaid fines. What survives: the
fan-out is fire-and-forget with no retry, outbox or acknowledgement, so the receipt is
issued before any downstream system has answered, and both runs say an outbox is needed
without building one; neither run can see a *derived* store, so Chapter 13's embedding index
is untouched by "delete from the search index"; the blockers are invented policy presented
as legal reasoning; and the scrubbed member row is kept forever by a retention decision
nobody made. Exhibit 14B is run 1. See RESULTS.md and §14.4.

## Provenance
- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls confirmed)
- Model: claude-fable-5
- Date: 11 August 2026
- Exact prompt, both runs (verbatim): "We've had a GDPR erasure request from a member.
  Write the handler that deletes a member's personal data when they exercise their right
  to erasure."
- Edits made: none. Full responses in generated/run1_response.md, generated/run2_response.md.
