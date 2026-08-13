@ExtendWith(MockitoExtension.class)
class CirculationServiceTest {

    @Mock private LoanRepository loanRepository;
    @Mock private CopyRepository copyRepository;
    @Mock private LoanPolicyResolver policyResolver;

    @InjectMocks private CirculationService circulationService;

    @Test
    void testCheckoutCreatesLoan() {
        when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(availableCopy));
        when(policyResolver.policyFor(member, availableCopy)).thenReturn(STANDARD_POLICY);
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        Loan loan = circulationService.checkout(member, BARCODE);

        assertNotNull(loan);
        verify(loanRepository).save(any(Loan.class));
    }

    @Test
    void testCheckoutSetsDueDate() {
        when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(availableCopy));
        when(policyResolver.policyFor(member, availableCopy)).thenReturn(STANDARD_POLICY);
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        Loan loan = circulationService.checkout(member, BARCODE);

        assertEquals(loan.getCheckedOutAt().plus(STANDARD_POLICY.loanPeriod()),
                     loan.getDueAt());
    }

    @Test
    void testGetOverdueLoansReturnsOverdueLoans() {
        List<Loan> overdueLoans = List.of(overdueLoan);
        when(loanRepository.findByReturnedAtIsNullAndDueAtBefore(any()))
            .thenReturn(overdueLoans);

        List<Loan> result = circulationService.overdueLoans();

        assertEquals(overdueLoans, result);
    }

    @Test
    void testCheckoutFailsWhenCopyOnLoan() {
        when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copyOnLoan));

        assertThrows(CopyUnavailableException.class,
            () -> circulationService.checkout(member, BARCODE));
    }
}

class FineAccrualServiceTest {

    @Test
    void testFineForTenDaysOverdue() {
        // expected value confirmed against current output
        Money fine = fineCalculator.fineFor(loanOverdueBy(10), STANDARD_POLICY);
        assertEquals(new BigDecimal("3.00"), fine.amount());
    }

    @Test
    void testFineForOneHundredTwentyDaysOverdue() {
        Money fine = fineCalculator.fineFor(loanOverdueBy(120), STANDARD_POLICY);
        assertEquals(new BigDecimal("25.00"), fine.amount());
    }

    @Test
    void testAccrueSavesAccrual() {
        accrualService.accrueFor(overdueLoan, TODAY);
        verify(accrualRepository).save(any(FineAccrual.class));
    }
}
