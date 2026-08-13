package ie.ardaralibraries.bookline.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * INV-5, re-run clause: "A fine accrues once per overdue day, never twice for the
 * same day, never on a day the branch was closed, and never exceeds the item's
 * replacement cost."
 *
 * This test asserts the "never twice for the same day" clause, which is what the
 * 03:40 retry of a failed nightly run depends on.
 */
class FineAccrualIdempotencyTest {

    private static final String LOAN_ID = "loan-1";
    private static final LocalDate FEB_3 = LocalDate.of(2026, 2, 3);
    private static final ZoneId DUBLIN = ZoneId.of("Europe/Dublin");

    /** In-memory accrual store: no unique key, exactly like the production table today. */
    static final class InMemoryAccrualRepository implements FineAccrualRepository {
        final List<FineAccrual> rows = new ArrayList<>();

        @Override
        public boolean existsForLoanAndDate(String loanId, LocalDate date) {
            return rows.stream().anyMatch(a -> a.loanId().equals(loanId)
                    && a.accrualDate().equals(date));
        }

        @Override
        public FineAccrual save(FineAccrual accrual) {
            rows.add(accrual);
            return accrual;
        }

        @Override
        public List<FineAccrual> findByLoan(String loanId) {
            return rows.stream().filter(a -> a.loanId().equals(loanId)).toList();
        }
    }

    private InMemoryAccrualRepository accrualRepository;
    private FineAccrualService accrualService;

    @BeforeEach
    void setUp() {
        accrualRepository = new InMemoryAccrualRepository();
        accrualService = new FineAccrualService(
                accrualRepository,
                day -> true,                       // every branch open
                new FineCalculator());
    }

    private OverdueLoanFacts overdueLoan() {
        return new OverdueLoanFacts(LOAN_ID, FEB_3.minusDays(3), Optional.empty(),
                new BigDecimal("0.30"), new BigDecimal("25.00"));
    }

    private Instant nightOf(LocalDate date) {
        return date.plusDays(1).atStartOfDay(DUBLIN).toInstant();
    }

    @Test
    void runningAccrualTwiceForTheSameDayChargesOnce() {
        accrualService.accrueUpTo(overdueLoan(), nightOf(FEB_3));
        accrualService.accrueUpTo(overdueLoan(), nightOf(FEB_3));   // the 03:40 retry

        long rowsForFeb3 = accrualRepository.findByLoan(LOAN_ID).stream()
                .filter(a -> a.accrualDate().equals(FEB_3))
                .count();

        assertEquals(1, rowsForFeb3,
                "INV-5: a fine accrues once per overdue day, never twice for the same day");
    }
}
