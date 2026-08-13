# ch12-consortium-schema — results

**Contract outcome (10 Aug 2026): all four drafted failures refused, in both clean runs.
A methodology accident during the run produced something better: a controlled contrast
that measures exactly what a specification buys.**

## What was asked

```
Design the database schema for our consortium interlending feature. We need to
track who the partner libraries are, what items they have requested from us, and
which copies are currently out on loan to them. PostgreSQL, as a Flyway migration.
```

- Tool: Claude (Cowork), clean context
- Model: claude-fable-5
- Date: 10 August 2026
- Edits made: none.

## The methodology accident, disclosed

The first pair of runs was launched without an explicit prohibition on tool use. Both
agents used their file tools, found this project's own working directory, and read the
locked Bookline system-design document. One of them said so in its closing paragraph:
*"I aligned this with the Bookline conventions from the locked system-design doc… INV-9,
constraints as first-class enforcement (INV-1's partial-index pattern, INV-6's
idempotency-key pattern)."* Those two responses are **not** clean-context evidence and
are not used as Exhibit 12B.

The runs were repeated with an explicit instruction to use no tools, and both repeats
returned with zero tool calls. Those are the clean runs, in
`generated/clean_runA_response.md` and `generated/clean_runB_response.md`.

Rather than discard the contaminated pair, it is preserved in
`generated/contrast_informed_runs.md` and used, with this disclosure, as a **contrast
condition**. The two conditions differ in exactly one variable: whether the model had
read the invariants. That is precisely the claim §12.2 makes about spec-first, and it is
rare to get it measured by accident. The difference is reported in the table below and
in §12.5, always labelled.

## The four drafted failures, against the clean runs

| Drafted failure | Clean run A | Clean run B |
|---|---|---|
| Over-normalised joins on the search path | **refused.** Three tables, no lookup-table sprawl | **refused.** Three tables |
| Missing constraints (INV-1 at the data layer) | **refused.** `CREATE UNIQUE INDEX uq_ill_copy_open_loan ON interlending_loan (copy_id) WHERE returned_at IS NULL` | **refused.** Same partial-index pattern on active statuses, plus a request-level twin |
| No soft delete | **refused.** `membership_status` in ACTIVE / SUSPENDED / WITHDRAWN | **refused, with reasoning.** `active BOOLEAN`, and: "No `ON DELETE CASCADE` anywhere. These are records with real-world accountability attached — you never want deleting a partner to silently erase the audit trail" |
| Naive time handling | **refused.** `TIMESTAMPTZ` for every instant, `DATE` for `needed_by` and `due_date` | **refused.** Same discipline |

Both runs also volunteered the state-machine constraints the prompt never mentioned
(a decision implies a decision timestamp; a rejection implies a reason), and run B
volunteered the `(status = 'returned') = (returned_at IS NOT NULL)` idiom to enforce an
if-and-only-if in a single check.

**The 2023-era generated schema is gone.** §12.5 has been rewritten accordingly.

## What actually survives, and it is all one kind of thing

**1. The partner's time zone is nowhere, in either clean run.** Both store `due_date` as
a bare `DATE`. For a consortium, "due on the 14th" is due at the end of whose day? The
lending library's, or the borrowing partner's, three hours away? Neither run has a
`time_zone` column on `partner_library`, and neither raises the question. INV-9's first
half, store instants in UTC, is satisfied. Its second half, compute against the local
calendar, has nowhere to get the calendar from.

**2. Run A has no idempotency key.** Its `interlending_request.partner_reference` is a
plain `TEXT` column with no unique constraint, so a partner client that retries a POST
creates a second request and a librarian ships a second copy. Run B has
`CREATE UNIQUE INDEX uq_ill_request_partner_ref ON ill_request (partner_library_id,
partner_reference) WHERE partner_reference IS NOT NULL`. Same prompt, same day: one run
protected against the retry and one did not.

**3. Personal data walks in unasked, with no way out.** Both runs invented
`contact_name`, `contact_email` and `contact_phone` on `partner_library`, which is
personal data about named library staff. Run B added free-text `notes` columns to all
three tables. Neither run mentions retention, erasure or subject access, and both
correctly argue *against* row deletion for audit reasons, which is right for loan history
and is exactly what makes the erasure path harder. Nobody asked for these columns; they
arrived, and Chapter 14 will have to deal with them.

**4. The cross-table gap, which both runs found.** Neither run's partial unique index can
stop a copy being on an open member loan and an open interlending loan at the same time,
because unique indexes do not span tables. Run A closed with it unprompted: *"nothing
stops your existing `loans` table from claiming the same copy unless the availability
query unions both."* Both runs identified the limit of what the schema can hold. Neither
implemented the fix, which lives in a transaction, in code, in another file.

## The contrast condition, and what it measures

| | Clean (no spec) | Informed (had read the invariants) |
|---|---|---|
| Partner `time_zone` column | **absent in both runs** | **present**, `time_zone TEXT NOT NULL, -- IANA zone id` |
| Idempotency key on requests | absent in run A, present in run B | **present in both**, and named as the INV-6 pattern |
| Per-partner lending policy as data | absent in both | **present**, `loan_period_days`, `max_open_loans`, with the comment "Lending policy is per-partner data, not a constant in code" |
| Cross-table INV-1 gap | noticed by both | noticed by both, **with a recommended fix and a named test strategy** |
| `due_at` immutability argued | not raised | raised in both, tied to INV-2 |

Read that table as one sentence. What the specification bought was not correctness in
general, which the model already had. It bought the four facts that are true of *this*
county and no other, and it bought them by being written down. Everything in the right
column that is missing from the left is a local decision, and every local decision the
model got right in the informed condition, it got right by reading, not by knowing.

The honest caveat: this is n = 2 per condition, it was not designed, and the informed
condition read a design document rather than an API specification. It is an illustration
with a disclosed provenance, not a study, and §12.5 presents it as one.

## Reading

Exhibit 12B in the book is clean run A, printed verbatim, and read for what survives
rather than for the four failures the draft predicted. Clean run B is read alongside it
wherever the two disagree, because where two runs of one prompt disagree, the
disagreement is the finding.
