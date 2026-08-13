Here's the nightly fine-accrual job. The key design decision: the job receives everything it needs through its constructor — the calendar, the repository, the clock, and the fine rate. No singletons, no static lookups, no service locator. That's what makes the branch-closure rule testable: in a test, you hand the job a fixture calendar that says "closed on Sundays" and a fixed clock pinned to a Sunday, and assert that nothing accrues.

```java
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Nightly job that accrues one day's fine for every overdue loan,
 * skipping loans whose branch is closed on the accrual date.
 *
 * Scheduling (cron, Quartz, @Scheduled, etc.) lives outside this class;
 * the scheduler just calls {@link #run()}. Keeping the schedule out of
 * the class means tests can invoke the accrual logic directly for any
 * date the clock reports.
 */
public final class NightlyFineAccrualJob {

    private final LibraryCalendar calendar;
    private final FineAccrualRepository repository;
    private final Clock clock;
    private final BigDecimal dailyFineAmount;

    public NightlyFineAccrualJob(LibraryCalendar calendar,
                                 FineAccrualRepository repository,
                                 Clock clock,
                                 BigDecimal dailyFineAmount) {
        this.calendar = calendar;
        this.repository = repository;
        this.clock = clock;
        this.dailyFineAmount = dailyFineAmount;
    }

    /** Entry point invoked by the scheduler once per night. */
    public void run() {
        accrueFor(LocalDate.now(clock));
    }

    /**
     * Accrues fines for the given date. Package-visible so tests can
     * exercise specific dates without manipulating the clock, and so a
     * backfill task can replay missed nights.
     */
    void accrueFor(LocalDate date) {
        List<Loan> overdueLoans = repository.overdueLoansOn(date);
        for (Loan loan : overdueLoans) {
            if (!calendar.isOpen(loan.branch(), date)) {
                continue; // no accrual on days the branch is closed
            }
            repository.save(new FineAccrual(loan.id(), date, dailyFineAmount));
        }
    }
}
```

And the `FineAccrual` record, if you don't already have one shaped like this:

```java
import java.math.BigDecimal;
import java.time.LocalDate;

public record FineAccrual(LoanId loanId, LocalDate accrualDate, BigDecimal amount) {}
```

A few notes on the choices:

**Why `Clock` instead of `LocalDate.now()`.** `LocalDate.now()` is a hidden static dependency on the wall clock — it makes "what happens on a closed day" untestable without actually waiting for a holiday. Injecting `java.time.Clock` costs one constructor parameter and buys you `Clock.fixed(...)` in tests. In production you pass `Clock.systemDefaultZone()` (or better, the zone the library operates in: `Clock.system(ZoneId.of("America/Chicago"))` — a nightly job that runs just after midnight UTC can otherwise accrue for the wrong calendar day).

**Why the closure check is per-loan, not per-run.** Each loan belongs to a branch, and branches have different closure schedules. Checking `isOpen(loan.branch(), date)` inside the loop means a system-wide holiday skips everyone, while a single-branch closure (flood damage, staff day) skips only that branch's loans.

**Why one `FineAccrual` row per day rather than a mutating balance.** Saving an accrual record per (loan, date) makes the job naturally idempotent to reason about and auditable: you can answer "why is this fine $4.50?" by listing the accrual rows. If the job might be re-run after a partial failure, add a uniqueness constraint on `(loan_id, accrual_date)` in the schema so replays can't double-charge — that guard belongs in the database, not in job logic.

**Idempotency and missed nights.** `accrueFor(LocalDate)` being callable with an explicit date means a missed night (deploy window, outage) can be backfilled by replaying the missing dates in order, and the unique constraint above makes replays safe.

Here's the test that pins down the branch-closure rule, using a fixture calendar — no mocking framework required:

```java
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightlyFineAccrualJobTest {

    private static final LocalDate SUNDAY = LocalDate.of(2026, 8, 9);
    private static final BigDecimal RATE = new BigDecimal("0.25");

    /** Fixture calendar: closed dates are declared per branch, everything else is open. */
    static final class FixtureCalendar implements LibraryCalendar {
        private final Map<BranchId, Set<LocalDate>> closures;

        FixtureCalendar(Map<BranchId, Set<LocalDate>> closures) {
            this.closures = closures;
        }

        @Override
        public boolean isOpen(BranchId branch, LocalDate date) {
            return !closures.getOrDefault(branch, Set.of()).contains(date);
        }
    }

    /** In-memory repository standing in for the real one. */
    static final class InMemoryFineAccrualRepository implements FineAccrualRepository {
        final List<Loan> overdue = new ArrayList<>();
        final List<FineAccrual> saved = new ArrayList<>();

        @Override
        public List<Loan> overdueLoansOn(LocalDate date) {
            return List.copyOf(overdue);
        }

        @Override
        public void save(FineAccrual accrual) {
            saved.add(accrual);
        }
    }

    @Test
    void accruesNothingForBranchClosedOnAccrualDate() {
        var mainBranch = new BranchId("main");
        var calendar = new FixtureCalendar(Map.of(mainBranch, Set.of(SUNDAY)));
        var repository = new InMemoryFineAccrualRepository();
        repository.overdue.add(loanAt(mainBranch, "loan-1"));

        var clock = Clock.fixed(SUNDAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var job = new NightlyFineAccrualJob(calendar, repository, clock, RATE);

        job.run();

        assertTrue(repository.saved.isEmpty(), "closed branch must not accrue");
    }

    @Test
    void accruesOnlyForOpenBranchesWhenClosuresDiffer() {
        var openBranch = new BranchId("eastside");
        var closedBranch = new BranchId("main");
        var calendar = new FixtureCalendar(Map.of(closedBranch, Set.of(SUNDAY)));
        var repository = new InMemoryFineAccrualRepository();
        repository.overdue.add(loanAt(closedBranch, "loan-1"));
        repository.overdue.add(loanAt(openBranch, "loan-2"));

        var clock = Clock.fixed(SUNDAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        var job = new NightlyFineAccrualJob(calendar, repository, clock, RATE);

        job.run();

        assertEquals(1, repository.saved.size());
        assertEquals(new LoanId("loan-2"), repository.saved.get(0).loanId());
        assertEquals(RATE, repository.saved.get(0).amount());
        assertEquals(SUNDAY, repository.saved.get(0).accrualDate());
    }

    private static Loan loanAt(BranchId branch, String id) {
        return new Loan(new LoanId(id), branch /*, ...other fields as your Loan defines */);
    }
}
```

(The tests assume `LibraryCalendar` and `FineAccrualRepository` are interfaces, and that `Loan` exposes `id()` and `branch()`. If `Loan` is a class with different accessors, adjust `loan.id()` / `loan.branch()` accordingly — nothing else in the job depends on its shape.)

Wiring it up in production is one line wherever you compose the application:

```java
var job = new NightlyFineAccrualJob(
        libraryCalendar,
        fineAccrualRepository,
        Clock.system(ZoneId.of("America/Chicago")),
        new BigDecimal("0.25"));
scheduler.scheduleNightly(job::run);
```

If you're on Spring, the constructor is already injection-ready — annotate it as a `@Component` and declare a `Clock` bean; don't reach for a static `FineCalculator.getInstance()` pattern, because the moment the closure rule lives behind a static, the Sunday test above becomes impossible to write without clock-mocking agents or a real calendar database.
