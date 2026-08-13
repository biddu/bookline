package ie.ardaralibraries.bookline.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/* Bookline billing core (compact authored version for the Chapter 7 experiment). */

record FineAccrual(String loanId, LocalDate accrualDate, BigDecimal amount) {}

interface FineAccrualRepository {
    boolean existsForLoanAndDate(String loanId, LocalDate date);
    FineAccrual save(FineAccrual accrual);
    List<FineAccrual> findByLoan(String loanId);
}

interface BillingCalendar {
    boolean isOpenOn(LocalDate date);
}

/** Facts billing needs about a loan; supplied by circulation. */
record OverdueLoanFacts(String loanId, LocalDate dueDate, Optional<LocalDate> returnedDate,
                        BigDecimal dailyFineRate, BigDecimal replacementCost) {}

class FineCalculator {

    /**
     * The fine for one overdue day. INV-5 cap clause: the accumulated fine never
     * exceeds the item's replacement cost, so the increment is clamped on write.
     */
    BigDecimal dailyIncrement(OverdueLoanFacts facts, BigDecimal accruedSoFar) {
        BigDecimal rate = facts.dailyFineRate();
        BigDecimal cap = facts.replacementCost();
        BigDecimal headroom = cap.subtract(accruedSoFar);
        if (headroom.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return rate.min(headroom);
    }
}

class FineAccrualService {
    private final FineAccrualRepository accrualRepository;
    private final BillingCalendar calendar;
    private final FineCalculator calculator;
    static final ZoneId LIBRARY_ZONE = ZoneId.of("Europe/Dublin");

    FineAccrualService(FineAccrualRepository accrualRepository, BillingCalendar calendar,
                       FineCalculator calculator) {
        this.accrualRepository = accrualRepository;
        this.calendar = calendar;
        this.calculator = calculator;
    }

    /**
     * Nightly accrual for one overdue loan up to (and excluding) today.
     * INV-5: once per overdue day; never for a closed day; capped at replacement cost.
     * Idempotent under retry: keyed on (loanId, accrualDate).
     */
    List<FineAccrual> accrueUpTo(OverdueLoanFacts facts, Instant now) {
        LocalDate today = now.atZone(LIBRARY_ZONE).toLocalDate();
        LocalDate end = facts.returnedDate().orElse(today);
        List<FineAccrual> written = new ArrayList<>();
        BigDecimal accrued = Fines.totalFor(accrualRepository, facts.loanId());
        for (LocalDate day = facts.dueDate().plusDays(1); day.isBefore(end); day = day.plusDays(1)) {
            if (!calendar.isOpenOn(day)) {
                continue;                       // INV-5 closure clause
            }
            if (accrualRepository.existsForLoanAndDate(facts.loanId(), day)) {
                continue;                       // idempotency under retry
            }
            BigDecimal amount = calculator.dailyIncrement(facts, accrued);
            if (amount.signum() <= 0) {
                break;                          // cap reached
            }
            FineAccrual accrual = new FineAccrual(facts.loanId(), day, amount);
            written.add(accrualRepository.save(accrual));
            accrued = accrued.add(amount);
        }
        return written;
    }
}

// small helper kept package-private on the record via an extension-style default
// (plain static helper to avoid polluting the record)
class Fines {
    static BigDecimal totalFor(FineAccrualRepository repo, String loanId) {
        return repo.findByLoan(loanId).stream()
                .map(FineAccrual::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
