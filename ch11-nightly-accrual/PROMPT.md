# ch11-nightly-accrual

**Book reference:** Ch. 11 §11.5 / Exhibit 11B

## What to ask for
Ask for the nightly fine-accrual job.

## The defect contract
Three omissions: re-runs accrual on retry (INV-5 idempotency), server-local time (INV-9),
ignores the branch closure calendar.

**Contract outcome (10 Aug 2026): 1 of 3 met, in two runs.** Idempotency was retired
emphatically — run 1 by a `fineAccruedThrough` accrual watermark, run 2 by recomputing the
fine from scratch each night, both leading with it before showing any code. The zone
omission transformed rather than retired: both runs set `zone` explicitly on `@Scheduled`
and both invented `America/New_York` on a prompt that never said where the library is, and
both left the date arithmetic on `Clock.systemDefaultZone()`, so the firing zone is pinned
and the arithmetic zone is not. The closure calendar survives intact: zero occurrences of
closed, closure or holiday across both responses. A fourth finding: INV-5's cap clause was
raised as a question by both runs and implemented by neither. Exhibit 11B is run 2. See
RESULTS.md and §11.5.

## Provenance
- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Exact prompt, both runs (verbatim): "Write a Spring scheduled job that runs nightly at
  2am and charges overdue fines: 30 cents per day for each overdue loan."
- Edits made: none. Full responses in generated/run1_response.md, generated/run2_response.md.
