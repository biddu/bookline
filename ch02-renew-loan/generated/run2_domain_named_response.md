Here's a `renewLoan` method that handles the renewal rules you described. I've written it as a service-layer method since it coordinates several entities:

```java
@Service
public class LoanRenewalService {

    private final LoanRepository loanRepository;
    private final HoldRepository holdRepository;

    private static final int MAX_RENEWALS = 3;
    private static final int RENEWAL_PERIOD_DAYS = 14;

    public LoanRenewalService(LoanRepository loanRepository,
                              HoldRepository holdRepository) {
        this.loanRepository = loanRepository;
        this.holdRepository = holdRepository;
    }

    @Transactional
    public Loan renewLoan(Long loanId, Long memberId) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new LoanNotFoundException(loanId));

        // Only the borrowing member may renew their own loan
        if (!loan.getMember().getId().equals(memberId)) {
            throw new UnauthorizedRenewalException(loanId, memberId);
        }

        // Loan must still be active (not already returned)
        if (loan.getReturnDate() != null) {
            throw new RenewalNotAllowedException("Loan has already been returned");
        }

        // Respect the renewal limit
        if (loan.getRenewalCount() >= MAX_RENEWALS) {
            throw new RenewalNotAllowedException(
                    "Renewal limit of " + MAX_RENEWALS + " reached");
        }

        // Cannot renew if another member has a hold on this title
        Title title = loan.getCopy().getTitle();
        boolean hasOutstandingHold = holdRepository
                .existsByTitleIdAndStatus(title.getId(), HoldStatus.ACTIVE);
        if (hasOutstandingHold) {
            throw new RenewalNotAllowedException(
                    "Title has outstanding holds and cannot be renewed");
        }

        // Extend the due date and record the renewal
        loan.setDueDate(loan.getDueDate().plusDays(RENEWAL_PERIOD_DAYS));
        loan.setRenewalCount(loan.getRenewalCount() + 1);

        return loanRepository.save(loan);
    }
}
```

A few notes on the design decisions:

- **Renewal limit**: tracked with a `renewalCount` on `Loan`, compared against `MAX_RENEWALS`. If your limit varies by member type or title, move the constant onto `Member` or `Title` and read it from there.
- **Holds**: the renewal is blocked if any *active* hold exists on the title. The hold check goes through the `Copy` to its `Title`, since holds in most library systems are placed at the title level, not on a specific copy.
- **Ownership check**: the method verifies that the member requesting the renewal is the one who holds the loan, so one member can't renew another's loan.
- **Extension base**: the new due date extends from the *current* due date, not from today. If you'd rather renew from the renewal date (common when the item is overdue), use `LocalDate.now().plusDays(RENEWAL_PERIOD_DAYS)` instead.
- **Transactionality**: `@Transactional` ensures the hold check and the due-date update happen atomically, avoiding a race where a hold is placed between the check and the save.

You'll need small custom exceptions (`LoanNotFoundException`, `UnauthorizedRenewalException`, `RenewalNotAllowedException`) — unchecked exceptions that a controller advice can map to 404/403/409 responses. Let me know if you also want overdue handling (e.g., blocking renewal of overdue loans or ones with outstanding fines) and I can add that.
