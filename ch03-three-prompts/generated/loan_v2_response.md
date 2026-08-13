Here's a `Loan` class for your library lending system.

```java
import java.time.LocalDate;
import java.util.Objects;

/**
 * A loan of one book copy to one library member.
 *
 * A Loan is created at checkout and lives until the copy is returned.
 * The identity of the loan (which copy, which member, when it was
 * checked out) never changes, so those fields are final. The state
 * that does change over the life of the loan (due date, returned flag,
 * renewal count) changes only through the {@link #renew()} and
 * {@link #markReturned()} operations, not through setters.
 */
public class Loan {

    /** How much each renewal extends the due date. */
    private static final int RENEWAL_EXTENSION_DAYS = 14;

    /** Maximum number of times a single loan may be renewed. */
    private static final int MAX_RENEWALS = 3;

    private final String copyBarcode;
    private final String membershipNumber;
    private final LocalDate checkedOutOn;

    private LocalDate dueOn;
    private boolean returned;
    private int renewalCount;

    /**
     * Creates a new loan at checkout time.
     *
     * @param copyBarcode      barcode identifying the physical copy
     * @param membershipNumber the borrowing member's membership number
     * @param checkedOutOn     the date the copy was checked out
     * @param dueOn            the date the copy is due back
     */
    public Loan(String copyBarcode,
                String membershipNumber,
                LocalDate checkedOutOn,
                LocalDate dueOn) {
        this.copyBarcode = requireNonBlank(copyBarcode, "copyBarcode");
        this.membershipNumber = requireNonBlank(membershipNumber, "membershipNumber");
        this.checkedOutOn = Objects.requireNonNull(checkedOutOn, "checkedOutOn must not be null");
        this.dueOn = Objects.requireNonNull(dueOn, "dueOn must not be null");
        if (dueOn.isBefore(checkedOutOn)) {
            throw new IllegalArgumentException(
                    "due date " + dueOn + " is before checkout date " + checkedOutOn);
        }
        this.returned = false;
        this.renewalCount = 0;
    }

    // ---- queries ----------------------------------------------------------

    public String getCopyBarcode() {
        return copyBarcode;
    }

    public String getMembershipNumber() {
        return membershipNumber;
    }

    public LocalDate getCheckedOutOn() {
        return checkedOutOn;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public boolean isReturned() {
        return returned;
    }

    public int getRenewalCount() {
        return renewalCount;
    }

    /** True if the loan is still open and past its due date on the given date. */
    public boolean isOverdue(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf must not be null");
        return !returned && asOf.isAfter(dueOn);
    }

    // ---- state changes ----------------------------------------------------

    /**
     * Renews the loan, extending the due date by {@value #RENEWAL_EXTENSION_DAYS}
     * days from the current due date.
     *
     * @throws IllegalStateException if the loan is already returned or has
     *                               reached the renewal limit
     */
    public void renew() {
        if (returned) {
            throw new IllegalStateException("cannot renew a returned loan");
        }
        if (renewalCount >= MAX_RENEWALS) {
            throw new IllegalStateException(
                    "loan has already been renewed " + MAX_RENEWALS + " times");
        }
        dueOn = dueOn.plusDays(RENEWAL_EXTENSION_DAYS);
        renewalCount++;
    }

    /**
     * Marks the loan as returned. Calling this twice is treated as a
     * programming error rather than silently ignored.
     *
     * @throws IllegalStateException if the loan is already returned
     */
    public void markReturned() {
        if (returned) {
            throw new IllegalStateException("loan is already returned");
        }
        returned = true;
    }

    // ---- helpers ----------------------------------------------------------

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @Override
    public String toString() {
        return "Loan{copy=" + copyBarcode
                + ", member=" + membershipNumber
                + ", checkedOut=" + checkedOutOn
                + ", due=" + dueOn
                + ", returned=" + returned
                + ", renewals=" + renewalCount + '}';
    }
}
```

A few design decisions worth flagging:

1. **No setters.** The barcode, membership number, and checkout date are `final` — a loan can't be quietly repointed at a different copy or member. The mutable state (due date, returned flag, renewal count) changes only through `renew()` and `markReturned()`, which enforce the rules: you can't renew a returned loan, you can't renew past the limit, and you can't return the same loan twice. A bare `setDueOn(...)` or `setReturned(boolean)` would let callers bypass all of that.

2. **Dates are `java.time.LocalDate`.** Library due dates are calendar dates, not instants — a book is due "on March 3rd," not "at 14:37:22 UTC." If your system needs to record the exact checkout moment (e.g., for same-day holds), change `checkedOutOn` to `Instant` and keep `dueOn` as `LocalDate`.

3. **Values I invented — check these against your library's actual policy:**
   - `RENEWAL_EXTENSION_DAYS = 14`: each renewal adds two weeks to the current due date.
   - `MAX_RENEWALS = 3`: a loan can be renewed at most three times.

   Your requirements didn't specify either. If loan policy varies by member type or item type, these shouldn't be constants at all — pass them into the constructor or, better, delegate to a `LoanPolicy` object.

4. **The constructor takes the due date rather than computing it.** Deciding the loan period (14 days? 21? shorter for new releases?) is policy, and policy tends to vary; the `Loan` itself just records the agreement. If you'd rather have `Loan` compute it, add a factory method like `Loan.checkOut(barcode, member, LocalDate today, Period loanPeriod)`.

5. **Barcode and membership number are `String`s.** That's fine to start, but if you find yourself validating their formats in several places, promote them to small value types (`Barcode`, `MembershipNumber`) so the compiler stops you from passing one where the other is expected.
