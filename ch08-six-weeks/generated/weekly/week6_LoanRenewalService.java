import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
    private final NotificationService notificationService;
    private final Clock clock;

    private static final int MAX_RENEWALS = 3;
    private static final int MAX_RENEWALS_STUDENT = 5;
    private static final int RENEWAL_PERIOD_DAYS = 14;

    /**
     * How many days past the due date a loan may still be renewed.
     *
     * <p>A loan that is late by this many days or fewer renews normally. Past
     * the window the renewal is refused and the item is expected back, though
     * staff can waive that at the desk — see
     * {@link RenewalRule#OVERDUE_BEYOND_GRACE}.
     */
    private static final int OVERDUE_GRACE_DAYS = 3;

    /**
     * How far a due date may be pushed forward to clear a branch closure.
     *
     * <p>Long enough to clear the closures a member could actually turn up
     * to — a weekend, a bank holiday weekend, a week of stocktaking. Past
     * that the branch is shut for something structural (refurbishment, flood,
     * a closure that was never end-dated in the calendar), and walking the
     * date forward day by day would either hand the member a due date months
     * out or spin until it found one. See
     * {@link #onDayBranchIsOpen(LocalDate, Branch, Loan)}.
     */
    private static final int MAX_CLOSURE_ROLL_DAYS = 14;

    /**
     * Members owing more than this are blocked from renewing. Concession
     * members are exempt from the check entirely.
     */
    private static final Money MAX_OUTSTANDING_FINES = Money.of("10.00");

    /**
     * The renewal rules branch staff are permitted to waive at the desk.
     *
     * <p>Deliberately narrow. These four are <em>policy</em> refusals: the
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
        OUTSTANDING_HOLD,

        /**
         * Loan is overdue by more than {@link #OVERDUE_GRACE_DAYS} days.
         *
         * <p>A loan inside the grace window is not a refusal at all and needs
         * no override; this rule covers only the loan that is late enough
         * that the library wants the item back rather than extended.
         */
        OVERDUE_BEYOND_GRACE
    }

    /**
     * Whether a refusal should be told to the member.
     *
     * <p>An enum rather than a {@code boolean} so the decision is legible at
     * the call site: {@code renewLoan(id, memberId, SUPPRESS)} says what it
     * does, where {@code renewLoan(id, memberId, false)} would not, and
     * would not survive a second flag being added next to it.
     *
     * <p>{@link #SEND} is what every existing caller gets. Silence is the
     * setting you have to ask for by name.
     */
    public enum RefusalNotice {

        /** Tell the member their renewal was refused, and why. */
        SEND,

        /**
         * Refuse silently.
         *
         * <p>For the overnight bulk-renewal job, which sweeps every eligible
         * loan rather than acting on a member's request. Most of what it
         * touches will refuse for perfectly ordinary reasons, and the member
         * never asked for anything — a message telling them so is noise
         * about a request they did not make.
         */
        SUPPRESS
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
                              FineService fineService,
                              NotificationService notificationService) {
        this(loanRepository, holdRepository, fineService, notificationService,
                Clock.systemDefaultZone());
    }

    /**
     * As above, with the clock supplied. Renewal now depends on what day it
     * is — both for the grace window and for the due date an overdue renewal
     * is measured from — so tests need to be able to say what "today" is
     * rather than waiting for the calendar to cooperate.
     */
    public LoanRenewalService(LoanRepository loanRepository,
                              HoldRepository holdRepository,
                              FineService fineService,
                              NotificationService notificationService,
                              Clock clock) {
        this.loanRepository = loanRepository;
        this.holdRepository = holdRepository;
        this.fineService = fineService;
        this.notificationService = notificationService;
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Self-service renewal, with no staff override. The member is told if it
     * is refused.
     */
    @Transactional
    public Loan renewLoan(Long loanId, Long memberId) {
        return renewLoan(loanId, memberId, null, RefusalNotice.SEND);
    }

    /**
     * Renewal with an optional staff override. The member is told if it is
     * refused.
     */
    @Transactional
    public Loan renewLoan(Long loanId, Long memberId, StaffOverride override) {
        return renewLoan(loanId, memberId, override, RefusalNotice.SEND);
    }

    /**
     * Renewal with no staff override, choosing whether a refusal is passed on
     * to the member. This is the overload the overnight bulk job wants:
     * {@code renewLoan(loanId, memberId, RefusalNotice.SUPPRESS)}.
     */
    @Transactional
    public Loan renewLoan(Long loanId, Long memberId, RefusalNotice notice) {
        return renewLoan(loanId, memberId, null, notice);
    }

    /**
     * Renewal with an optional staff override.
     *
     * @param override the rules a member of staff has authorised waiving for
     *                 this renewal, or {@code null} for a normal renewal.
     *                 Only the rules in {@link RenewalRule} can be waived;
     *                 the ownership and already-returned checks always apply.
     * @param notice   whether a refusal is passed on to the member. Note that
     *                 this governs only whether the member hears about a
     *                 refusal — it does not affect whether the renewal is
     *                 refused, or why.
     */
    @Transactional
    public Loan renewLoan(Long loanId, Long memberId, StaffOverride override,
                          RefusalNotice notice) {
        Objects.requireNonNull(notice, "notice is required; pass RefusalNotice.SEND to notify");

        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new LoanNotFoundException(loanId));

        // Only the borrowing member may renew their own loan.
        // Not overridable: see RenewalRule.
        //
        // Not notified either, whatever `notice` says. The only Member we can
        // reach from here is the loan's owner, and they are by definition not
        // the person who made this request. Telling them their renewal was
        // refused would be a message about something they never did.
        if (!loan.getMember().getId().equals(memberId)) {
            throw new UnauthorizedRenewalException(loanId, memberId);
        }

        // Loan must still be active (not already returned).
        // Not overridable: see RenewalRule.
        if (loan.getReturnDate() != null) {
            throw refusal(loan, notice, "Loan has already been returned");
        }

        // An overdue loan is still renewable while it is inside the grace
        // window. Past that the item is wanted back, and only staff can say
        // otherwise.
        LocalDate today = LocalDate.now(clock);
        long daysOverdue = daysOverdue(loan, today);
        if (daysOverdue > OVERDUE_GRACE_DAYS) {
            if (!waives(override, RenewalRule.OVERDUE_BEYOND_GRACE)) {
                throw refusal(loan, notice,
                        "Loan is " + daysOverdue + " days overdue, beyond the "
                                + OVERDUE_GRACE_DAYS + "-day grace period for renewal");
            }
            logOverride(loanId, memberId, override, RenewalRule.OVERDUE_BEYOND_GRACE,
                    daysOverdue + " days overdue");
        }

        // Members carrying large fines cannot renew, unless they are exempt
        if (!isExemptFromFineCheck(loan.getMember().getTier())) {
            Money outstanding = fineService.outstandingBalanceFor(memberId);
            if (outstanding.compareTo(MAX_OUTSTANDING_FINES) > 0) {
                if (!waives(override, RenewalRule.OUTSTANDING_FINES)) {
                    throw refusal(loan, notice,
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
                throw refusal(loan, notice, "Renewal limit of " + maxRenewals + " reached");
            }
            logOverride(loanId, memberId, override, RenewalRule.RENEWAL_LIMIT,
                    "renewal limit of " + maxRenewals + " reached");
        }

        // Cannot renew if another member has a hold on this item
        boolean hasOutstandingHold = holdRepository
                .existsByCopyIdAndStatus(loan.getCopy().getId(), HoldStatus.ACTIVE);
        if (hasOutstandingHold) {
            if (!waives(override, RenewalRule.OUTSTANDING_HOLD)) {
                throw refusal(loan, notice,
                        "Item has outstanding holds and cannot be renewed");
            }
            // Louder than the others: this one costs a member who is not here.
            log.warn("Renewal override on loan {} (member {}) by staff {} waived an ACTIVE hold "
                            + "held by another member on copy {}. Reason: {}",
                    loanId, memberId, override.staffId(), loan.getCopy().getId(),
                    override.reason());
        }

        // Extend the due date and record the renewal
        loan.setDueDate(renewedDueDate(loan, today));
        loan.setRenewalCount(loan.getRenewalCount() + 1);

        return loanRepository.save(loan);
    }

    /**
     * How many days late the loan is, or {@code 0} if it is not late.
     */
    private static long daysOverdue(Loan loan, LocalDate today) {
        long days = ChronoUnit.DAYS.between(loan.getDueDate(), today);
        return Math.max(days, 0L);
    }

    /**
     * The due date a renewal should produce.
     *
     * <p>A loan renewed on time is extended from its existing due date, so a
     * member who renews early keeps the days they have not used yet.
     *
     * <p>An overdue loan is measured from today instead (BKL-214). Extending
     * a lapsed due date spends the renewal period on days that have already
     * gone by, and branches were handing members renewals that were part
     * expired — in the worst case, inside the grace window, already due back
     * in {@code RENEWAL_PERIOD_DAYS - OVERDUE_GRACE_DAYS} days.
     *
     * <p>The result is then moved off any day the home branch is shut, so the
     * date the member is given is a date they can act on.
     */
    private static LocalDate renewedDueDate(Loan loan, LocalDate today) {
        LocalDate from = loan.getDueDate().isBefore(today) ? today : loan.getDueDate();
        return onDayBranchIsOpen(from.plusDays(RENEWAL_PERIOD_DAYS), homeBranchOf(loan), loan);
    }

    /**
     * The home branch of the loaned copy, or {@code null} if we cannot see one.
     *
     * <p>Separated out so the null-handling is in one place and the callers
     * can stay readable. A missing copy or branch is not something renewal
     * should have an opinion about — see
     * {@link #onDayBranchIsOpen(LocalDate, Branch, Loan)}.
     */
    private static Branch homeBranchOf(Loan loan) {
        Copy copy = loan.getCopy();
        return copy == null ? null : copy.getHomeBranch();
    }

    /**
     * The first day from {@code dueDate} onwards on which {@code branch} is
     * open.
     *
     * <p>Forwards only. Pulling the date back to the previous open day would
     * shorten a renewal the member has already been promised, and can land in
     * the past for a loan renewed close to a closure. Pushing it forward
     * costs the library a day or two of shelf time and gives the member a
     * date they can actually turn up on, which is the whole point of the
     * change.
     *
     * <p>Three cases are deliberately treated as "leave the date alone"
     * rather than as failures, because none of them is a reason to refuse a
     * renewal that has already passed every rule the library actually has:
     *
     * <ul>
     *   <li><b>No branch on record.</b> Nothing to ask, so nothing to
     *       adjust.</li>
     *   <li><b>The calendar throws.</b> A branch calendar that is unavailable
     *       is an operational problem, not the member's; the renewal stands
     *       on the unadjusted date and the failure is logged.</li>
     *   <li><b>No open day within {@link #MAX_CLOSURE_ROLL_DAYS}.</b> The
     *       branch is shut for something structural rather than for a
     *       weekend. Walking further would hand the member a due date months
     *       out, and would spin forever against a branch marked closed with
     *       no end date. Logged at WARN because it means a branch calendar
     *       needs a human to look at it.</li>
     * </ul>
     */
    private static LocalDate onDayBranchIsOpen(LocalDate dueDate, Branch branch, Loan loan) {
        if (branch == null) {
            return dueDate;
        }
        try {
            LocalDate candidate = dueDate;
            for (int rolled = 0; rolled <= MAX_CLOSURE_ROLL_DAYS; rolled++) {
                if (!branch.isClosedOn(candidate)) {
                    if (rolled > 0) {
                        log.info("Renewed due date for loan {} moved from {} to {}: "
                                        + "home branch closed for {} day(s)",
                                loan.getId(), dueDate, candidate, rolled);
                    }
                    return candidate;
                }
                candidate = candidate.plusDays(1);
            }
            log.warn("Home branch of loan {} is closed on every day from {} to {}; "
                            + "renewing to {} unadjusted. Check the branch calendar.",
                    loan.getId(), dueDate, dueDate.plusDays(MAX_CLOSURE_ROLL_DAYS), dueDate);
            return dueDate;
        } catch (RuntimeException e) {
            log.error("Could not check branch opening for loan {} on {}; "
                            + "renewing to that date unadjusted",
                    loan.getId(), dueDate, e);
            return dueDate;
        }
    }

    /**
     * Tells the member their renewal was refused (unless suppressed) and
     * returns the exception for the caller to throw.
     *
     * <p>Returning the exception rather than throwing it keeps every refusal
     * a visible {@code throw} at the point it happens, so the control flow
     * still reads straight down the method.
     *
     * <p>The notification is a side effect of refusing, never a cause of it:
     * if the notification fails, the member still gets the refusal they were
     * owed, and the failure is logged rather than thrown in its place.
     */
    private RenewalNotAllowedException refusal(Loan loan, RefusalNotice notice, String reason) {
        if (notice == RefusalNotice.SEND) {
            try {
                notificationService.sendRenewalRefused(loan.getMember(), loan, reason);
            } catch (RuntimeException e) {
                log.error("Could not notify member {} that renewal of loan {} was refused ({})",
                        loan.getMember().getId(), loan.getId(), reason, e);
            }
        }
        return new RenewalNotAllowedException(reason);
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
