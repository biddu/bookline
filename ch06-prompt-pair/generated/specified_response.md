Here is the implementation. Java, constructor injection throughout, and only the per-day amount varies by tier.

**`DailyFineRate.java`** — the interface. This is the one thing that varies by tier, so it is the only thing behind an interface:

```java
package bookline.fines;

/** Per-day fine amount for one membership tier. The only tier-varying rule. */
public interface DailyFineRate {

    /**
     * @param overdueDayNumber 1 for the first day past due, 2 for the second, and so on
     * @return cents to accrue for that overdue day
     */
    int amountInCents(int overdueDayNumber);
}
```

**`AdultDailyFineRate.java`**

```java
package bookline.fines;

public final class AdultDailyFineRate implements DailyFineRate {

    @Override
    public int amountInCents(int overdueDayNumber) {
        return 30;
    }
}
```

**`StudentDailyFineRate.java`**

```java
package bookline.fines;

public final class StudentDailyFineRate implements DailyFineRate {

    private static final int GRACE_DAYS = 2;

    @Override
    public int amountInCents(int overdueDayNumber) {
        return overdueDayNumber <= GRACE_DAYS ? 0 : 15;
    }
}
```

**`ConcessionDailyFineRate.java`**

```java
package bookline.fines;

public final class ConcessionDailyFineRate implements DailyFineRate {

    @Override
    public int amountInCents(int overdueDayNumber) {
        return 0;
    }
}
```

**`LoanPolicy.java`** — the resolution point. The member's LoanPolicy carries its tier's rate, injected where the policy is constructed. I am assuming you have an existing LoanPolicy class; this shows only the addition:

```java
package bookline.loans;

import bookline.fines.DailyFineRate;

public final class LoanPolicy {

    private final DailyFineRate dailyFineRate;
    // ... your existing policy fields ...

    public LoanPolicy(DailyFineRate dailyFineRate /*, your existing args */) {
        this.dailyFineRate = dailyFineRate;
    }

    public DailyFineRate dailyFineRate() {
        return dailyFineRate;
    }
}
```

Wherever each tier's LoanPolicy is built today, pass the matching rate: `new LoanPolicy(new AdultDailyFineRate(), ...)`, `new LoanPolicy(new StudentDailyFineRate(), ...)`, `new LoanPolicy(new ConcessionDailyFineRate(), ...)`. When the council changes a scheme next year, you add or edit one `DailyFineRate` class and its wiring; the accrual code below does not change.

**`FineAccrual.java`**

```java
package bookline.fines;

import java.time.LocalDate;

public record FineAccrual(long loanId, LocalDate accrualDate, int amountInCents) {}
```

**`FineAccrualRepository.java`**

```java
package bookline.fines;

public interface FineAccrualRepository {

    /** Total already accrued for this loan, in cents. */
    int totalAccruedInCents(long loanId);

    /**
     * Insert keyed on (loan_id, accrual_date). Returns false and writes nothing
     * if a row for that key already exists.
     */
    boolean insertIfAbsent(FineAccrual accrual);
}
```

Back `insertIfAbsent` with the database key that expresses the one-accrual-per-loan-per-day rule:

```sql
ALTER TABLE fine_accrual
    ADD CONSTRAINT uq_fine_accrual_loan_date UNIQUE (loan_id, accrual_date);
-- insertIfAbsent: INSERT ... ON CONFLICT (loan_id, accrual_date) DO NOTHING
```

**`LibraryCalendar.java`** — I am assuming this exists; the assessor needs this shape:

```java
package bookline.fines;

import java.time.LocalDate;

public interface LibraryCalendar {

    boolean isClosed(String branchId, LocalDate date);
}
```

**`OverdueLoans.java`** — source of loans for the nightly run. `Loan` is your existing class; the assessor uses `id()`, `branchId()`, `dueDate()`, `replacementCostInCents()`, and `policy()`:

```java
package bookline.loans;

import java.time.LocalDate;
import java.util.List;

public interface OverdueLoans {

    /** Loans past due and not yet returned as of the given date. */
    List<Loan> findOverdue(LocalDate asOf);
}
```

**`NightlyFineAssessor.java`** — all the shared rules live here, and only here:

```java
package bookline.fines;

import bookline.loans.Loan;
import bookline.loans.OverdueLoans;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public final class NightlyFineAssessor {

    private final OverdueLoans overdueLoans;
    private final FineAccrualRepository accruals;
    private final LibraryCalendar calendar;

    public NightlyFineAssessor(OverdueLoans overdueLoans,
                               FineAccrualRepository accruals,
                               LibraryCalendar calendar) {
        this.overdueLoans = overdueLoans;
        this.accruals = accruals;
        this.calendar = calendar;
    }

    /**
     * Run once per night for that night's date. Safe to re-run: the
     * (loan_id, accrual_date) key makes a repeat run a no-op.
     */
    public void assess(LocalDate accrualDate) {
        for (Loan loan : overdueLoans.findOverdue(accrualDate)) {
            assessLoan(loan, accrualDate);
        }
    }

    private void assessLoan(Loan loan, LocalDate accrualDate) {
        // Shared rule: no accrual on branch closure days.
        if (calendar.isClosed(loan.branchId(), accrualDate)) {
            return;
        }

        int overdueDayNumber = (int) ChronoUnit.DAYS.between(loan.dueDate(), accrualDate);
        if (overdueDayNumber < 1) {
            return;
        }

        // The only tier-specific call: per-day amount from the loan's policy.
        int amount = loan.policy().dailyFineRate().amountInCents(overdueDayNumber);
        if (amount <= 0) {
            return; // nothing to accrue: concession tier, or a student grace day
        }

        // Shared rule: total fines never exceed the item's replacement cost.
        // Enforced here, at the point the accrual is written: the day's amount
        // is clamped to the remaining headroom, so the final accrual may be
        // partial and the running total lands exactly on the cap.
        int headroom = loan.replacementCostInCents() - accruals.totalAccruedInCents(loan.id());
        if (headroom <= 0) {
            return;
        }

        accruals.insertIfAbsent(
                new FineAccrual(loan.id(), accrualDate, Math.min(amount, headroom)));
    }
}
```

Wire it at your composition root — plain construction, no singletons, no factories:

```java
NightlyFineAssessor assessor =
        new NightlyFineAssessor(overdueLoans, fineAccrualRepository, libraryCalendar);
assessor.assess(LocalDate.now(clock));
```

Two decisions worth flagging, both within your stated rules:

1. Zero-amount days (concession members, student grace days) write no row. The (loan_id, accrual_date) key governs uniqueness of accruals that are written; skipped days need nothing. If you want zero rows for audit purposes, say so and I will change the `amount <= 0` branch.
2. The overdue day number counts calendar days since the due date. Closure days still advance that count — they just do not accrue — so a student's 2-day grace is two calendar days, not two open days. If grace should count only open days, that is a one-line change in the assessor, but it would move calendar knowledge into shared code paths the tiers depend on, so I kept the simpler reading.
