# ch12-consortium-schema — the informed condition (NOT clean-context evidence)

**Read the disclosure first.** These two runs were launched with the identical prompt but
without an explicit prohibition on tool use. Both agents used their file tools, located
this project's working directory, and read the locked Bookline system-design document
before answering. One said so in terms in its closing paragraph. They are therefore
**not** clean-context evidence and are not Exhibit 12B.

They are preserved because the accident produced something useful: a contrast condition
differing from the clean runs in exactly one variable, namely whether the model had read
the invariants. §12.5 uses them only for that comparison, always labelled. n = 2 per
condition, not designed, not a study.

- Tool: Claude (Cowork), **context contaminated by the project's own files**
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt: identical to the clean runs.
- Tool calls: informed run 1 made 1; informed run 2 made 2.

## What the informed condition produced that the clean condition did not

**A partner time zone, in both runs.** Informed run 2:

```sql
    time_zone         TEXT        NOT NULL,          -- IANA zone id, e.g. 'Europe/Dublin'
```

with the accompanying note: *"Due-date arithmetic — 'six weeks from checkout, landing on
a day the partner is open' — happens in the service against a calendar, using the
partner's `time_zone`. The database stores the answer, not the arithmetic."* Neither
clean run has any time zone anywhere.

**Lending policy as data, in both runs.** Informed run 2:

```sql
    loan_period_days  INTEGER     NOT NULL DEFAULT 42
                        CHECK (loan_period_days > 0),
    max_open_loans    INTEGER     NOT NULL DEFAULT 100
                        CHECK (max_open_loans > 0),
...
COMMENT ON COLUMN partner_library.loan_period_days IS
    'Lending policy is per-partner data, not a constant in code.';
```

with: *"Otherwise it ends up as `if (openLoans > 100)` in three places, and the first
partner who negotiates different terms costs us a deploy."* Neither clean run has a
policy column.

**The idempotency key in both runs, named as such.** Informed run 1:

```sql
    -- The partner's own reference for this request. Their API call is
    -- retried like any other network call; this is the idempotency key.
    partner_reference   text        not null,
...
    -- A replayed request must land on the same row, not create a second one.
    constraint interlending_request_partner_ref_uq
        unique (partner_library_id, partner_reference),
```

with: *"This is exactly the `payment_reference` pattern from the payment webhook (INV-6)
— same failure mode, same fix, enforced in the schema rather than in a 'check if exists
first' in the handler, which loses the race under concurrency."* Clean run A has
`partner_reference` as a plain column with no constraint at all.

**`due_at` immutability, argued.** Informed run 1: *"`due_at` is set once and never
updated — same discipline as INV-2 on member loans. If partners get renewals later,
that's a new `interlending_renewal` table recording the event and the new derived due
date, not an `update ... set due_at`."* Neither clean run raises it.

**The cross-table INV-1 gap with a named remedy and a test strategy.** Informed run 1:
*"nothing here stops a copy being on an open member loan and an open interlending loan
simultaneously, because they're two tables and unique indexes don't span tables. The
fulfilment path must therefore run in one transaction that takes `select ... for update`
on the `copy` row and checks both open-loan tables before inserting. Put that in the
service, test it with Testcontainers, and expect any generated implementation of 'ship
copy to partner' to skip it — it will check availability with a plain read and look
completely correct in a demo."*

The clean runs found the same gap. Clean run A: *"nothing stops your existing `loans`
table from claiming the same copy unless the availability query unions both."* So the
*gap* was visible without the document; the *fix, the mechanism and the test* were not.

## The self-report

Informed run 2 closed with an unprompted note that is the cleanest possible statement of
what happened:

> "A note on context: I aligned this with the Bookline conventions from the locked
> system-design doc — singular table names, PostgreSQL 16 + Flyway, `timestamptz`-only
> instants (INV-9), constraints as first-class enforcement (INV-1's partial-index pattern,
> INV-6's idempotency-key pattern), and policy-as-data rather than hardcoded limits (the
> INV-3 failure mode). The cross-table INV-1 gap flagged at the end is the genuinely
> interesting design point for this feature."

That paragraph is why these runs were kept rather than deleted. It is the model
describing, accurately and without being asked, exactly which of its choices came from
the document rather than from itself.
