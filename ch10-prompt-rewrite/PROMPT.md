# ch10-prompt-rewrite

**Book reference:** Ch. 10 §10.3 / Exhibit 10A — MUST BE ACTUALLY RUN

## What to ask for
The Chapter 5 one-liner re-run, then the C-I-E rewrite (both drafted verbatim in the chapter).

## The defect contract — what the generated output must exhibit
Both outputs verbatim plus the honest reckoning: what the better prompt fixed, and the residue it did not (e.g. a hardcoded limit surviving).

If the tool does not produce the defect naturally, re-prompt, use an older model, or seed
the fault per the course's seeded-fault convention, and say so in the provenance below.
The book's rule: verbatim evidence, honest provenance, no staging without disclosure.

## Provenance — RUN 1 (9 August 2026)
- Tool: Claude (Cowork drafting session; clean context: fresh agent given only the C-I-E request below)
- Model: claude-fable-5
- Date: 9 August 2026
- Exact prompt (verbatim):

```
Context. Bookline is the lending platform for Ardara County Library
Service. Java 21. The code is organised by feature: catalogue,
circulation, membership, billing, notification, platform. Dependency
rule: circulation may import catalogue and membership; billing may
import circulation; notification receives events and imports only
platform. These types exist and are injected; use them, do not
implement them: LoanRepository, HoldRepository, CirculationService
(checkout and return), LibraryCalendar, and LoanPolicy, which carries
the loan period, renewal limit and concurrent-loan limit by membership
tier and format, resolved through membership. One rule you must not
weaken: a member may hold at most N open loans, where N is a property
of their membership tier.

Intent. Extend circulation with: placing a hold on a title, cancelling
a hold, satisfying a hold when a copy becomes available, and renewal.
Fines, notices and reports are owned by other modules and are out of
scope: when a loan or hold changes in a way they care about, publish a
domain event and stop. Produce complete Java classes in the
circulation package, plus the event types.

Evaluation. I will accept the output only if: no import outside
catalogue, membership and platform; no SQL and no mail anywhere;
every policy number (limits, periods, rates) reaches the code through
LoanPolicy, never as a literal; renewal is refused while any
unsatisfied hold exists on the title. After generating, identify any
edge cases your implementation does not handle.
```

- Edits made: None; committed verbatim. Contract met — see VERDICT.md.
