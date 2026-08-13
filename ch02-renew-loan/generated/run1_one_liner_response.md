Here's a `renewLoan` method for a library system. I've included the supporting pieces it needs (a result type and a custom exception) so it drops into a typical domain model:

```java
import java.time.LocalDate;

public class LoanService {

    private static final int MAX_RENEWALS = 3;
    private static final int RENEWAL_PERIOD_DAYS = 14;

    private final LoanRepository loanRepository;
    private final HoldRepository holdRepository;

    public LoanService(LoanRepository loanRepository, HoldRepository holdRepository) {
        this.loanRepository = loanRepository;
        this.holdRepository = holdRepository;
    }

    /**
     * Renews an active loan, extending its due date by the standard
     * renewal period.
     *
     * @param loanId the ID of the loan to renew
     * @return the updated loan with its new due date
     * @throws LoanRenewalException if the loan cannot be renewed
     */
    public Loan renewLoan(long loanId) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new LoanRenewalException("Loan not found: " + loanId));

        if (loan.isReturned()) {
            throw new LoanRenewalException("Loan has already been returned.");
        }

        if (loan.getRenewalCount() >= MAX_RENEWALS) {
            throw new LoanRenewalException(
                    "Maximum number of renewals (" + MAX_RENEWALS + ") reached.");
        }

        if (holdRepository.hasActiveHold(loan.getBookId())) {
            throw new LoanRenewalException(
                    "Another patron has placed a hold on this book.");
        }

        if (loan.getDueDate().isBefore(LocalDate.now())) {
            throw new LoanRenewalException(
                    "Loan is overdue; please return the book or contact the library.");
        }

        // Extend from the current due date, not from today, so patrons
        // don't gain extra time by renewing early.
        LocalDate newDueDate = loan.getDueDate().plusDays(RENEWAL_PERIOD_DAYS);
        loan.setDueDate(newDueDate);
        loan.setRenewalCount(loan.getRenewalCount() + 1);

        return loanRepository.save(loan);
    }
}
```

And the exception class:

```java
public class LoanRenewalException extends RuntimeException {
    public LoanRenewalException(String message) {
        super(message);
    }
}
```

A few notes on the choices made here:

- **Renewal limit** — `MAX_RENEWALS` caps how many times a patron can renew, which is standard library policy. Adjust the constant (or make it configurable per membership tier) as needed.
- **Holds block renewal** — if another patron is waiting for the book, renewal is refused. If your system doesn't track holds yet, you can drop the `holdRepository` check.
- **Overdue loans can't be renewed** — many libraries allow renewing overdue items; if yours does, remove that check or convert it to a fine calculation instead.
- **Extension is from the due date, not today** — this prevents patrons from gaining extra loan time by renewing early. If your policy is "14 days from renewal date," change `loan.getDueDate()` to `LocalDate.now()` on that line.

If you're using Spring, annotate the class with `@Service` and the method with `@Transactional` so the read-check-update sequence is atomic and two concurrent renewals can't both pass the `renewalCount` check.
