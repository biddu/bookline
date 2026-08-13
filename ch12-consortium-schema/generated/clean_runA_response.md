# ch12-consortium-schema — clean run A, verbatim (this is Exhibit 12B in the book)

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, confirmed zero tool calls)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim): "Design the database schema for our consortium interlending feature. We need to track who the partner libraries are, what items they have requested from us, and which copies are currently out on loan to them. PostgreSQL, as a Flyway migration."
- Edits made: none.

---

Here's how I'd cut this. Three tables, one clear rule baked into the constraints: **requests are for titles, loans are for physical copies.** A partner asks "can we borrow *The Pragmatic Programmer*", we approve it, and only at fulfilment time do we commit a specific barcode/copy to them. Keeping those two concepts separate saves you a lot of pain later (rejected requests never touch inventory, and you can re-fulfil a request with a different copy if the first one turns out to be damaged).

I'm assuming you already have `items` (bibliographic records) and `copies` (physical holdings) tables with `bigint` PKs — adjust the FK targets if your names differ.

```sql
-- V12__consortium_interlending.sql
-- Consortium interlending: partner libraries, their borrowing requests,
-- and physical copies currently on loan to them.

-- ---------------------------------------------------------------------------
-- 1. Partner libraries
-- ---------------------------------------------------------------------------
CREATE TABLE partner_library (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            TEXT        NOT NULL,               -- short consortium code, e.g. 'NORTH-CENTRAL'
    name            TEXT        NOT NULL,
    contact_name    TEXT,
    contact_email   TEXT        NOT NULL,
    contact_phone   TEXT,
    address         TEXT,
    membership_status TEXT      NOT NULL DEFAULT 'ACTIVE',
    joined_on       DATE        NOT NULL DEFAULT CURRENT_DATE,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_partner_library_code UNIQUE (code),
    CONSTRAINT ck_partner_library_status
        CHECK (membership_status IN ('ACTIVE', 'SUSPENDED', 'WITHDRAWN'))
);

-- ---------------------------------------------------------------------------
-- 2. Interlending requests (title-level: "we'd like to borrow this item")
-- ---------------------------------------------------------------------------
CREATE TABLE interlending_request (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    partner_library_id  BIGINT      NOT NULL REFERENCES partner_library (id),
    item_id             BIGINT      NOT NULL REFERENCES items (id),
    status              TEXT        NOT NULL DEFAULT 'PENDING',
    partner_reference   TEXT,                            -- their internal request/ticket number
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    needed_by           DATE,                            -- optional "no use to us after this date"
    decided_at          TIMESTAMPTZ,                     -- when we approved/rejected
    decided_by          TEXT,                            -- staff username; swap for FK to users if you have one
    decision_note       TEXT,                            -- esp. rejection reason
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_interlending_request_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'FULFILLED', 'CLOSED')),
    -- a decision implies a decision timestamp
    CONSTRAINT ck_interlending_request_decision
        CHECK (status IN ('PENDING', 'CANCELLED') OR decided_at IS NOT NULL)
);

CREATE INDEX ix_ilr_partner   ON interlending_request (partner_library_id);
CREATE INDEX ix_ilr_item      ON interlending_request (item_id);
CREATE INDEX ix_ilr_status    ON interlending_request (status)
    WHERE status IN ('PENDING', 'APPROVED');             -- the worklist queries

-- ---------------------------------------------------------------------------
-- 3. Interlending loans (copy-level: "this barcode is at that library")
-- ---------------------------------------------------------------------------
CREATE TABLE interlending_loan (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id          BIGINT      NOT NULL REFERENCES interlending_request (id),
    copy_id             BIGINT      NOT NULL REFERENCES copies (id),
    dispatched_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    due_date            DATE        NOT NULL,
    returned_at         TIMESTAMPTZ,                     -- NULL = still out
    return_condition    TEXT,                            -- 'GOOD', 'DAMAGED', 'LOST' etc.
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_interlending_loan_return_after_dispatch
        CHECK (returned_at IS NULL OR returned_at >= dispatched_at)
);

-- A physical copy can only be out on ONE open interlending loan at a time.
CREATE UNIQUE INDEX uq_ill_copy_open_loan
    ON interlending_loan (copy_id)
    WHERE returned_at IS NULL;

-- A request can only have ONE open loan at a time (but may have several
-- historical ones, e.g. first copy came back damaged and was replaced).
CREATE UNIQUE INDEX uq_ill_request_open_loan
    ON interlending_loan (request_id)
    WHERE returned_at IS NULL;

CREATE INDEX ix_ill_request ON interlending_loan (request_id);
CREATE INDEX ix_ill_copy    ON interlending_loan (copy_id);
-- "what's overdue" query
CREATE INDEX ix_ill_open_due ON interlending_loan (due_date)
    WHERE returned_at IS NULL;
```

## Design notes / decisions worth flagging

**Why three tables and not two.** The tempting shortcut is to hang `copy_id` and `due_date` straight off the request. Don't — a request has a lifecycle (pending → approved/rejected) that's independent of physical circulation, and the request-to-loan relationship is genuinely 1-to-many over time (copy comes back damaged, you send a replacement under the same request). The two partial unique indexes give you the invariants you actually care about: at most one *open* loan per request, and a copy can't be in two places at once.

**No status column on the loan.** "Is this copy out?" is exactly `returned_at IS NULL`. A separate status column would just be a second source of truth that drifts. Overdue is likewise derived: `returned_at IS NULL AND due_date < CURRENT_DATE`. If you need a materialised "overdue" flag for notifications later, add it then.

**TEXT + CHECK rather than Postgres `ENUM` types.** Adding a value to a CHECK constraint is a trivial migration; reordering/removing native enum values is annoying. If you prefer real enums for JPA mapping reasons, it's a mechanical swap.

**`decided_by` is TEXT** because I don't know your users/staff table — if you have one, make it a proper FK in this same migration.

**The partial index on status** covers the two hot worklists ("requests awaiting decision" and "approved, awaiting dispatch") without indexing the ever-growing pile of closed requests.

**Deliberately out of scope**, so you can push back if you need them now: renewals (I'd model as a `interlending_loan_renewal` child table rather than mutating `due_date`, so you keep history), fees/invoicing for lost items, and the reciprocal direction (things *we* borrow *from* partners — that's a separate pair of tables, don't try to make these bidirectional with a "direction" flag, it muddies every query).

One thing to wire up app-side or via trigger: when a loan is created, flip the request to `FULFILLED`; when the last loan is returned, `CLOSED`. I'd keep that in the service layer rather than a DB trigger so the state machine lives in one place, but shout if you'd rather have the trigger and I'll add it to the migration.

Also worth doing in the same PR: whatever your regular circulation checkout logic is, it needs to consider `uq_ill_copy_open_loan` — a copy on interlending loan must not be checkoutable to a local patron. The unique index protects you within interlending, but nothing stops your existing `loans` table from claiming the same copy unless the availability query unions both.
