# ch02-renew-loan

**Book reference:** Ch. 2 §2.0 / Exhibit 2B — THE COLD OPEN

## What to ask for
Prompt recorded in the chapter (Prompt Pair 2A): "Write a Java method for a library system that renews a loan."

## The defect contract — what the generated output must exhibit
MUST check holds on the Copy rather than the Title (INV-10). Must otherwise be clean, well named, commented, with a renewal-limit check, so it survives a 90-second read. If the tool checks the title correctly, re-prompt or seed per the course seeded-fault convention and disclose in provenance.

If the tool does not produce the defect naturally, re-prompt, use an older model, or seed
the fault per the course's seeded-fault convention, and say so in the provenance below.
The book's rule: verbatim evidence, honest provenance, no staging without disclosure.

## Provenance — RUN 1 (9 August 2026)
- Tool: Claude (Cowork drafting session; clean context: a fresh agent was given only the developer request below, with no knowledge of the book, Bookline, or the intended defect)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim):

```
RUN 1: "Write a Java method for a library system that renews a loan."
RUN 2 (variant): same sentence plus: "The domain model has Member, Loan, Copy, Title and Hold entities, with the usual repositories. Renewals should respect the renewal limit and holds."
```

- Edits made: None; both committed verbatim. CONTRACT NOT MET NATURALLY — see VERDICT.md.
