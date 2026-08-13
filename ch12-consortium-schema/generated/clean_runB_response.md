# ch12-consortium-schema — clean run B, verbatim

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, confirmed zero tool calls)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim, identical to clean run A): "Design the database schema for our consortium interlending feature. We need to track who the partner libraries are, what items they have requested from us, and which copies are currently out on loan to them. PostgreSQL, as a Flyway migration."
- Edits made: none.
- **Note: this run produced the idempotency key that run A omitted, and argued explicitly against row deletion. Like run A, it has no partner time zone anywhere.**

---

Here's how I'd approach it, followed by the migration itself.

**Design notes before the SQL:**

I've modelled this as three tables with a clear separation between the *request* (the workflow/paperwork) and the *loan* (a physical copy actually leaving the building):

1. **`partner_library`** — the consortium members. I gave each a short unique `code` (e.g. `"UOE-MAIN"`) because that's what shows up in ILL messages and reports, plus an `active` flag so we can offboard a partner without deleting history.

2. **`ill_request`** — an incoming request from a partner for something in our catalogue. Status is a `TEXT` column with a `CHECK` constraint rather than a Postgres `ENUM` — same integrity guarantee, but adding a status later is a trivial migration instead of an `ALTER TYPE` dance. I also store the partner's own reference number and made it unique *per partner*, which deduplicates retransmitted requests (a real problem with email/ISO 18626 traffic).

3. **`ill_loan`** — created only when a copy is actually sent. It's a separate table because a request can be rejected or cancelled without a loan ever existing, and (depending on policy) a request could be fulfilled by a second copy if the first comes back damaged. Two partial unique indexes enforce the physical invariants: a given copy can only be on **one active loan** at a time, and a request can only have **one active loan** at a time.

I'm assuming you already have `item` (bibliographic record) and `item_copy` (physical copy/barcode) tables — adjust those FK targets to whatever they're actually called. `item_id` on the request is nullable because partners sometimes request things we can't match to our catalogue; we still want to record the request and reject it gracefully, hence the free-text `requested_title`/`requested_author` fallback and the constraint that at least one identifying field is present.

Renumber the migration version to whatever's next in your sequence.

```sql
-- V12__consortium_interlending.sql
-- Consortium interlending: partner libraries, their requests for our items,
-- and copies currently out on loan to them.

-- ---------------------------------------------------------------------------
-- 1. Partner libraries in the consortium
-- ---------------------------------------------------------------------------
CREATE TABLE partner_library (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            TEXT        NOT NULL,           -- short identifier used in ILL traffic, e.g. 'UOE-MAIN'
    name            TEXT        NOT NULL,
    contact_name    TEXT,
    contact_email   TEXT,
    contact_phone   TEXT,
    address         TEXT,
    notes           TEXT,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_partner_library_code UNIQUE (code)
);

-- ---------------------------------------------------------------------------
-- 2. Incoming interlending requests from partners for our items
-- ---------------------------------------------------------------------------
CREATE TABLE ill_request (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    partner_library_id  BIGINT      NOT NULL REFERENCES partner_library (id),

    -- What they want. item_id is nullable: partners sometimes request things
    -- we can't match to our catalogue; we still record and reject gracefully.
    item_id             BIGINT      REFERENCES item (id),
    requested_title     TEXT,
    requested_author    TEXT,

    -- The partner's own reference for this request (from their ILL system).
    partner_reference   TEXT,

    status              TEXT        NOT NULL DEFAULT 'pending',
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    needed_by           DATE,                        -- "not needed after" date, if given
    decided_at          TIMESTAMPTZ,                 -- when we approved/rejected
    closed_at           TIMESTAMPTZ,                 -- terminal state reached
    rejection_reason    TEXT,
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_ill_request_status CHECK (status IN (
        'pending',      -- received, awaiting decision
        'approved',     -- we will supply; awaiting shipment
        'rejected',     -- we won't supply
        'cancelled',    -- partner withdrew the request
        'on_loan',      -- a copy is out with the partner
        'completed'     -- copy returned (or request otherwise finished)
    )),

    -- Must identify the material somehow.
    CONSTRAINT ck_ill_request_identifies_item CHECK (
        item_id IS NOT NULL OR requested_title IS NOT NULL
    ),

    -- A rejection should say why.
    CONSTRAINT ck_ill_request_rejection_reason CHECK (
        status <> 'rejected' OR rejection_reason IS NOT NULL
    )
);

-- Deduplicate retransmitted requests: a partner's reference is unique to them.
CREATE UNIQUE INDEX uq_ill_request_partner_ref
    ON ill_request (partner_library_id, partner_reference)
    WHERE partner_reference IS NOT NULL;

CREATE INDEX ix_ill_request_partner   ON ill_request (partner_library_id);
CREATE INDEX ix_ill_request_item      ON ill_request (item_id);
CREATE INDEX ix_ill_request_status    ON ill_request (status);

-- ---------------------------------------------------------------------------
-- 3. Physical copies currently (or historically) on loan to partners
-- ---------------------------------------------------------------------------
CREATE TABLE ill_loan (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ill_request_id  BIGINT      NOT NULL REFERENCES ill_request (id),
    item_copy_id    BIGINT      NOT NULL REFERENCES item_copy (id),

    status          TEXT        NOT NULL DEFAULT 'shipped',
    shipped_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    due_date        DATE        NOT NULL,
    renewal_count   INT         NOT NULL DEFAULT 0 CHECK (renewal_count >= 0),
    returned_at     TIMESTAMPTZ,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_ill_loan_status CHECK (status IN (
        'shipped',      -- in transit to partner
        'received',     -- partner confirmed receipt
        'recalled',     -- we've asked for it back early
        'returned',     -- back on our shelf; loan closed
        'lost'          -- written off; loan closed
    )),

    -- returned_at set iff the loan is closed as returned.
    CONSTRAINT ck_ill_loan_returned_at CHECK (
        (status = 'returned') = (returned_at IS NOT NULL)
    ),

    CONSTRAINT ck_ill_loan_dates CHECK (
        returned_at IS NULL OR returned_at >= shipped_at
    )
);

-- A physical copy can only be on one open loan at a time.
CREATE UNIQUE INDEX uq_ill_loan_active_copy
    ON ill_loan (item_copy_id)
    WHERE status IN ('shipped', 'received', 'recalled');

-- A request can only have one open loan at a time.
CREATE UNIQUE INDEX uq_ill_loan_active_request
    ON ill_loan (ill_request_id)
    WHERE status IN ('shipped', 'received', 'recalled');

CREATE INDEX ix_ill_loan_request  ON ill_loan (ill_request_id);
CREATE INDEX ix_ill_loan_copy     ON ill_loan (item_copy_id);
CREATE INDEX ix_ill_loan_status   ON ill_loan (status);
CREATE INDEX ix_ill_loan_due_date ON ill_loan (due_date) WHERE status IN ('shipped', 'received', 'recalled');

-- ---------------------------------------------------------------------------
-- Keep updated_at fresh
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_partner_library_updated_at
    BEFORE UPDATE ON partner_library
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_ill_request_updated_at
    BEFORE UPDATE ON ill_request
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_ill_loan_updated_at
    BEFORE UPDATE ON ill_loan
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
```

**A few things worth calling out:**

- **"Currently out on loan" is a query, not a table.** Anything in `ill_loan` with status in `('shipped','received','recalled')` is out. The partial index on `due_date` makes the "what's overdue" query cheap. If you want it as a view for convenience:

  ```sql
  CREATE VIEW v_active_ill_loans AS
  SELECT l.*, r.partner_library_id, p.code AS partner_code
  FROM ill_loan l
  JOIN ill_request r ON r.id = l.ill_request_id
  JOIN partner_library p ON p.id = r.partner_library_id
  WHERE l.status IN ('shipped', 'received', 'recalled');
  ```

- **No `ON DELETE CASCADE` anywhere.** These are records with real-world accountability attached — you never want deleting a partner to silently erase the audit trail of what they borrowed. Deactivate partners with the `active` flag instead.

- **The `(status = 'returned') = (returned_at IS NOT NULL)` trick** is a compact way to enforce the iff relationship in one constraint — it fails both if a returned loan lacks a timestamp and if an open loan has one.

- **Deliberately out of scope:** the *outbound* direction (things we borrow from partners) — same shape but a separate pair of tables when you need it, don't overload these; renewal history as rows (I just kept a counter — add an `ill_loan_renewal` table if you need per-renewal audit); and any integration with your existing circulation/hold system, which probably needs a status on `item_copy` itself so front-desk staff can see "out via ILL" — that's a one-line `ALTER` in a later migration once we see how your copy-status enum works.

One question back to you: do requests ever arrive for a *specific copy* (e.g. a particular edition/barcode)? If so we'd add a nullable `item_copy_id` to `ill_request` too. As written, the copy is chosen at fulfilment time, which matches most ILL workflows.
