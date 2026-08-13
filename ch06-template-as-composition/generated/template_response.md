Here's a design for the return workflow. The short version of my approach: the workflow itself is a fixed five-step sequence with exactly one configuration today, so I kept it as a single plain method whose steps read top-to-bottom. The flexibility lives in the *pieces most likely to change* — fine assessment is an injected strategy (a functional interface you can implement with a lambda), and the routing outcome is a sealed type you can extend and switch over exhaustively. I deliberately did **not** build a template-method class hierarchy: with one call site and one configuration, abstract hook methods would be speculative structure you'd have to read around every day.

```java
// --- Domain types -----------------------------------------------------------

public record BranchId(String value) {}

public record CopyId(String barcode) {}

public record Copy(CopyId id, String titleId, BranchId homeBranch) {}

public record Money(java.math.BigDecimal amount) {
    public static final Money ZERO = new Money(java.math.BigDecimal.ZERO);
    public boolean isPositive() { return amount.signum() > 0; }
}

public final class Loan {
    private final String loanId;
    private final CopyId copyId;
    private final String patronId;
    private final java.time.LocalDate dueDate;
    private java.time.Instant returnedAt; // null while open

    public Loan(String loanId, CopyId copyId, String patronId, java.time.LocalDate dueDate) {
        this.loanId = loanId;
        this.copyId = copyId;
        this.patronId = patronId;
        this.dueDate = dueDate;
    }

    public boolean isOpen() { return returnedAt == null; }

    public void close(java.time.Instant when) {
        if (!isOpen()) {
            throw new IllegalStateException("Loan %s is already closed".formatted(loanId));
        }
        this.returnedAt = when;
    }

    public String loanId() { return loanId; }
    public CopyId copyId() { return copyId; }
    public String patronId() { return patronId; }
    public java.time.LocalDate dueDate() { return dueDate; }
}

// --- The parts that are likely to change ------------------------------------

/**
 * Fine assessment is the piece of this workflow with real churn
 * (grace periods, per-title caps, fine-free pilots), so it is a
 * strategy you inject — usually as a lambda.
 */
@FunctionalInterface
public interface FinePolicy {
    Money assess(Loan loan, java.time.Instant returnedAt);
}

/** Where the copy physically goes next. Sealed so switches stay exhaustive. */
public sealed interface Disposition {
    record Reshelved(BranchId branch) implements Disposition {}
    record InTransit(BranchId from, BranchId to) implements Disposition {}
}

/** Everything the caller needs to act on: receipt printing, fine posting, hold slips. */
public record CheckInResult(
        Loan closedLoan,
        Money fineAssessed,
        Disposition disposition,
        java.util.Optional<Hold> holdToNotify
) {}

// --- Ports ------------------------------------------------------------------

public interface LoanRepository {
    java.util.Optional<Loan> findOpenByCopy(CopyId copyId);
    void save(Loan loan);
}

public interface CopyRepository {
    Copy require(CopyId copyId);
}

public record Hold(String holdId, String patronId, BranchId pickupBranch) {}

public interface HoldQueue {
    /** Offers the copy to the next queued hold for its title, if any. */
    java.util.Optional<Hold> offer(Copy copy);
}

public class NoOpenLoanException extends RuntimeException {
    public NoOpenLoanException(CopyId copyId) {
        super("No open loan for copy " + copyId.barcode());
    }
}

// --- The workflow -----------------------------------------------------------

public final class ReturnService {

    private final LoanRepository loans;
    private final CopyRepository copies;
    private final HoldQueue holds;
    private final FinePolicy finePolicy;

    public ReturnService(LoanRepository loans, CopyRepository copies,
                         HoldQueue holds, FinePolicy finePolicy) {
        this.loans = loans;
        this.copies = copies;
        this.holds = holds;
        this.finePolicy = finePolicy;
    }

    public CheckInResult checkIn(CopyId copyId, BranchId returnBranch, java.time.Instant now) {
        // 1. Find the open loan.
        Loan loan = loans.findOpenByCopy(copyId)
                .orElseThrow(() -> new NoOpenLoanException(copyId));

        // 2. Close it.
        loan.close(now);
        loans.save(loan);

        // 3. Assess any fine — returned to the caller, not swallowed here.
        Money fine = finePolicy.assess(loan, now);

        // 4. Route the copy.
        Copy copy = copies.require(copyId);
        Disposition disposition = copy.homeBranch().equals(returnBranch)
                ? new Disposition.Reshelved(returnBranch)
                : new Disposition.InTransit(returnBranch, copy.homeBranch());

        // 5. Offer the copy to the hold queue.
        java.util.Optional<Hold> claimedBy = holds.offer(copy);

        return new CheckInResult(loan, fine, disposition, claimedBy);
    }
}
```

Wiring it up — the fine policy is just a lambda at the composition root:

```java
FinePolicy standardFines = (loan, returnedAt) -> {
    var returnedOn = java.time.LocalDate.ofInstant(returnedAt, java.time.ZoneOffset.UTC);
    long daysLate = java.time.temporal.ChronoUnit.DAYS.between(loan.dueDate(), returnedOn);
    if (daysLate <= 0) return Money.ZERO;
    var perDay = new java.math.BigDecimal("0.25");
    var cap = new java.math.BigDecimal("10.00");
    return new Money(perDay.multiply(java.math.BigDecimal.valueOf(daysLate)).min(cap));
};

ReturnService service = new ReturnService(loanRepo, copyRepo, holdQueue, standardFines);
```

A few deliberate choices, so you know what to revisit later:

1. **No template-method hierarchy.** The step sequence (find → close → fine → route → holds) is policy that belongs in one readable place. If a genuinely different *sequence* ever appears (say, self-service kiosks that skip fines entirely), model that with a second `FinePolicy` or a second small service — don't retrofit abstract hooks into this one.
2. **The fine is data, not a side effect.** `checkIn` reports the assessed fine in `CheckInResult`; posting it to the patron's account, waiving it, or printing it on a receipt is the caller's decision. That keeps this method testable without a billing system.
3. **Routing returns a value.** `Disposition` is sealed, so when you later add `HeldForPickup` or `SentToRepair`, every `switch` over it fails to compile until it's handled.
4. **Ordering caveat worth confirming with the domain folks:** you specified route-then-holds, and the code follows that. Many ILS systems check holds *first*, because a hold captures the copy before it's routed home. If that's your real rule, swap steps 4 and 5 and fold the hold's pickup branch into the routing decision — the sealed `Disposition` makes that a compile-checked change.

Tests are cheap because everything is injected: fake the three ports, pass `FinePolicy` as `(l, t) -> Money.ZERO` or a fixed amount, and assert on the returned `CheckInResult` — no mocks of static state, no subclass-per-test.
