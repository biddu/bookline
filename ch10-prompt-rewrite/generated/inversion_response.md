Scoping note first: I only read `HoldService.java`. `Hold`, `HoldRepository`, and `CirculationService` are not visible to me, so several of these scenarios are only defects depending on how those collaborators behave. I've said which assumption each one rests on so you can kill the ones your state machine already defends.

Ranked most-likely-real first.

---

**1. Cancel the only hold in the queue while it is READY — the freed copy is stranded**

```
placeHold(m1, t1)            -> h1
onCopyAvailable(t1, c1)      -> h1 READY, reserves c1
cancelHold(h1, m1)
```

After the cancel, the queue for `t1` is empty, so `offerCopy` returns empty and `freedCopy = c1` is silently dropped. `cancelHold` returns `void`, so the caller has no way to learn that `c1` is now unreserved and needs reshelving. Compare `onCopyAvailable`, whose javadoc explicitly makes "empty" mean *the copy may go back on the open shelf* — the same signal exists on one path and is discarded on the other. A copy goes quietly missing from circulation until someone notices it on the hold shelf.

Depends on nothing outside this file. This is the one I'd write a test for first: cancel a READY hold with no one behind it in the queue, and assert that *something* — a return value, an event, a call into `circulation` — reports the copy as available.

---

**2. Cancel, then place again on the same title**

```
placeHold(m1, t1)            -> h1
cancelHold(h1, m1)
placeHold(m1, t1)            -> DuplicateHoldException?
```

Everything turns on whether a CANCELLED hold satisfies `existsUnsatisfiedByMemberAndTitle`. On the plain reading of the word, a cancelled hold was never *satisfied*, so this predicate likely returns true and the member is permanently barred from ever re-holding that title.

The tell is the vocabulary split inside this one class: the duplicate check asks for **unsatisfied**, `offerCopy` asks for **open**. If those name the same set, one of them is misnamed; if they name different sets, this input is what exposes the difference.

**2b, the mirror case** (same root cause, opposite direction) — if READY *does* count as satisfied:

```
placeHold(m1, t1)            -> h1
onCopyAvailable(t1, c1)      -> h1 READY
placeHold(m1, t1)            -> succeeds
```

Now `m1` occupies two positions in the queue for `t1` and can be offered a second copy while already holding `c1` ready. Exactly one of 2 or 2b is live, and which one is decided entirely by that repository query.

---

**3. Fulfil a hold that is no longer READY**

`fulfilHold` checks only that `reservedCopyId` is present. It never inspects the hold's status.

```
placeHold(m1, t1)            -> h1
placeHold(m2, t1)            -> h2
onCopyAvailable(t1, c1)      -> h1 READY with c1
cancelHold(h1, m1)           -> c1 offered onward, h2 READY with c1
fulfilHold(h1)
```

If `Hold.cancel()` leaves `reservedCopyId` populated, `h1` still resolves `c1` and `circulation.checkout(m1, c1)` runs. `m1` walks off with the copy `h2` was just invited to collect, and `h2` sits READY pointing at a copy that is now on loan.

Same signature, simpler input: **call `fulfilHold(h1)` twice**. The second call re-checks-out `c1` unless `markFulfilled()` clears the reservation or rejects the transition.

Depends on: whether `Hold.cancel()` / `markFulfilled()` clear `reservedCopyId` and whether they throw on an invalid transition, and whether `CirculationService.checkout` refuses a copy already on loan. If the state machine is enforced inside `Hold`, this is defended one layer down — but the service is leaning on that silently.

---

**4. Collect someone else's hold**

`cancelHold` takes `requestedBy` and verifies ownership. `fulfilHold` takes no such parameter.

```
fulfilHold(h1)   // called by/for m2, where h1 belongs to m1
```

The loan is created for `m1`'s member ID, against `m1`'s tier limits, at `m2`'s request. Whether that's a defect depends on whether this method is only ever reachable from a trusted staff-side caller — but the asymmetry with `cancelHold` inside the same class is reason enough to check. Concrete probe: is there any request-handling path that passes a client-supplied `holdId` into `fulfilHold`?

---

**5. Cancel after fulfil — re-offers a copy that is on loan**

```
h1 fulfilled with c1         // c1 now on loan to m1
h2 queued behind it
cancelHold(h1, m1)
```

If `markFulfilled()` leaves `reservedCopyId` set and `cancel()` doesn't reject FULFILLED -> CANCELLED, then `freedCopy = c1` and `offerCopy` publishes `HoldReadyForPickup(h2, c1)` for a copy sitting in `m1`'s bag. `m2` gets a notification and travels to the branch for a book that isn't there. Same collaborator dependency as 3.

---

**6. Two copies of the same title returned concurrently**

One queued hold `h1` for `t1`, two threads:

```
T1: onCopyAvailable(t1, c1)
T2: onCopyAvailable(t1, c2)
```

Both call `findOldestOpenByTitle` before either saves, both get `h1`, both `markReady`. Last write wins: one of `c1`/`c2` ends up reserved by nobody and never reshelved, and two `HoldReadyForPickup` events fire for the same hold with different copies. Nothing in this class locks, versions, or re-reads after the query. The same shape applies to `cancelHold` racing `onCopyAvailable` over the same freed copy — both can route it to different holds.

Depends on isolation/optimistic locking that isn't visible here. Worth confirming `Hold` carries a version column or that the repository query takes a lock.

---

**7. Partial failure inside `fulfilHold`**

```
fulfilHold(h1) where circulation.checkout(...) succeeds
               but holds.save(...) or events.publish(...) throws
```

The loan exists; the hold is still READY with `c1` reserved; no `HoldFulfilled` is ever published, so every downstream consumer believes the copy is still waiting on the shelf. A retry runs `checkout(m1, c1)` a second time. The javadoc carefully covers the case where *checkout* throws, and says nothing about anything throwing after it. No transaction boundary is declared in this class. The same input class applies to `cancelHold`, where `publish` sits between the save and `offerCopy`.

---

**8. Timestamp skew in the published events (lowest, data rather than behaviour)**

`placeHold` publishes `hold.placedAt()` — the value stored on the aggregate. `cancelHold`, `fulfilHold`, and `offerCopy` all publish `clock.instant()`, sampled at publish time rather than at the transition. Input: any cancel or fulfil where the transition and the publish straddle a clock tick — trivially forced with a stepping `Clock` in a test, and reachable in production via a GC pause between `hold.cancel()` and the `publish` call. The event timestamp and the persisted hold timestamp disagree, which bites anything reconstructing queue order or wait times from the event stream. Cheap to settle: does `Hold.cancel()` stamp its own `cancelledAt`?

---

**What I'd read next to promote these from suspicion to confirmation**

- `Hold` — does it guard transitions, and does it clear `reservedCopyId` on cancel and on fulfil? This decides 3, 5, and part of 1.
- `HoldRepository` — the exact predicates behind `existsUnsatisfiedByMemberAndTitle` and `findOldestOpenByTitle`, and whether either locks. Decides 2 and 6.
- `CirculationService.checkout` — does it reject a copy already on loan? That's the backstop for 3 and 7.

**Two things I deliberately did not turn into scenarios**

There is no hold-expiry path anywhere in this class — nothing releases a READY hold's reserved copy if the member never collects it. That may live elsewhere, or it may be genuinely missing, but I have no basis in this file for how long a copy should sit before it's released, so I'm not guessing a duration or writing a scenario around one.

Likewise, the concurrent-loan limit is delegated to `CirculationService` by the javadoc, so I have no number to test against and haven't invented one. `fulfilHold` failing on a member at their limit is the *documented* behaviour, not a bug — the interesting variant of it is scenario 7, where the failure lands after the checkout has already succeeded.

`cancelHold` folding "no such hold" and "not your hold" into the same `HoldNotFoundException` looks deliberate to me (avoids leaking hold existence), so I've left it off the list.

---

Eight numbered scenarios (scenario 2 has two mutually exclusive directions, 2 and 2b). None of them depends on a hardcoded or invented policy value — I avoided the two places that would have required one, the missing pickup-expiry window and the concurrent-loan limit, and flagged both as open questions instead.
