Renewal is part of a loan's lifecycle, the same way returning is — so I'd put it on `Loan` itself rather than introduce a subclass or a separate entity. The interesting design decisions are (1) `dueAt` stops being `final`, and (2) "ordinarily twice" means the limit is policy, not a hard-coded invariant, so the class should let a caller (e.g. a librarian override) exceed it deliberately rather than bury `2` in a conditional.

Here's the updated class:

```java
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Loan {

    /** Snapshot of one renewal, kept for auditing and display. */
    public record Renewal(Instant renewedAt, Instant previousDueAt, Instant newDueAt) {
        public Renewal {
            Objects.requireNonNull(renewedAt, "renewedAt");
            Objects.requireNonNull(previousDueAt, "previousDueAt");
            Objects.requireNonNull(newDueAt, "newDueAt");
        }
    }

    public static final int STANDARD_RENEWAL_LIMIT = 2;

    private final Barcode copyBarcode;
    private final MembershipNumber memberNumber;
    private final Instant checkedOutAt;
    private final Instant originalDueAt;
    private Instant dueAt;                       // moves on renewal
    private Instant returnedAt;                  // null until returned
    private final List<Renewal> renewals = new ArrayList<>();

    public Loan(Barcode copyBarcode,
                MembershipNumber memberNumber,
                Instant checkedOutAt,
                Instant dueAt) {
        this.copyBarcode = Objects.requireNonNull(copyBarcode, "copyBarcode");
        this.memberNumber = Objects.requireNonNull(memberNumber, "memberNumber");
        this.checkedOutAt = Objects.requireNonNull(checkedOutAt, "checkedOutAt");
        this.dueAt = Objects.requireNonNull(dueAt, "dueAt");
        if (!dueAt.isAfter(checkedOutAt)) {
            throw new IllegalArgumentException("dueAt must be after checkedOutAt");
        }
        this.originalDueAt = dueAt;
    }

    /**
     * Renews this loan under the standard limit of {@value #STANDARD_RENEWAL_LIMIT}
     * renewals.
     */
    public void renew(Instant on, Instant newDueAt) {
        renew(on, newDueAt, STANDARD_RENEWAL_LIMIT);
    }

    /**
     * Renews this loan, moving the due date to {@code newDueAt}.
     *
     * @param renewalLimit the maximum number of renewals permitted for this
     *        request — ordinarily {@link #STANDARD_RENEWAL_LIMIT}, but callers
     *        with authority (e.g. a librarian override) may pass a higher limit
     * @throws LoanNotRenewableException if the loan has been returned or the
     *         limit has been reached
     * @throws IllegalArgumentException if {@code newDueAt} does not move the
     *         due date forward
     */
    public void renew(Instant on, Instant newDueAt, int renewalLimit) {
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(newDueAt, "newDueAt");
        if (returnedAt != null) {
            throw new LoanNotRenewableException(
                "Loan of " + copyBarcode + " has already been returned");
        }
        if (renewals.size() >= renewalLimit) {
            throw new LoanNotRenewableException(
                "Loan of " + copyBarcode + " has reached its renewal limit ("
                    + renewalLimit + ")");
        }
        if (!newDueAt.isAfter(dueAt)) {
            throw new IllegalArgumentException(
                "newDueAt " + newDueAt + " must be after current dueAt " + dueAt);
        }
        renewals.add(new Renewal(on, dueAt, newDueAt));
        dueAt = newDueAt;
    }

    public int renewalCount() {
        return renewals.size();
    }

    public List<Renewal> renewals() {
        return Collections.unmodifiableList(renewals);
    }

    public Instant dueAt() {
        return dueAt;
    }

    public Instant originalDueAt() {
        return originalDueAt;
    }

    // existing accessors and markReturned(on) unchanged
}
```

And the exception, so callers can distinguish "not allowed" from a programming error:

```java
public class LoanNotRenewableException extends RuntimeException {
    public LoanNotRenewableException(String message) {
        super(message);
    }
}
```

A few notes on the choices:

**Why not a subclass or a separate `Renewal` entity?** A `RenewableLoan` subclass would imply some loans can never be renewed — that's not the requirement; every loan is renewable until policy says stop. And a free-standing renewal entity in the domain model would force every due-date query to join loan + renewals to find the effective date. Instead the `Renewal` record lives *inside* `Loan` as an audit trail: `dueAt` always answers "when is it due" directly, and `renewals()` answers "how did we get here."

**Why is the due date mutated in place?** `dueAt` is genuinely mutable state, like `returnedAt` already was — the class was never fully immutable. Keeping `originalDueAt` final preserves the checkout-time invariant (`dueAt.isAfter(checkedOutAt)` is validated against the original), and the renewal history means no information is lost by the mutation.

**Why does the caller supply `newDueAt`?** Computing the new due date (14 days? until end of term? shorter if another member has a hold?) is loan *policy*, and it likely needs information `Loan` doesn't have — holds on the copy, member standing, calendar rules. The entity enforces its own invariants (not returned, under the limit, date moves forward) and leaves the calculation to a `RenewalPolicy`/service at the application layer. That's also where "ordinarily twice" belongs: the standard limit is the default, but the overload takes the limit as an argument so an override path exists without `Loan` knowing anything about librarian permissions.

**Concurrency caveat:** if two renewal requests for the same loan can race (e.g. two branch terminals), guard at the persistence layer — optimistic locking on the loan row/aggregate version — rather than synchronizing this class.

One thing worth adding when you wire this up: a test that renewing a returned loan and renewing past the limit both throw `LoanNotRenewableException`, and one asserting `renewals()` preserves the prior due dates in order — that's the audit trail future-you will rely on when a member disputes a fine.
