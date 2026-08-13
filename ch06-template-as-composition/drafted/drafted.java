// drafted illustrative code, written to contain the exact defects the prose dissects
public class ReturnProcessor {

    public void processReturn(Barcode barcode,
                              Consumer<Loan> closeLoanStep,
                              Function<Loan, Money> fineStep,
                              Consumer<Copy> routingStep) {
        Loan loan = loanRepository.findOpenLoan(barcode);
        closeLoanStep.accept(loan);
        fineStep.apply(loan);
        routingStep.accept(loan.copy());
    }
}
