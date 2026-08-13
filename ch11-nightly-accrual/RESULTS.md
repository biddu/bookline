# ch11-nightly-accrual — results

**Contract outcome (10 Aug 2026): 1 of 3 met, in two independent runs. The one that
was met is the one whose cause is an absent control.**

## What was asked

Both runs used the identical verbatim prompt printed in §11.5:

```
Write a Spring scheduled job that runs nightly at 2am and charges
overdue fines: 30 cents per day for each overdue loan.
```

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Edits made: none. Full responses in `generated/run1_response.md`, `generated/run2_response.md`.

## The defect contract

Three omissions: (1) re-runs accrual on retry, breaking INV-5's once-per-day clause;
(2) computes dates in the server's zone rather than the branch's, breaking INV-9;
(3) never consults the branch closure calendar.

## Omission 1 — idempotency: RETIRED, emphatically, in both runs

Neither run charges per run. Both charge per day, by different mechanisms, and both
lead with it before showing any code.

- **Run 1** adds a `fineAccruedThrough` column to the entity and charges
  `ChronoUnit.DAYS.between(accruedFrom, today)`, with `if (daysToCharge <= 0) return;
  // already up to date — safe re-run`. Its opening sentence: "the parts that actually
  matter are idempotency (what happens if the job runs twice, or misses a night?) and
  money handling."
- **Run 2** goes further and makes the job stateless: it recomputes
  `fineAmount = daysOverdue × 0.30` from scratch every night, so there is no accrual
  state to corrupt. Opening sentence: "The one design decision worth getting right up
  front: **don't increment the fine by 30 cents each night**… Recomputing is idempotent:
  you can run it five times in a row and the numbers stay correct."
- Run 1 closes with a testing tip that is, in substance, Chapter 7's idempotency test:
  "call it a second time and assert it's *still* 150 — that's your idempotency test,
  and it's the one that matters most here."
- Both volunteer the multi-instance problem the prompt never mentioned, and both name
  ShedLock.

The word `idempot*` appears 4 times in run 1 and 3 times in run 2. The drafted
exhibit's first omission does not survive contact with the current model.

## Omission 2 — the branch's zone: transformed, not retired

Both runs know the zone problem exists and both warn about it in prose. Run 2:
"Set `zone` explicitly — otherwise it uses server-default timezone, which on a UTC
container means 2am UTC, probably not what the library intends."

Two things follow, and they pull in opposite directions.

- **Both invent a zone.** `zone = "America/New_York"`, in both runs, on a prompt that
  never said where the library is. This is the book's invention failure mode exactly:
  the prompt was silent, and the silence was filled with the training distribution's
  median library rather than with a question.
- **Both pin only half of it.** The `zone` attribute governs *when the trigger fires*.
  The date arithmetic runs through `LocalDate.now(clock)` against a `Clock` bean that
  is `Clock.systemDefaultZone()` in both runs. So the firing zone is configured
  explicitly and the arithmetic zone is inherited from the container, in two different
  files, with nothing keeping them consistent. On the nominal 02:00 path the two agree
  for most zones and the defect is invisible. It becomes visible on exactly the paths
  that matter: a retry that lands after local midnight but before UTC's, and run 2's
  own suggested admin "recalculate now" endpoint, which can be pressed at any hour.

The seam that Chapter 7 asked for is present — `Clock` is injected, 15 mentions in
run 1, 14 in run 2 — and it is wired to the wrong zone. A reviewer who reads
`zone = "America/New_York"` and stops there will conclude the zone question was handled.

## Omission 3 — the closure calendar: SURVIVES INTACT, both runs

Across both responses, in code and prose:

| Term | Run 1 | Run 2 |
|---|---|---|
| `closed` | 0 | 0 |
| `closure` | 0 | 0 |
| `holiday` | 0 | 0 |
| `Calendar` / `calendar` | 1 | 0 |

Run 1's single hit is not a closure calendar: "the day count comes from the calendar,
not from 'number of times the job happened to run'," meaning the Gregorian calendar.
Neither run contains any concept of a day on which the branch was shut. Both charge
30 cents for every calendar day between the due date and today, unconditionally.

## The cap — a fourth category: raised, not implemented

INV-5's cap clause is noticed by both runs and implemented by neither.

- Run 1 closes with: "One product question worth confirming with whoever owns the
  requirements: should fines cap at some maximum (e.g., the item's replacement cost)?
  Most library systems do, and it's a one-line guard in `accrueFine` if so."
- Run 2 folds it into a note: "If your policy is 'fine starts accruing *on* the due
  date' or you cap fines at the book's replacement cost, adjust the formula in one
  place."

This complicates §11.2's requirements row, which said the machine "cannot ask a
question." It can raise one. What it cannot do is *block* on the answer: it shipped
a complete, confident, uncapped implementation and put the question underneath it,
where a reader in a hurry will not act on it. The chapter's text has been corrected
to say that precisely.

## Reading

Sort the four by what causes them and the pattern is Chapter 9's, reproduced on a
different defect family. Idempotency has a name, a canon, and a thousand blog posts;
it retired. The zone problem has a name; the *awareness* retired while the wiring did
not. The closure calendar has no name outside Ardara's policy documents, and it is
untouched in both runs. The cap has a name in the library domain and none in the
training distribution, and it surfaced as a question rather than as code.

Exhibit 11B in the book is run 2, printed verbatim. §11.5 reads it against INV-5 and
INV-9 as it actually is, not as the draft predicted.
