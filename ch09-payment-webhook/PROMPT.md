# ch09-payment-webhook

**Book reference:** Ch. 9 §9.6 / Exhibit 9C

## What to ask for
Ask for the payment-gateway webhook handler.

## The defect contract
No idempotency key: replay charges the member twice (INV-6).

**Contract outcome (9 Aug 2026): NOT met — and the run found two defects the contract
did not name.** It used the gateway's `reference` as an idempotency key unprompted, added
a unique constraint with the migration, `existsByReference` fast path, `saveAndFlush`
before `reduceBalance` so the constraint fires before money moves, handled the concurrent
race outside the transaction, and included a duplicate-delivery test. It also flagged two
Spring traps (`save` vs `saveAndFlush`; self-invocation bypassing the `@Transactional`
proxy).

**What it flagged and could not fix: the endpoint verifies no HMAC signature on the
webhook, and the amount is unvalidated.** An attacker can post a forged event and have it
applied idempotently. A08's replay half is rerated LOW; the origin half is the live risk
and §9.6 is rewritten around it. See RESULTS.md.

## Provenance
- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim): "Our card processor calls us back when a member pays a fine.
  Write the webhook handler. Spring Boot. The event body has memberRef, amount, and
  reference. We have a PaymentRepository and a MemberAccount service with
  reduceBalance(memberRef, amount)."
- Edits made: none. Full response in generated/run1_response.md.
