# ch03-three-prompts

**Book reference:** Ch. 3 §3.3 / Exhibit 3A

## What to ask for
Three prompts of increasing precision for the Loan class (vague / structured / C-I-E-shaped with invariants). All three prompts are drafted verbatim in the chapter.

## The defect contract — what the generated output must exhibit
Output 1: public fields or blanket setters, no validation. Output 2: better structure, invariants still unenforced. Output 3: constructor validation present but at least one gap remains for the prose to find.

If the tool does not produce the defect naturally, re-prompt, use an older model, or seed
the fault per the course's seeded-fault convention, and say so in the provenance below.
The book's rule: verbatim evidence, honest provenance, no staging without disclosure.

## Provenance — RUN 1 (9 August 2026)
- Tool: Claude (Cowork drafting session; clean contexts: three fresh agents, one per prompt, no shared session, no book knowledge)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim):

```
RUN 1: "Write a Java class for a library loan."
RUN 2 and RUN 3: the fuller and C-I-E prompts exactly as printed in §3.3 of the chapter.
```

- Edits made: None; all three committed verbatim. Chapter excerpts are abridged with elisions marked.
