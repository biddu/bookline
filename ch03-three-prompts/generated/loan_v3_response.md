Here's the `Loan` class. Every stated rule that can be checked mechanically is checked in the constructor or in `markReturned`; the fields are all `final` except `returnedAt` and `renewalCount`, and there are no setters.

```java
package ie.ardaralibraries.bookline.circulation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A loan of one copy (identified by {@link Barcode}) to one member
 * (identified by {@link MembershipNumber}).
 *
 * <p>Invariants enforced by this class:
 * <ul>
 *   <li>{@code dueAt} is fixed at construction and is strictly after
 *       {@code checkedOutAt}; the loan period is supplied by the caller.</li>
 *   <li>{@code returnedAt} starts null, is set exactly once via
 *       {@link #markReturned(Instant)}, and cannot precede
 *       {@code checkedOutAt}.</li>
 *   <li>No setters; all constructor arguments are validated.</li>
 * </ul>
 */
public final class Loan {

    private final Barcode copyBarcode;
    private final MembershipNumber memberNumber;
    private final Instant checkedOutAt;
    private final Instant dueAt;

    private Instant returnedAt; // null until markReturned is called, then immutable
    private int renewalCount;

    /**
     * @param copyBarcode  the copy on loan; must not be null
     * @param memberNumber the borrowing member; must not be null
     * @param checkedOutAt when the loan began; must not be null
     * @param dueAt        when the loan falls due, computed by the caller from
     *                     whatever loan period applies; must be strictly after
     *                     {@code checkedOutAt}
     */
    public Loan(Barcode copyBarcode,
                MembershipNumber memberNumber,
                Instant checkedOutAt,
                Instant dueAt) {
        this.copyBarcode = Objects.requireNonNull(copyBarcode, "copyBarcode must not be null");
        this.memberNumber = Objects.requireNonNull(memberNumber, "memberNumber must not be null");
        this.checkedOutAt = Objects.requireNonNull(checkedOutAt, "checkedOutAt must not be null");
        Objects.requireNonNull(dueAt, "dueAt must not be null");
        if (!dueAt.isAfter(checkedOutAt)) {
            throw new IllegalArgumentException(
                    "dueAt (%s) must be strictly after checkedOutAt (%s)"
                            .formatted(dueAt, checkedOutAt));
        }
        this.dueAt = dueAt;
        this.returnedAt = null;
        this.renewalCount = 0;
    }

    /**
     * Records the return of the copy. May be called exactly once.
     *
     * @param returnedAt when the copy came back; must not be null and must
     *                   not precede {@code checkedOutAt}
     * @throws IllegalStateException    if the loan has already been returned
     * @throws IllegalArgumentException if {@code returnedAt} precedes
     *                                  {@code checkedOutAt}
     */
    public void markReturned(Instant returnedAt) {
        Objects.requireNonNull(returnedAt, "returnedAt must not be null");
        if (this.returnedAt != null) {
            throw new IllegalStateException(
                    "loan was already returned at " + this.returnedAt);
        }
        if (returnedAt.isBefore(checkedOutAt)) {
            throw new IllegalArgumentException(
                    "returnedAt (%s) cannot precede checkedOutAt (%s)"
                            .formatted(returnedAt, checkedOutAt));
        }
        this.returnedAt = returnedAt;
    }

    /**
     * Records one renewal. Kept deliberately minimal: this class counts
     * renewals but does not extend {@code dueAt}, because the stated rule is
     * that {@code dueAt} is fixed at construction. If renewal is meant to
     * move the due date, model that as a new Loan created by the caller.
     *
     * @throws IllegalStateException if the loan has already been returned
     */
    public void recordRenewal() {
        if (returnedAt != null) {
            throw new IllegalStateException("cannot renew a returned loan");
        }
        renewalCount++;
    }

    public Barcode copyBarcode() {
        return copyBarcode;
    }

    public MembershipNumber memberNumber() {
        return memberNumber;
    }

    public Instant checkedOutAt() {
        return checkedOutAt;
    }

    public Instant dueAt() {
        return dueAt;
    }

    /** Empty until {@link #markReturned(Instant)} has been called. */
    public Optional<Instant> returnedAt() {
        return Optional.ofNullable(returnedAt);
    }

    public int renewalCount() {
        return renewalCount;
    }

    public boolean isReturned() {
        return returnedAt != null;
    }

    public boolean isOverdueAt(Instant asOf) {
        Objects.requireNonNull(asOf, "asOf must not be null");
        return returnedAt == null && asOf.isAfter(dueAt);
    }
}
```

Design notes:

- The class is `final` with all identity and time-of-checkout state in `final` fields; `returnedAt` and `renewalCount` are the only mutable fields, and each mutates only through its single intent-revealing method.
- `returnedAt()` returns `Optional<Instant>` rather than a nullable `Instant`, so callers cannot forget that a live loan has no return time.
- `dueAt` is a required constructor argument with no default and no overload that omits it, which is how "the loan period is supplied by the caller, never assumed" is made structural rather than a comment.
- I added `recordRenewal` because the intent mentions a renewal count, but it does not touch `dueAt` — the stated rules say `dueAt` is fixed at construction. If your renewal policy extends the due date, that conflicts with the fixed-`dueAt` rule and should be resolved in the brief, not silently in code.

Rules stated above that this implementation cannot enforce by itself:

1. **"The loan period is supplied by the caller, never assumed."** The class can force the caller to pass `dueAt` and can check it is after `checkedOutAt`, but it cannot verify that the value reflects the library's actual loan policy. A caller that hardcodes `checkedOutAt.plus(Duration.ofDays(21))` everywhere has assumed a period; the class cannot see that.
2. **The truthfulness of the timestamps.** The class checks ordering (`dueAt` after `checkedOutAt`, `returnedAt` not before `checkedOutAt`), but it cannot know whether any of these instants correspond to when events actually happened. A caller passing a fabricated or clock-skewed `returnedAt` satisfies every check.
3. **That the `Barcode` and `MembershipNumber` refer to a real copy and a real, eligible member.** They are validated as well-formed values on construction (by their own classes), but existence, membership status, and borrowing eligibility are repository/service concerns outside this class.
4. **That one copy has at most one active loan.** Nothing stops two `Loan` instances being constructed for the same barcode with overlapping periods; that uniqueness rule lives wherever loans are created and persisted, not inside a single `Loan` object.

Everything else in the stated rules — due date fixed and strictly after checkout, no setters, set-once return through `markReturned`, return not preceding checkout, validated constructor arguments — is enforced directly by the class.
