package ie.ardaralibraries.bookline.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FineAccrualServiceTest {

    private static final String LOAN_ID = "L-1";
    // Winter instant: Europe/Dublin is UTC+0, so the Dublin date is 2026-01-15.
    private static final Instant WINTER_NOW = Instant.parse("2026-01-15T12:00:00Z");

    @Mock FineAccrualRepository accrualRepository;
    @Mock BillingCalendar calendar;

    private FineAccrualService service;

    @BeforeEach
    void setUp() {
        // Real calculator: pure, deterministic, and part of the behaviour under test.
        service = new FineAccrualService(accrualRepository, calendar, new FineCalculator());
    }

    private OverdueLoanFacts facts(LocalDate dueDate, LocalDate returnedDate,
                                   String dailyRate, String replacementCost) {
        return new OverdueLoanFacts(LOAN_ID, dueDate, Optional.ofNullable(returnedDate),
                new BigDecimal(dailyRate), new BigDecimal(replacementCost));
    }

    private void savePassesThrough() {
        when(accrualRepository.save(any(FineAccrual.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void accruesOncePerOpenOverdueDay_excludingDueDateAndEndDate() {
        // Due Jan 5, returned Jan 10 -> chargeable days are Jan 6..9.
        OverdueLoanFacts facts = facts(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 10),
                "0.50", "100.00");
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of());
        when(calendar.isOpenOn(any(LocalDate.class))).thenReturn(true);
        when(accrualRepository.existsForLoanAndDate(eq(LOAN_ID), any(LocalDate.class))).thenReturn(false);
        savePassesThrough();

        List<FineAccrual> written = service.accrueUpTo(facts, WINTER_NOW);

        assertEquals(4, written.size());
        assertEquals(List.of(LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 7),
                        LocalDate.of(2026, 1, 8), LocalDate.of(2026, 1, 9)),
                written.stream().map(FineAccrual::accrualDate).toList());
        for (FineAccrual accrual : written) {
            assertEquals(LOAN_ID, accrual.loanId());
            assertEquals(0, new BigDecimal("0.50").compareTo(accrual.amount()));
        }
        verify(accrualRepository, times(4)).save(any(FineAccrual.class));
    }

    @Test
    void skipsClosedDaysWithoutCharging() {
        // Jan 7 is closed: no accrual for it, and no idempotency lookup either.
        OverdueLoanFacts facts = facts(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 10),
                "0.50", "100.00");
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of());
        when(calendar.isOpenOn(any(LocalDate.class)))
                .thenAnswer(inv -> !inv.getArgument(0).equals(LocalDate.of(2026, 1, 7)));
        when(accrualRepository.existsForLoanAndDate(eq(LOAN_ID), any(LocalDate.class))).thenReturn(false);
        savePassesThrough();

        List<FineAccrual> written = service.accrueUpTo(facts, WINTER_NOW);

        assertEquals(List.of(LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 8),
                        LocalDate.of(2026, 1, 9)),
                written.stream().map(FineAccrual::accrualDate).toList());
        verify(accrualRepository, never()).existsForLoanAndDate(LOAN_ID, LocalDate.of(2026, 1, 7));
        verify(accrualRepository, never())
                .save(argThat(a -> a.accrualDate().equals(LocalDate.of(2026, 1, 7))));
    }

    @Test
    void idempotentUnderRetry_skipsDaysAlreadyAccrued() {
        OverdueLoanFacts facts = facts(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9),
                "0.50", "100.00");
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of());
        when(calendar.isOpenOn(any(LocalDate.class))).thenReturn(true);
        when(accrualRepository.existsForLoanAndDate(eq(LOAN_ID), any(LocalDate.class)))
                .thenAnswer(inv -> inv.getArgument(1).equals(LocalDate.of(2026, 1, 6)));
        savePassesThrough();

        List<FineAccrual> written = service.accrueUpTo(facts, WINTER_NOW);

        assertEquals(List.of(LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 8)),
                written.stream().map(FineAccrual::accrualDate).toList());
        verify(accrualRepository, never())
                .save(argThat(a -> a.accrualDate().equals(LocalDate.of(2026, 1, 6))));
    }

    @Test
    void clampsFinalIncrementToReplacementCostAndStops() {
        // Rate 3.00, cap 5.00: day one charges 3.00, day two charges 2.00, then stops
        // even though more overdue days remain.
        OverdueLoanFacts facts = facts(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 12),
                "3.00", "5.00");
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of());
        when(calendar.isOpenOn(any(LocalDate.class))).thenReturn(true);
        when(accrualRepository.existsForLoanAndDate(eq(LOAN_ID), any(LocalDate.class))).thenReturn(false);
        savePassesThrough();

        List<FineAccrual> written = service.accrueUpTo(facts, WINTER_NOW);

        assertEquals(2, written.size());
        assertEquals(0, new BigDecimal("3.00").compareTo(written.get(0).amount()));
        assertEquals(0, new BigDecimal("2.00").compareTo(written.get(1).amount()));
        BigDecimal total = written.stream().map(FineAccrual::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("5.00").compareTo(total));
        verify(accrualRepository, times(2)).save(any(FineAccrual.class));
    }

    @Test
    void countsPreviouslyPersistedAccrualsTowardTheCap() {
        // 4.50 already on file against a 5.00 cap: only 0.50 of headroom remains.
        OverdueLoanFacts facts = facts(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 12),
                "2.00", "5.00");
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of(
                new FineAccrual(LOAN_ID, LocalDate.of(2026, 1, 2), new BigDecimal("2.00")),
                new FineAccrual(LOAN_ID, LocalDate.of(2026, 1, 3), new BigDecimal("2.50"))));
        when(calendar.isOpenOn(any(LocalDate.class))).thenReturn(true);
        when(accrualRepository.existsForLoanAndDate(eq(LOAN_ID), any(LocalDate.class))).thenReturn(false);
        savePassesThrough();

        List<FineAccrual> written = service.accrueUpTo(facts, WINTER_NOW);

        assertEquals(1, written.size());
        assertEquals(0, new BigDecimal("0.50").compareTo(written.get(0).amount()));
    }

    @Test
    void capAlreadyReached_writesNothing() {
        OverdueLoanFacts facts = facts(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 12),
                "2.00", "5.00");
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of(
                new FineAccrual(LOAN_ID, LocalDate.of(2026, 1, 2), new BigDecimal("5.00"))));
        when(calendar.isOpenOn(any(LocalDate.class))).thenReturn(true);
        when(accrualRepository.existsForLoanAndDate(eq(LOAN_ID), any(LocalDate.class))).thenReturn(false);

        List<FineAccrual> written = service.accrueUpTo(facts, WINTER_NOW);

        assertTrue(written.isEmpty());
        verify(accrualRepository, never()).save(any());
    }

    @Test
    void loanDueYesterday_nothingAccruedYet_becauseTodayIsExcluded() {
        // Due Jan 14, now Jan 15: the first chargeable day is today, which the
        // nightly run excludes, so nothing is written.
        OverdueLoanFacts facts = facts(LocalDate.of(2026, 1, 14), null, "0.50", "100.00");
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of());

        List<FineAccrual> written = service.accrueUpTo(facts, WINTER_NOW);

        assertTrue(written.isEmpty());
        verify(accrualRepository, never()).save(any());
        verifyNoInteractions(calendar);
    }

    @Test
    void openLoan_accruesUpToTodayInTheDublinTimeZone() {
        // 2026-05-31T23:30Z is already 2026-06-01 in Europe/Dublin (IST, UTC+1),
        // so with dueDate May 30 the day May 31 is chargeable. Using the UTC date
        // (May 31) as "today" would wrongly produce no accruals.
        Instant summerNow = Instant.parse("2026-05-31T23:30:00Z");
        OverdueLoanFacts facts = facts(LocalDate.of(2026, 5, 30), null, "0.50", "100.00");
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of());
        when(calendar.isOpenOn(LocalDate.of(2026, 5, 31))).thenReturn(true);
        when(accrualRepository.existsForLoanAndDate(LOAN_ID, LocalDate.of(2026, 5, 31))).thenReturn(false);
        savePassesThrough();

        List<FineAccrual> written = service.accrueUpTo(facts, summerNow);

        assertEquals(1, written.size());
        assertEquals(LocalDate.of(2026, 5, 31), written.get(0).accrualDate());
    }

    // ---------- Fines helper ----------

    @Test
    void finesTotal_isZeroWhenNoAccrualsExist() {
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of());

        assertEquals(0, BigDecimal.ZERO.compareTo(Fines.totalFor(accrualRepository, LOAN_ID)));
    }

    @Test
    void finesTotal_sumsAllAccrualAmounts() {
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of(
                new FineAccrual(LOAN_ID, LocalDate.of(2026, 1, 6), new BigDecimal("0.50")),
                new FineAccrual(LOAN_ID, LocalDate.of(2026, 1, 7), new BigDecimal("0.50")),
                new FineAccrual(LOAN_ID, LocalDate.of(2026, 1, 8), new BigDecimal("0.25"))));

        assertEquals(0, new BigDecimal("1.25").compareTo(Fines.totalFor(accrualRepository, LOAN_ID)));
    }
}
