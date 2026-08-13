@Service
public class CheckoutService {

    private final LoanRepository loans;

    public CheckoutService(LoanRepository loans) {
        this.loans = loans;
    }

    public Loan checkOut(Barcode copy, MembershipNumber member) {
        Optional<Loan> open = loans.findOpenLoanFor(copy);
        if (open.isPresent()) {
            throw new CopyUnavailableException(copy);
        }
        LocalDate today = LocalDate.now();
        Loan loan = new Loan(copy, member, today, today.plusDays(21));
        return loans.save(loan);
    }
}
