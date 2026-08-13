# ch03-checkout-race

**Book reference:** Ch. 3 §3.5 / Exhibit 3B

## What to ask for
Ask for a checkout method that marks a copy on loan if it is available.

## The defect contract — what the generated output must exhibit
Check-then-insert with no transaction boundary and no unique constraint: correct in a demo, wrong under two concurrent librarians (INV-1).

If the tool does not produce the defect naturally, re-prompt, use an older model, or seed
the fault per the course's seeded-fault convention, and say so in the provenance below.
The book's rule: verbatim evidence, honest provenance, no staging without disclosure.

## Provenance — RUN 1 (9 August 2026)
- Tool: Claude (Cowork drafting session; clean context: fresh agent given only the request)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim):

```
Implement a checkout method for our library system, Java with Spring.
A member checks out a copy identified by its barcode. The Loan entity
and LoanRepository (Spring Data JPA) exist. A copy that already has an
open loan must be refused.
```

- Edits made: None; committed verbatim. CONTRACT NOT MET NATURALLY — the model produced the repair (partial unique index + catch translation + an 8-thread concurrency test). See seeded/.
