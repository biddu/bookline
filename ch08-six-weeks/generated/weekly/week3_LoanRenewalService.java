import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class LoanRenewalService {

    private static final Logger log = LoggerFactory.getLogger(LoanRenewalService.class);

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

    /**
     * The renewal rules branch staff are permitted to waive at the desk.
     *
     * <p>Deliberately narrow. These three are <em>policy</em> refusals: the
     * loan could legitimately be renewed, and the library has simply chosen
     * not to by default. The other refusals in {@link #renewLoan} are not
     * listed here and cannot be overridden by any value of this enum:
     *
     * <ul>
     *   <li>a loan that does not exist — there is nothing to renew;</li>
     *   <li>a loan belonging to a different member — this is an identity
     *       check, not a policy one. Staff renewing at the desk pass the
     *       member's own id, so a mismatch means the wrong loan was pulled
     *       up, and extending it would touch a stranger's record;</li>
     *   <li>a loan already returned — the item is back on the shelf. Renewing
     *       it produces a due date for a copy the member does not hold, and
     *       corrupts the loan history rather than granting anything.</li>
     * </ul>
     */
    public enum RenewalRule {

        /** Member owes more than {@link #MAX_OUTSTANDING_FINES}. */
        OUTSTANDING_FINES,

        /** Member has already renewed the maximum number of times. */
        RENEWAL_LIMIT,

        /**
         * Another member has an active hold on this copy.
         *
         * <p>Waiving this one is different in kind from the other two: it
         * does not relax a rule that only affects the member at the desk, it
         * takes an item from a member who is not in the room to argue for
         * it. It is overridable because a supervisor sometimes genuinely
         * needs to, but it must be named explicitly and is logged at WARN.
         */
        OUTSTANDING_HOLD
    }

    /**
     * A named, reasoned authorisation from a member of staff to waive
     * specific renewal rules for a single renewal.
     *
     * <p>There is deliberately no "override everything" flag. Staff state
     * which rules they are waiving, so the override cannot silently grow to
     * cover a refusal nobody considered, and the audit line records what was
     * actually set aside.
     */
    public record StaffOverride(Long staffId, String reason, Set<RenewalRule> waivedRules) {

        public StaffOverride {
            Objects.requireNonNull(staffId, "staffId is required to authorise an override");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "An override must record why it was granted");
            }
            if (waivedRules == null || waivedRules.isEmpty()) {
                throw new IllegalArgumentException(
                        "An override must name at least one rule to waive");
            }
            waivedRules = Collections.unmodifiableSet(EnumSet.copyOf(waivedRules));
        }

        public static StaffOverride of(Long staffId, String reason, RenewalRule... rules) {
            Set<RenewalRule> waived = (rules == null || rules.length == 0)
                    ? EnumSet.noneOf(RenewalRule.class)
                    : EnumSet.copyOf(List.of(rules));
            return new StaffOverride(staffId, reason, waived);
        }

        boolean waives(RenewalRule rule) {
            return waivedRules.contains(rule);
        }
    }

    public LoanRenewalService(LoanRepository loanRepository,
                              HoldRepository holdRepository,
                              FineService fineService) {
        this.loanRepository = loanRepository;
        this.holdRepository = holdRepository;
        this.fineService = fineService;
    }

    /**
     * Self-service renewal, with no staff override. Behaves exactly as before.
     */
    @Transactional
    public Loan renewLoan(Long loanId, Long memberId) {
        return renewLoan(loanId, memberId, null);
    }

    /**
     * Renewal with an optional staff override.
     *
     * @param override the rules a member of staff has authorised waiving for
     *                 this renewal, or {@code null} for a normal renewal.
     *                 Only the rules in {@link RenewalRule} can be waived;
     *                 the ownership and already-returned checks always apply.
     */
    @Transactional
    public Loan renewLoan(Long loanId, Long memberId, StaffOverride override) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new LoanNotFoundException(loanId));

        // Only the borrowing member may renew their own loan.
        // Not overridable: see RenewalRule.
        if (!loan.getMember().getId().equals(memberId)) {
            throw new UnauthorizedRenewalException(loanId, memberId);
        }

        // Loan must still be active (not already returned).
        // Not overridable: see RenewalRule.
        if (loan.getReturnDate() != null) {
            throw new RenewalNotAllowedException("Loan has already been returned");
        }

        // Members carrying large fines cannot renew, unless they are exempt
        if (!isExemptFromFineCheck(loan.getMember().getTier())) {
            Money outstanding = fineService.outstandingBalanceFor(memberId);
            if (outstanding.compareTo(MAX_OUTSTANDING_FINES) > 0) {
                if (!waives(override, RenewalRule.OUTSTANDING_FINES)) {
                    throw new RenewalNotAllowedException(
                            "Outstanding fines of " + outstanding
                                    + " exceed the renewal limit of " + MAX_OUTSTANDING_FINES);
                }
                logOverride(loanId, memberId, override, RenewalRule.OUTSTANDING_FINES,
                        "outstanding fines of " + outstanding);
            }
        }

        // Respect the renewal limit for this member's tier
        int maxRenewals = maxRenewalsFor(loan.getMember().getTier());
        if (loan.getRenewalCount() >= maxRenewals) {
            if (!waives(override, RenewalRule.RENEWAL_LIMIT)) {
                throw new RenewalNotAllowedException(
                        "Renewal limit of " + maxRenewals + " reached");
            }
            logOverride(loanId, memberId, override, RenewalRule.RENEWAL_LIMIT,
                    "renewal limit of " + maxRenewals + " reached");
        }

        // Cannot renew if another member has a hold on this item
        boolean hasOutstandingHold = holdRepository
                .existsByCopyIdAndStatus(loan.getCopy().getId(), HoldStatus.ACTIVE);
        if (hasOutstandingHold) {
            if (!waives(override, RenewalRule.OUTSTANDING_HOLD)) {
                throw new RenewalNotAllowedException(
                        "Item has outstanding holds and cannot be renewed");
            }
            // Louder than the others: this one costs a member who is not here.
            log.warn("Renewal override on loan {} (member {}) by staff {} waived an ACTIVE hold "
                            + "held by another member on copy {}. Reason: {}",
                    loanId, memberId, override.staffId(), loan.getCopy().getId(),
                    override.reason());
        }

        // Extend the due date and record the renewal
        loan.setDueDate(loan.getDueDate().plusDays(RENEWAL_PERIOD_DAYS));
        loan.setRenewalCount(loan.getRenewalCount() + 1);

        return loanRepository.save(loan);
    }

    private static boolean waives(StaffOverride override, RenewalRule rule) {
        return override != null && override.waives(rule);
    }

    private static void logOverride(Long loanId,
                                    Long memberId,
                                    StaffOverride override,
                                    RenewalRule rule,
                                    String refusal) {
        log.info("Renewal override on loan {} (member {}) by staff {}: waived {} ({}). Reason: {}",
                loanId, memberId, override.staffId(), rule, refusal, override.reason());
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
