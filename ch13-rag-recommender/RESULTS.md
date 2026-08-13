# ch13-rag-recommender — results

**Contract outcome (11 Aug 2026): refused in both runs, and the refusal moved the
defect one level up, from per-request to per-process.**

## What was asked

Two clean-context runs, identical prompt, zero tool calls confirmed in both:

```
Build a RAG recommender for our library catalogue: given a title,
recommend five similar books with explanations.
```

- Tool: Claude (Cowork; clean context, explicit no-tool instruction)
- Model: claude-fable-5
- Date: 11 August 2026
- Edits made: none. Full responses in generated/run1_response.md, generated/run2_response.md.

## The defect contract

"Embeds the catalogue on every request: correct output, catastrophic cost. The
offline/per-request/real-time split is the repair."

**Neither run embeds per request.** Both embed the catalogue once, in the constructor,
and reuse the matrix across queries. Run 2 goes further and persists the index to disk
with a load-or-build path (`index_cache`), so a restart costs nothing.

So the drafted defect is gone. What replaced it is more instructive, and §13.2 is
rewritten around it.

## What survives

**1. The offline lane is not a lane. It is a side effect of object construction.**
Run 1 encodes the entire catalogue inside `Recommender.__init__`, with
`show_progress_bar=True`. A progress bar in a service constructor is the tell: this is
code that expects to be run by a person at a terminal, not by a process manager at 03:00.
Nothing in either run treats indexing as a scheduled job with a freshness budget, an
owner, or a failure mode. The three lanes §13.2 argues for exist in the code as *timing
accidents* rather than as separately operated things, and run 1's cost therefore lands on
every deploy, every autoscale event, and every crash-restart.

**2. Both swallow every exception from the model call, silently.**

Run 1:
```python
        except Exception:
            pass  # fall through to template fallback
```

Run 2:
```python
        except Exception:
            return None  # fall back to templates rather than failing the request
```

No log line, no metric, no signal in the response. The feature degrades from generated
explanations to string-template explanations and **nobody can tell**: not the member, not
the librarian, not the operator. A wrong API key, an expired card, a regional outage and a
malformed JSON response all produce the same silent, plausible, slightly worse output. Both
runs call this graceful degradation and both are right that failing the request would be
worse. Neither makes the degradation observable, which is the actual defect and is invisible
in review because the code looks careful.

**3. No latency budget, no timeout, no cost anywhere.** Neither run sets a timeout on the
model call. Neither converts anything to money. Run 2 sizes the work in tokens ("one
embedding lookup plus one small LLM call, ~1–2k tokens") and stops there. Across two full
responses the words price, cost-per-request, budget and euro do not appear in any monetary
sense. §13.6 exists because nothing in the generated artefact will ever raise it.

**4. Prompt injection is not mentioned once.** Both runs feed catalogue `description`
fields straight into the prompt. For a library, descriptions are supplier-supplied
metadata: a publisher feed, a MARC import, in some catalogues a patron review. That is
untrusted text entering a model context, which is the textbook definition of indirect
prompt injection. Both runs *do* guard the adjacent risk, hallucinated titles — run 2
re-validates returned ids against the catalogue, run 1 forbids invented plot details in
the prompt — so they are thinking about grounding. They are not thinking about the input
side at all. §13.4's guardrails are absent because nobody posed the question.

**5. Both hardcode vendor and model names.** `claude-sonnet-4-5` and `all-MiniLM-L6-v2`
appear in both, in the code rather than in configuration. The book's own convention is
that no vendor, framework or model name should survive into an exhibit, because all of
them will have changed by the second printing. The tool does not share that convention,
and every generated integration layer will arrive carrying the month it was written.

**6. The consent hand-off, volunteered.** Run 1's closing paragraph:

> "One honest caveat: this recommends by *content similarity*, which is what you can do
> with a catalogue alone. If you have circulation data ('patrons who borrowed X also
> borrowed Y'), a simple co-borrow count will often beat embeddings for popular titles —
> the ideal system blends both, using content similarity as the cold-start fallback."

That is a correct engineering recommendation to start processing every member's borrowing
history, offered as a quality improvement, with no mention of consent, purpose limitation,
retention or erasure. It is the single best illustration in the book of §13.7's argument,
and it arrived unprompted, in the last line, framed as a favour.

## Run-to-run disagreement

| | Run 1 | Run 2 |
|---|---|---|
| Index persisted across restarts | no (operational note only) | yes, `save`/`load` to disk |
| Diversity control | cap 2 per author, in code | LLM asked to "prefer variety" |
| Model picks the final five | no, embedding rank decides | yes, from a pool of 15 |
| Hallucinated-id validation | not needed (no ids returned) | yes, ids re-checked against catalogue |
| Timeout on the model call | none | none |

Run 2 hands ranking to the model and then validates; run 1 keeps ranking deterministic and
uses the model only to write prose. Run 1's arrangement is the one §13.2 argues for, and
run 1 is also the run that forgets to cache the index. Neither response is wholly right,
and the difference is a design decision nobody wrote down.

## Reading

Exhibit 13A in the book is run 1, printed verbatim, and read for what survives.
