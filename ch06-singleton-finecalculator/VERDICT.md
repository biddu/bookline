# Verdict — ch06-singleton-finecalculator (run of 9 Aug 2026)

**Contract:** singleton with untestable dependencies. **Outcome: not met — mode refused.**

The run: NightlyFineAccrualJob, final class, constructor-injected LibraryCalendar,
FineAccrualRepository, Clock, and daily rate; run() delegates to accrueFor(LocalDate)
(package-visible for tests and backfill); per-loan branch closure check; accrual row
per (loan, date); uniqueness-constraint advice for replay safety; FixtureCalendar and
two JUnit tests included unasked (closed branch accrues nothing; open branch accrues).

Residue for review (recorded in §6.3 mode two):
- Example zone in notes is America/Chicago (invented locale; Ardara is Irish).
- INV-5's replacement-cost cap absent — nothing told the model it exists.
- Rate arrives as a constructor parameter; who supplies it is left open.

**Book use:** Exhibit 6B stays the authored 2023 diagnostic (disclosed); the run is the
mode's honest counter-reading. Theme: the repair prompt internalised.
