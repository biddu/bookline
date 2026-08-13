# ch06-observer-coupled

**Book reference:** Ch. 6 §6.3 mode three / Exhibit 6C (diagnostic) + real run in generated/

## What to ask for
Ask to notify the member when a returned copy satisfies a hold.

## The defect contract — what the generated output must exhibit
Originally: a "listener" that constructs its notifiers and messages every holder of the
title on every channel (INV-4 broken).

**Contract outcome (9 Aug 2026): NOT met.** Injected HoldNotifier seam; exactly one
member notified (oldest ACTIVE hold, FIFO by placedAt); hold transitioned to
READY_FOR_PICKUP and persisted before notification. Residue read in §6.3: a
CompositeHoldNotifier fans out to every configured channel (email and SMS both; no
preference concept anywhere); "held for 7 days" invented and stated in two message
string literals; INV-4's priority classes absent (never stated). Exhibit 6C is an
authored diagnostic (see DIAGNOSTIC_RECORD.md); the real run is committed in
generated/observer_response.md.

## Provenance
- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim): "When a copy is returned and a member has a hold on that
  title, notify the member that it is ready for collection. Add this to our library
  system. Java 21. We have Copy, Hold, HoldRepository (findByTitle(isbn)), and members
  have email and SMS contact details."
- Edits made: none.
