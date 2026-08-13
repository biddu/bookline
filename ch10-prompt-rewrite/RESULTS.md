# Prompt Pair 10A and the inversion — REAL RESULTS (9 Aug 2026)

## The pair (§10.3)

Both prompts run against the Chapter 4 checkpoint, separate sessions, committed verbatim.

| | Vague prompt | C-I-E prompt |
|---|---|---|
| Output | 15 types, 692 lines, 6 packages | 16 types, 565 lines, circulation package |
| Imports | unconstrained | catalogue, membership, platform only |
| SQL / mail | inline SMTP present | none |
| Policy numbers | **6 invented facts** | **none invented** |
| INV-10 hold check | absent | on the title, correct |
| Self-report | none | 8 edge cases |

The vague run is `../ch05-god-class/generated/run1_response.md`; the C-I-E run is
`generated/cie_rewrite_response.md`.

### The finding: the same feature, invented in one run and refused in the other

Vague:

```java
private static final int PICKUP_WINDOW_DAYS = 7;    // Hold
private static final int STANDARD_LIMIT = 5;        // BorrowingPolicy
private static final int STUDENT_LIMIT  = 3;
private static final int PREMIUM_LIMIT  = 10;
```

C-I-E, self-report item 2, verbatim:

> "**Hold-shelf expiry.** A READY hold has no pickup deadline, because a shelf period is
> a policy number and `LoanPolicy` (as specified) does not carry one — inventing a
> literal here would violate your own constraint. The right fix is to add a hold-shelf
> period to `LoanPolicy` and run a scheduled sweep that expires READY holds and re-offers
> the copy via `onCopyAvailable`."

Same feature, same model, same afternoon. The Evaluation clause "every policy number
reaches the code through LoanPolicy, never as a literal" did not merely make the defect
cheap to find. It converted the invention into a reported question, with the fix located.

Self-report item 4 is the same move on a different axis: `placeHold` performs no
existence or standing checks because "no catalogue or membership lookup type was in the
injected set, so I did not invent one."

**This supersedes the drafted §10.3**, which predicted the C-I-E output would still carry
a hardcoded `MAX_OPEN_LOANS = 10`. It did not. The limit is enforced in one place through
the injected `CirculationService`.

## The inversion (§10.4)

Prompt: *"Assuming this class has a bug, what inputs would expose it? Give me concrete
input scenarios, most likely to be a real defect first."* Run against the C-I-E output's
`HoldService` (extract committed as generated/HoldService_cie_extract.java), which
already carried an 8-item author-side self-report.

**Result: 8 scenarios, of which 6 do not appear in the self-report at all.**

The two stances find different classes:

- **Cooperative (the Evaluation Cue)** finds *omissions the author knows about*: no
  hold-shelf expiry, no standing checks, no idempotency on `onCopyAvailable`.
- **Adversarial (the inversion)** finds *interaction defects*: cancelling the only READY
  hold strands the reserved copy because `cancelHold` returns void while
  `onCopyAvailable` returns the freed copy; cancel-then-replace may permanently bar a
  member depending on an `unsatisfied` versus `open` vocabulary split; `fulfilHold`
  checks no status and can be called twice.

The scenario worth the exercise, verbatim:

> "**Collect someone else's hold** — `cancelHold` takes `requestedBy` and verifies
> ownership; `fulfilHold` takes no such parameter, so `fulfilHold(h1)` from `m2` creates
> a loan for `m1`. The asymmetry inside one class is reason to check whether any request
> path passes a client-supplied `holdId` in."

That is Chapter 9's A01 (broken access control), found in code whose own author-side
self-report did not mention it. Same model, same code, minutes apart; the difference is
the stance the prompt put it in.

Both stances refused to invent. The inversion declined to build scenarios around the
missing pickup window or the loan limit "because I have no number to test against and
would not guess one".

**Book use:** §10.3 and §10.4 rewritten around these runs. Figure 10.1 built from the
pickup-window contrast.
