package ie.ardaralibraries.bookline.circulation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/*
 * Bookline circulation core (compact authored version for the Chapter 7 experiment).
 * One file, several top-level types, so the test-generation prompt sees the whole surface.
 */

// ---------- domain ----------

enum CopyStatus { AVAILABLE, ON_LOAN, REPAIR, LOST }

class Copy {
    private final String barcode;
    private final String titleId;
    private CopyStatus status;
    private final BigDecimal replacementCost;
    Copy(String barcode, String titleId, CopyStatus status, BigDecimal replacementCost) {
        this.barcode = barcode; this.titleId = titleId; this.status = status;
        this.replacementCost = replacementCost;
    }
    String barcode() { return barcode; }
    String titleId() { return titleId; }
    CopyStatus status() { return status; }
    void markOnLoan() { this.status = CopyStatus.ON_LOAN; }
    void markAvailable() { this.status = CopyStatus.AVAILABLE; }
    BigDecimal replacementCost() { return replacementCost; }
}

class Member {
    private final String membershipNumber;
    private final String tier;
    Member(String membershipNumber, String tier) {
        this.membershipNumber = membershipNumber; this.tier = tier;
    }
    String membershipNumber() { return membershipNumber; }
    String tier() { return tier; }
}

record LoanPolicy(Duration loanPeriod, int renewalLimit, int concurrentLoanLimit,
                  BigDecimal dailyFineRate) {}

class Loan {
    private final String id;
    private final String copyBarcode;
    private final String titleId;
    private final String membershipNumber;
    private final Instant checkedOutAt;
    private final Instant dueAt;
    private Instant returnedAt;
    private int renewalCount;
    Loan(String id, String copyBarcode, String titleId, String membershipNumber,
         Instant checkedOutAt, Instant dueAt) {
        this.id = id; this.copyBarcode = copyBarcode; this.titleId = titleId;
        this.membershipNumber = membershipNumber;
        this.checkedOutAt = checkedOutAt; this.dueAt = dueAt;
    }
    String id() { return id; }
    String copyBarcode() { return copyBarcode; }
    String titleId() { return titleId; }
    String membershipNumber() { return membershipNumber; }
    Instant checkedOutAt() { return checkedOutAt; }
    Instant dueAt() { return dueAt; }
    Optional<Instant> returnedAt() { return Optional.ofNullable(returnedAt); }
    int renewalCount() { return renewalCount; }
    void recordReturn(Instant at) { this.returnedAt = at; }
    void recordRenewal() { this.renewalCount++; }
    boolean isOpen() { return returnedAt == null; }
}

class Hold {
    private final String id;
    private final String titleId;
    private final String membershipNumber;
    private final int priorityClass;   // lower is more urgent
    private final Instant placedAt;
    private boolean satisfied;
    Hold(String id, String titleId, String membershipNumber, int priorityClass, Instant placedAt) {
        this.id = id; this.titleId = titleId; this.membershipNumber = membershipNumber;
        this.priorityClass = priorityClass; this.placedAt = placedAt;
    }
    String id() { return id; }
    String titleId() { return titleId; }
    String membershipNumber() { return membershipNumber; }
    int priorityClass() { return priorityClass; }
    Instant placedAt() { return placedAt; }
    boolean isSatisfied() { return satisfied; }
    void markSatisfied() { this.satisfied = true; }
}

// ---------- ports ----------

interface LoanRepository {
    Optional<Loan> findOpenLoanByCopy(String copyBarcode);
    List<Loan> findOpenLoansByMember(String membershipNumber);
    List<Loan> findOpenLoansDueBefore(Instant instant);
    Loan save(Loan loan);
}

interface CopyRepository {
    Optional<Copy> findByBarcode(String barcode);
    Copy save(Copy copy);
}

interface HoldRepository {
    List<Hold> findUnsatisfiedByTitle(String titleId);
    Hold save(Hold hold);
}

interface LoanPolicyResolver {
    LoanPolicy policyFor(Member member, Copy copy);
}

/** Branch opening calendar. */
interface LibraryCalendar {
    boolean isOpenOn(LocalDate date);
}

// ---------- exceptions ----------

class CopyUnavailableException extends RuntimeException {
    CopyUnavailableException(String m) { super(m); }
}
class LoanLimitExceededException extends RuntimeException {
    LoanLimitExceededException(String m) { super(m); }
}
class RenewalRefusedException extends RuntimeException {
    RenewalRefusedException(String m) { super(m); }
}

// ---------- circulation ----------

class CirculationService {
    private final LoanRepository loanRepository;
    private final CopyRepository copyRepository;
    private final HoldRepository holdRepository;
    private final LoanPolicyResolver policyResolver;

    CirculationService(LoanRepository loanRepository, CopyRepository copyRepository,
                       HoldRepository holdRepository, LoanPolicyResolver policyResolver) {
        this.loanRepository = loanRepository;
        this.copyRepository = copyRepository;
        this.holdRepository = holdRepository;
        this.policyResolver = policyResolver;
    }

    /** Checks a copy out to a member. INV-1: a copy is on at most one open loan. */
    Loan checkout(Member member, String barcode, Instant now) {
        Copy copy = copyRepository.findByBarcode(barcode)
                .orElseThrow(() -> new CopyUnavailableException("No copy " + barcode));
        if (copy.status() != CopyStatus.AVAILABLE) {
            throw new CopyUnavailableException("Copy " + barcode + " is not available");
        }
        LoanPolicy policy = policyResolver.policyFor(member, copy);
        List<Loan> open = loanRepository.findOpenLoansByMember(member.membershipNumber());
        if (open.size() >= policy.concurrentLoanLimit()) {
            throw new LoanLimitExceededException("Loan limit reached for tier " + member.tier());
        }
        Loan loan = new Loan(java.util.UUID.randomUUID().toString(), copy.barcode(),
                copy.titleId(), member.membershipNumber(), now, now.plus(policy.loanPeriod()));
        copy.markOnLoan();
        copyRepository.save(copy);
        return loanRepository.save(loan);
    }

    /** INV-10: renewal is refused while any unsatisfied hold exists on the title. */
    Loan renew(Member member, String barcode, Instant now) {
        Loan loan = loanRepository.findOpenLoanByCopy(barcode)
                .orElseThrow(() -> new RenewalRefusedException("No open loan for " + barcode));
        if (!loan.membershipNumber().equals(member.membershipNumber())) {
            throw new RenewalRefusedException("Loan does not belong to this member");
        }
        Copy copy = copyRepository.findByBarcode(barcode).orElseThrow();
        LoanPolicy policy = policyResolver.policyFor(member, copy);
        if (loan.renewalCount() >= policy.renewalLimit()) {
            throw new RenewalRefusedException("Renewal limit reached");
        }
        if (!holdRepository.findUnsatisfiedByTitle(loan.titleId()).isEmpty()) {
            throw new RenewalRefusedException("Unsatisfied hold exists on this title");
        }
        loan.recordRenewal();
        return loanRepository.save(loan);
    }

    void returnCopy(String barcode, Instant now) {
        Loan loan = loanRepository.findOpenLoanByCopy(barcode)
                .orElseThrow(() -> new CopyUnavailableException("No open loan for " + barcode));
        loan.recordReturn(now);
        loanRepository.save(loan);
        Copy copy = copyRepository.findByBarcode(barcode).orElseThrow();
        copy.markAvailable();
        copyRepository.save(copy);
    }

    List<Loan> overdueLoans(Instant now) {
        return loanRepository.findOpenLoansDueBefore(now);
    }
}

// ---------- hold queue ----------

class HoldQueueService {
    private final HoldRepository holdRepository;
    HoldQueueService(HoldRepository holdRepository) { this.holdRepository = holdRepository; }

    /** INV-4: first-come-first-served within a priority class; one copy satisfies one hold. */
    Optional<Hold> nextHoldFor(String titleId) {
        return holdRepository.findUnsatisfiedByTitle(titleId).stream()
                .sorted(Comparator.comparingInt(Hold::priorityClass)
                        .thenComparing(Hold::placedAt))
                .findFirst();
    }

    Optional<Hold> satisfyNext(String titleId) {
        Optional<Hold> next = nextHoldFor(titleId);
        next.ifPresent(h -> { h.markSatisfied(); holdRepository.save(h); });
        return next;
    }
}
