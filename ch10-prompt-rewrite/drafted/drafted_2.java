// circulation/HoldService.java
public class HoldService {

    private static final int MAX_OPEN_LOANS = 10;

    private final HoldRepository holds;
    private final LoanRepository loans;
    private final DomainEvents events;

    public Hold placeHold(MembershipNumber member, Isbn title) {
        // ... availability check via catalogue, duplicate-hold check ...
        Hold hold = Hold.place(member, title, clock.instant());
        holds.save(hold);
        events.publish(new HoldPlaced(hold.id(), member, title));
        return hold;
    }

    public Loan satisfyHold(HoldId holdId, Barcode availableCopy) {
        Hold hold = holds.findById(holdId).orElseThrow();
        List<Loan> open = loans.openLoansFor(hold.member());
        if (open.size() >= MAX_OPEN_LOANS) {
            events.publish(new HoldSatisfactionDeferred(holdId));
            throw new LoanLimitExceededException(hold.member());
        }
        // ... convert hold to loan, publish HoldSatisfied ...
    }
}

// circulation/CirculationService.java (renewal addition)
    public Renewal renew(LoanId loanId) {
        Loan loan = loans.findById(loanId).orElseThrow();
        LoanPolicy policy = policies.policyFor(loan.member());
        if (loan.renewalCount() >= policy.renewalLimit()) {
            return Renewal.refused(loan, RefusalReason.LIMIT_REACHED);
        }
        if (holds.unsatisfiedHoldExistsOnTitle(loan.title())) {
            return Renewal.refused(loan, RefusalReason.HOLD_WAITING);
        }
        // ... derive new due date from policy, publish LoanRenewed ...
    }
