package ie.ardaralibraries.bookline.circulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CirculationServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-10T10:00:00Z");
    private static final String BARCODE = "BC-001";
    private static final String TITLE = "title-42";

    @Mock LoanRepository loanRepository;
    @Mock CopyRepository copyRepository;
    @Mock HoldRepository holdRepository;
    @Mock LoanPolicyResolver policyResolver;

    private CirculationService service;
    private Member member;

    @BeforeEach
    void setUp() {
        service = new CirculationService(loanRepository, copyRepository, holdRepository, policyResolver);
        member = new Member("M-100", "standard");
    }

    private Copy availableCopy() {
        return new Copy(BARCODE, TITLE, CopyStatus.AVAILABLE, new BigDecimal("25.00"));
    }

    private LoanPolicy policy(int renewalLimit, int concurrentLimit) {
        return new LoanPolicy(Duration.ofDays(14), renewalLimit, concurrentLimit, new BigDecimal("0.50"));
    }

    private Loan openLoanFor(String membershipNumber) {
        return new Loan("L-1", BARCODE, TITLE, membershipNumber, NOW.minus(Duration.ofDays(7)),
                NOW.plus(Duration.ofDays(7)));
    }

    // ---------- checkout ----------

    @Test
    void checkout_createsOpenLoanWithPolicyDueDate() {
        Copy copy = availableCopy();
        when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
        when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 3));
        when(loanRepository.findOpenLoansByMember("M-100")).thenReturn(List.of());
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(copyRepository.save(any(Copy.class))).thenAnswer(inv -> inv.getArgument(0));

        Loan loan = service.checkout(member, BARCODE, NOW);

        assertNotNull(loan.id());
        assertEquals(BARCODE, loan.copyBarcode());
        assertEquals(TITLE, loan.titleId());
        assertEquals("M-100", loan.membershipNumber());
        assertEquals(NOW, loan.checkedOutAt());
        assertEquals(NOW.plus(Duration.ofDays(14)), loan.dueAt());
        assertTrue(loan.isOpen());
        assertEquals(0, loan.renewalCount());
        verify(loanRepository).save(loan);
    }

    @Test
    void checkout_marksCopyOnLoanAndSavesIt() {
        Copy copy = availableCopy();
        when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
        when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 3));
        when(loanRepository.findOpenLoansByMember("M-100")).thenReturn(List.of());
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.checkout(member, BARCODE, NOW);

        assertEquals(CopyStatus.ON_LOAN, copy.status());
        verify(copyRepository).save(copy);
    }

    @Test
    void checkout_unknownBarcode_throwsCopyUnavailable() {
        when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.empty());

        assertThrows(CopyUnavailableException.class, () -> service.checkout(member, BARCODE, NOW));
        verify(loanRepository, never()).save(any());
        verify(copyRepository, never()).save(any());
    }

    @Test
    void checkout_copyNotAvailable_throwsForEveryNonAvailableStatus() {
        for (CopyStatus status : new CopyStatus[]{CopyStatus.ON_LOAN, CopyStatus.REPAIR, CopyStatus.LOST}) {
            Copy copy = new Copy(BARCODE, TITLE, status, new BigDecimal("25.00"));
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));

            assertThrows(CopyUnavailableException.class, () -> service.checkout(member, BARCODE, NOW),
                    "expected refusal for status " + status);
        }
        verify(loanRepository, never()).save(any());
        verify(copyRepository, never()).save(any());
    }

    @Test
    void checkout_atConcurrentLoanLimit_throwsAndLeavesCopyUntouched() {
        Copy copy = availableCopy();
        when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
        when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 2));
        when(loanRepository.findOpenLoansByMember("M-100"))
                .thenReturn(List.of(openLoanFor("M-100"), openLoanFor("M-100")));

        assertThrows(LoanLimitExceededException.class, () -> service.checkout(member, BARCODE, NOW));
        assertEquals(CopyStatus.AVAILABLE, copy.status());
        verify(loanRepository, never()).save(any());
        verify(copyRepository, never()).save(any());
    }

    @Test
    void checkout_justBelowConcurrentLoanLimit_succeeds() {
        Copy copy = availableCopy();
        when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
        when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 2));
        when(loanRepository.findOpenLoansByMember("M-100")).thenReturn(List.of(openLoanFor("M-100")));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(copyRepository.save(any(Copy.class))).thenAnswer(inv -> inv.getArgument(0));

        Loan loan = service.checkout(member, BARCODE, NOW);

        assertTrue(loan.isOpen());
    }

    // ---------- renew ----------

    @Test
    void renew_incrementsRenewalCountAndSaves() {
        Loan loan = openLoanFor("M-100");
        Copy copy = new Copy(BARCODE, TITLE, CopyStatus.ON_LOAN, new BigDecimal("25.00"));
        when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));
        when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
        when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 3));
        when(holdRepository.findUnsatisfiedByTitle(TITLE)).thenReturn(List.of());
        when(loanRepository.save(loan)).thenReturn(loan);

        Loan renewed = service.renew(member, BARCODE, NOW);

        assertSame(loan, renewed);
        assertEquals(1, renewed.renewalCount());
        verify(loanRepository).save(loan);
    }

    @Test
    void renew_noOpenLoan_throwsRenewalRefused() {
        when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.empty());

        assertThrows(RenewalRefusedException.class, () -> service.renew(member, BARCODE, NOW));
        verify(loanRepository, never()).save(any());
    }

    @Test
    void renew_loanBelongsToDifferentMember_throwsRenewalRefused() {
        Loan loan = openLoanFor("M-OTHER");
        when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));

        assertThrows(RenewalRefusedException.class, () -> service.renew(member, BARCODE, NOW));
        assertEquals(0, loan.renewalCount());
        verify(loanRepository, never()).save(any());
    }

    @Test
    void renew_renewalLimitReached_throwsRenewalRefused() {
        Loan loan = openLoanFor("M-100");
        loan.recordRenewal();
        loan.recordRenewal();      // renewalCount == 2 == limit
        Copy copy = new Copy(BARCODE, TITLE, CopyStatus.ON_LOAN, new BigDecimal("25.00"));
        when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));
        when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
        when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 3));

        assertThrows(RenewalRefusedException.class, () -> service.renew(member, BARCODE, NOW));
        assertEquals(2, loan.renewalCount());
        verify(loanRepository, never()).save(any());
    }

    @Test
    void renew_unsatisfiedHoldOnTitle_throwsRenewalRefused() {
        Loan loan = openLoanFor("M-100");
        Copy copy = new Copy(BARCODE, TITLE, CopyStatus.ON_LOAN, new BigDecimal("25.00"));
        Hold hold = new Hold("H-1", TITLE, "M-999", 1, NOW.minus(Duration.ofDays(1)));
        when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));
        when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
        when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 3));
        when(holdRepository.findUnsatisfiedByTitle(TITLE)).thenReturn(List.of(hold));

        assertThrows(RenewalRefusedException.class, () -> service.renew(member, BARCODE, NOW));
        assertEquals(0, loan.renewalCount());
        verify(loanRepository, never()).save(any());
    }

    // ---------- returnCopy ----------

    @Test
    void returnCopy_recordsReturnAndMakesCopyAvailable() {
        Loan loan = openLoanFor("M-100");
        Copy copy = new Copy(BARCODE, TITLE, CopyStatus.ON_LOAN, new BigDecimal("25.00"));
        when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));
        when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
        when(loanRepository.save(loan)).thenReturn(loan);
        when(copyRepository.save(copy)).thenReturn(copy);

        service.returnCopy(BARCODE, NOW);

        assertFalse(loan.isOpen());
        assertEquals(Optional.of(NOW), loan.returnedAt());
        assertEquals(CopyStatus.AVAILABLE, copy.status());
        verify(loanRepository).save(loan);
        verify(copyRepository).save(copy);
    }

    @Test
    void returnCopy_noOpenLoan_throwsCopyUnavailable() {
        when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.empty());

        assertThrows(CopyUnavailableException.class, () -> service.returnCopy(BARCODE, NOW));
        verify(loanRepository, never()).save(any());
        verify(copyRepository, never()).save(any());
    }

    // ---------- overdueLoans ----------

    @Test
    void overdueLoans_delegatesToRepository() {
        Loan overdue = openLoanFor("M-100");
        when(loanRepository.findOpenLoansDueBefore(NOW)).thenReturn(List.of(overdue));

        List<Loan> result = service.overdueLoans(NOW);

        assertEquals(List.of(overdue), result);
        verify(loanRepository).findOpenLoansDueBefore(NOW);
    }
}
