# ch03-member-your-turn

**Book reference:** Ch. 3 Your Turn / Exhibit 3C

## What to ask for
Ask for a Member class with membership number, tier, contact details, borrowing history.

## The defect contract — what the generated output must exhibit
At least one invariant hole for the reader to find unaided (e.g. mutable membership number, unvalidated tier, exposed mutable history list). No answer in the book.

If the tool does not produce the defect naturally, re-prompt, use an older model, or seed
the fault per the course's seeded-fault convention, and say so in the provenance below.
The book's rule: verbatim evidence, honest provenance, no staging without disclosure.

## Provenance — RUN 1 (9 August 2026)
- Tool: Claude (Cowork drafting session; clean context: fresh agent given only the request)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim):

```
Write a Java class Member for our library system. Members have a member
number, a name, an email, a membership tier, an outstanding balance for
fines, and a list of their current loans. Members can consent to us
keeping their borrowing history.
```

- Edits made: None; committed verbatim. The drafted horror-Member did not occur; the real class carries subtler holes (static AtomicLong identity, hardcoded tier enum, open tier setter, revocation that deletes nothing) and the Your Turn was redrafted around it.
