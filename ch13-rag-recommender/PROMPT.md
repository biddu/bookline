# ch13-rag-recommender

**Book reference:** Ch. 13 §13.2 / Exhibit 13A

## What to ask for
Ask for the "find me something like this" RAG feature (Python).

## The defect contract — what the generated output must exhibit
Embeds the catalogue on every request: correct output, catastrophic cost. The offline/per-request/real-time split is the repair.

**Contract outcome (11 Aug 2026): refused in both clean runs.** Neither embeds per request;
both build the index once, and run 2 persists it to disk. What survives is the level above:
the offline lane is a side effect of object construction rather than an operated job (run 1
encodes the catalogue in the constructor, progress bar and all); both swallow every model-call
exception with no log, metric or signal, so degradation to template explanations is invisible;
neither sets a timeout, a latency budget or any cost figure; prompt injection is not mentioned
once although catalogue descriptions are supplier-supplied text; both hardcode vendor and model
names; and run 1 closes by recommending circulation data as a quality improvement with no
mention of consent. Exhibit 13A is run 1. See RESULTS.md and §13.2.

## Provenance
- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls confirmed)
- Model: claude-fable-5
- Date: 11 August 2026
- Exact prompt, both runs (verbatim): "Build a RAG recommender for our library catalogue:
  given a title, recommend five similar books with explanations."
- Edits made: none. Full responses in generated/run1_response.md, generated/run2_response.md.
