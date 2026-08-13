# Fine Calculation with the Strategy Pattern

Here's a complete implementation. I'm assuming `Loan` exposes a due date and `Money` is a value object with basic arithmetic; I've noted the exact methods I rely on, so adjust the call sites if your signatures differ.

## Design overview

- `FineStrategy` — the strategy interface: given a loan and an as-of date, produce a fine as `Money`.
- Three concrete strategies: `StandardFineStrategy`, `StudentFineStrategy` (lower rate, longer grace period), and `NoFineStrategy` (staff and senior members).
- `FineStrategyFactory` — selects the strategy from the borrower's membership tier.
- `FineCalculator` — the context class the rest of the application talks to.

## 1. The strategy interface

```java
package com.bookline.fines;

import com.bookline.loans.Loan;
import com.bookline.money.Money;

import java.time.LocalDate;

/**
 * Strategy for computing the fine owed on a loan as of a given date.
 * Implementations must be stateless and thread-safe.
 */
public interface FineStrategy {

    /**
     * @param loan     the loan to assess (never null)
     * @param asOfDate the date to assess the fine against, typically today
     *                 or the actual return date (never null)
     * @return the fine owed; {@link Money#ZERO} if the loan is not overdue
     */
    Money calculateFine(Loan loan, LocalDate asOfDate);
}
```

## 2. Concrete strategies

```java
package com.bookline.fines;

import com.bookline.loans.Loan;
import com.bookline.money.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Standard members: $0.50 per overdue day after a 2-day grace period,
 * capped at $25.00.
 */
public final class StandardFineStrategy implements FineStrategy {

    private static final BigDecimal DAILY_RATE = new BigDecimal("0.50");
    private static final int GRACE_DAYS = 2;
    private static final BigDecimal MAX_FINE = new BigDecimal("25.00");

    @Override
    public Money calculateFine(Loan loan, LocalDate asOfDate) {
        long overdueDays = overdueDays(loan, asOfDate, GRACE_DAYS);
        if (overdueDays <= 0) {
            return Money.ZERO;
        }
        BigDecimal fine = DAILY_RATE.multiply(BigDecimal.valueOf(overdueDays));
        return Money.of(fine.min(MAX_FINE));
    }

    static long overdueDays(Loan loan, LocalDate asOfDate, int graceDays) {
        LocalDate effectiveDue = loan.dueDate().plusDays(graceDays);
        return ChronoUnit.DAYS.between(effectiveDue, asOfDate);
    }
}
```

```java
package com.bookline.fines;

import com.bookline.loans.Loan;
import com.bookline.money.Money;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Student members: $0.25 per overdue day after a 7-day grace period,
 * capped at $10.00.
 */
public final class StudentFineStrategy implements FineStrategy {

    private static final BigDecimal DAILY_RATE = new BigDecimal("0.25");
    private static final int GRACE_DAYS = 7;
    private static final BigDecimal MAX_FINE = new BigDecimal("10.00");

    @Override
    public Money calculateFine(Loan loan, LocalDate asOfDate) {
        long overdueDays =
            StandardFineStrategy.overdueDays(loan, asOfDate, GRACE_DAYS);
        if (overdueDays <= 0) {
            return Money.ZERO;
        }
        BigDecimal fine = DAILY_RATE.multiply(BigDecimal.valueOf(overdueDays));
        return Money.of(fine.min(MAX_FINE));
    }
}
```

```java
package com.bookline.fines;

import com.bookline.loans.Loan;
import com.bookline.money.Money;

import java.time.LocalDate;

/**
 * Staff and senior members are never fined.
 */
public final class NoFineStrategy implements FineStrategy {

    @Override
    public Money calculateFine(Loan loan, LocalDate asOfDate) {
        return Money.ZERO;
    }
}
```

## 3. Strategy selection

```java
package com.bookline.fines;

import com.bookline.members.MembershipTier;

/**
 * Maps a membership tier to its fine strategy. Strategies are stateless,
 * so single shared instances are reused.
 */
public final class FineStrategyFactory {

    private static final FineStrategy STANDARD = new StandardFineStrategy();
    private static final FineStrategy STUDENT = new StudentFineStrategy();
    private static final FineStrategy NO_FINE = new NoFineStrategy();

    private FineStrategyFactory() {
    }

    public static FineStrategy forTier(MembershipTier tier) {
        return switch (tier) {
            case STANDARD -> STANDARD;
            case STUDENT -> STUDENT;
            case STAFF, SENIOR -> NO_FINE;
        };
    }
}
```

If `MembershipTier` doesn't exist yet:

```java
package com.bookline.members;

public enum MembershipTier {
    STANDARD,
    STUDENT,
    STAFF,
    SENIOR
}
```

## 4. The context class

```java
package com.bookline.fines;

import com.bookline.loans.Loan;
import com.bookline.money.Money;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Application-facing entry point for fine calculation. Selects the
 * strategy from the loan's borrower tier and delegates to it.
 */
public final class FineCalculator {

    private final Clock clock;

    public FineCalculator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Fine owed as of today (per the injected clock). */
    public Money fineFor(Loan loan) {
        return fineFor(loan, LocalDate.now(clock));
    }

    /** Fine owed as of an explicit date, e.g. the actual return date. */
    public Money fineFor(Loan loan, LocalDate asOfDate) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(asOfDate, "asOfDate");
        FineStrategy strategy =
            FineStrategyFactory.forTier(loan.borrower().membershipTier());
        return strategy.calculateFine(loan, asOfDate);
    }
}
```

## 5. Usage

```java
FineCalculator calculator = new FineCalculator(Clock.systemDefaultZone());
Money owed = calculator.fineFor(loan);            // as of today
Money owedAtReturn = calculator.fineFor(loan, returnDate);
```

## Assumptions about your existing classes

- `Loan.dueDate()` returns `LocalDate`, and `Loan.borrower().membershipTier()` returns a `MembershipTier`. If the tier lives elsewhere (e.g. on a `Member` looked up by id), pass the tier into `fineFor` instead and drop the `borrower()` call.
- `Money.of(BigDecimal)` constructs a value and `Money.ZERO` exists. If your factory method takes a currency or cents, adapt the two `Money.of(...)` call sites.

## Notes

- **Rates, grace periods, and caps are compile-time constants** inside each strategy (`0.50`, `0.25`, `25.00`, `10.00`, grace days `2` and `7`). That keeps the example self-contained; in production you'd likely load these from configuration and pass them into the strategy constructors so a rate change doesn't require a deploy.
- Strategies are stateless and shared, so the factory returns singletons; the `switch` over the sealed set of enum values is exhaustive, so adding a tier without a strategy becomes a compile error.
- `FineCalculator` takes a `Clock` so "today" is testable — inject `Clock.fixed(...)` in tests.
- Grace-day arithmetic lives in one place (`StandardFineStrategy.overdueDays`); if a third rate-bearing strategy appears, consider extracting an abstract `PerDayFineStrategy(rate, graceDays, cap)` base and making the concrete classes thin — with only two rate-bearing strategies, the duplication isn't yet worth the hierarchy.

## Suggested tests

```java
@Test
void standardMemberOwesNothingWithinGracePeriod() {
    Loan loan = loanDue(LocalDate.of(2026, 3, 1), MembershipTier.STANDARD);
    assertEquals(Money.ZERO,
        calculator.fineFor(loan, LocalDate.of(2026, 3, 3)));
}

@Test
void standardFineIsCappedAtTwentyFiveDollars() {
    Loan loan = loanDue(LocalDate.of(2026, 1, 1), MembershipTier.STANDARD);
    assertEquals(Money.of(new BigDecimal("25.00")),
        calculator.fineFor(loan, LocalDate.of(2026, 6, 1)));
}

@Test
void staffMembersAreNeverFined() {
    Loan loan = loanDue(LocalDate.of(2026, 1, 1), MembershipTier.STAFF);
    assertEquals(Money.ZERO,
        calculator.fineFor(loan, LocalDate.of(2026, 6, 1)));
}
```

Fill in `loanDue(...)` with however you construct `Loan` test fixtures. If you share the actual `Loan` and `Money` signatures, I'll adjust the call sites exactly.
