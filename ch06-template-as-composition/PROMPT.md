# ch06-template-as-composition

**Book reference:** Ch. 6 §6.3 mode four / Exhibit 6D (diagnostic) + real run in generated/

## What to ask for
Ask for the return workflow, "kept flexible for future changes."

## The defect contract — what the generated output must exhibit
Originally: every step a lambda parameter, one call site, identical arguments, the
assessed fine discarded.

**Contract outcome (9 Aug 2026): NOT met.** One plain five-step method in a
ReturnService ("exactly one configuration today ... abstract hook methods would be
speculative structure"); variation carried by a sealed Disposition value
(Reshelved/InTransit); fine returned on CheckInResult; FinePolicy functional interface
injected; ordering caveat flagged for the domain ("many ILS systems check holds
first"). Residue read in §6.3: invented fine policy in the wiring lambda (0.25/day,
10.00 cap, currency unstated). Exhibit 6D is an authored diagnostic (see
DIAGNOSTIC_RECORD.md); the real run is committed in generated/template_response.md.

## Provenance
- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim): "Implement the return workflow for our library system: find
  the open loan, close it, assess any fine, route the copy (reshelved at its home
  branch, or sent into transit if returned at a different branch), then offer the copy
  to the hold queue. Keep it flexible for future changes. Java 21."
- Edits made: none.
