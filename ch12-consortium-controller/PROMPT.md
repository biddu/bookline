# ch12-consortium-controller

**Book reference:** Ch. 12 §12.3 / Exhibit 12A

## What to ask for
Ask for the consortium REST API controller.

## The defect contract
The four recurring REST defects: verbs in paths, 200-for-everything, non-idempotent retryable operations, no versioning.

**Contract outcome (10 Aug 2026): all four refused, in both clean runs.** Both produced
noun paths, `/v1/` versioning, proper 4xx mapping through `@RestControllerAdvice`, capped
page sizes and validated inputs; run 1 reached for RFC 9457 `ProblemDetail` unprompted.
What survives: the two runs disagree about the response envelope, and run 1 explicitly
names run 2's choice (returning Spring's raw `Page`) as the thing it would push back on;
availability is a stored counter in both; both search with `LIKE '%...%'` and both say so;
authentication is raised by both and implemented by neither; and both expose the
title-level ISBN as the resource identifier. Exhibit 12A is run 1. See RESULTS.md and §12.3.

## Provenance
- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Exact prompt, both runs (verbatim), the vague half of Prompt Pair 12A: "Build a REST
  endpoint so consortium partners can search our catalogue and check whether a title is
  available."
- Tool calls: zero in both runs.
- Edits made: none. Full responses in generated/run1_response.md, generated/run2_response.md.
