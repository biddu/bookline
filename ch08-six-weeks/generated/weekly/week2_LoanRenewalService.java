@Service
public class LoanRenewalService {

    private final LoanRepository loanRepository;
    private final HoldRepository holdRepository;
    private final FineService fineService;

    private static final int MAX_RENEWALS = 3;
    private static final int MAX_RENEWALS_STUDENT = 5;
    private static final int RENEWAL_PERIOD_DAYS = 14;

    /**
     * Members owing more than this are blocked from renewing. Concession
     * members are exempt from the check entirely.
     */
    private static final Money MAX_OUTSTANDING_FINES = Money.of("10.00");

    public LoanRenewalService(LoanRepository loanRepository,
                              HoldRepository holdRepository,
                              FineService fineService) {
        this.loanRepository = loanRepository;
        this.holdRepository = holdRepository;
        this.fineService = fineService;
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

        // Members carrying large fines cannot renew, unless they are exempt
        if (!isExemptFromFineCheck(loan.getMember().getTier())) {
            Money outstanding = fineService.outstandingBalanceFor(memberId);
            if (outstanding.compareTo(MAX_OUTSTANDING_FINES) > 0) {
                throw new RenewalNotAllowedException(
                        "Outstanding fines of " + outstanding
                                + " exceed the renewal limit of " + MAX_OUTSTANDING_FINES);
            }
        }

        // Respect the renewal limit for this member's tier
        int maxRenewals = maxRenewalsFor(loan.getMember().getTier());
        if (loan.getRenewalCount() >= maxRenewals) {
            throw new RenewalNotAllowedException(
                    "Renewal limit of " + maxRenewals + " reached");
        }

        // Cannot renew if another member has a hold on this item
        boolean hasOutstandingHold = holdRepository
                .existsByCopyIdAndStatus(loan.getCopy().getId(), HoldStatus.ACTIVE);
        if (hasOutstandingHold) {
            throw new RenewalNotAllowedException(
                    "Item has outstanding holds and cannot be renewed");
        }

        // Extend the due date and record the renewal
        loan.setDueDate(loan.getDueDate().plusDays(RENEWAL_PERIOD_DAYS));
        loan.setRenewalCount(loan.getRenewalCount() + 1);

        return loanRepository.save(loan);
    }

    /**
     * Concession members may renew regardless of what they owe. Every other
     * tier (including a member with no tier recorded) is subject to the
     * outstanding-fine check.
     */
    private static boolean isExemptFromFineCheck(MembershipTier tier) {
        return tier == MembershipTier.CONCESSION;
    }

    /**
     * Students are allowed more renewals than other members; every other tier
     * (including a member with no tier recorded) keeps the standard limit.
     */
    private static int maxRenewalsFor(MembershipTier tier) {
        return tier == MembershipTier.STUDENT ? MAX_RENEWALS_STUDENT : MAX_RENEWALS;
    }
}
