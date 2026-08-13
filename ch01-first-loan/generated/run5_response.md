# ch01-first-loan — run 5, verbatim

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim, identical to run 1): "Write a Java class to represent a book loan in a library system."
- Edits made: none.
- **Note: second of two runs to invent a daily fine rate of 0.50 in an unnamed currency. Also declares an `OVERDUE` enum constant and then computes it from `LocalDate.now()` inside the entity.**

---

Here's a solid implementation of a `BookLoan` class, along with the design reasoning.

```java
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a single loan of a book to a library member.
 *
 * A loan is created when a book is checked out and closed when it is
 * returned. Overdue status and fines are derived from the due date
 * rather than stored, so they can never go stale.
 */
public class BookLoan {

    /** Status of the loan lifecycle. */
    public enum Status {
        ACTIVE,
        RETURNED,
        OVERDUE   // derived convenience value, see getStatus()
    }

    private static final int DEFAULT_LOAN_PERIOD_DAYS = 14;
    private static final double DAILY_FINE = 0.50;

    private final String loanId;
    private final String bookIsbn;
    private final String memberId;
    private final LocalDate checkoutDate;
    private LocalDate dueDate;
    private LocalDate returnDate;   // null until the book is returned

    /**
     * Creates a new active loan with the default loan period.
     */
    public BookLoan(String bookIsbn, String memberId, LocalDate checkoutDate) {
        this(bookIsbn, memberId, checkoutDate,
             checkoutDate == null ? null : checkoutDate.plusDays(DEFAULT_LOAN_PERIOD_DAYS));
    }

    /**
     * Creates a new active loan with an explicit due date.
     */
    public BookLoan(String bookIsbn, String memberId, LocalDate checkoutDate, LocalDate dueDate) {
        this.loanId = UUID.randomUUID().toString();
        this.bookIsbn = Objects.requireNonNull(bookIsbn, "bookIsbn must not be null");
        this.memberId = Objects.requireNonNull(memberId, "memberId must not be null");
        this.checkoutDate = Objects.requireNonNull(checkoutDate, "checkoutDate must not be null");
        this.dueDate = Objects.requireNonNull(dueDate, "dueDate must not be null");
        if (dueDate.isBefore(checkoutDate)) {
            throw new IllegalArgumentException("dueDate cannot be before checkoutDate");
        }
    }

    // ---- Behaviour -------------------------------------------------------

    /**
     * Marks the book as returned on the given date.
     *
     * @throws IllegalStateException if the loan is already closed
     */
    public void returnBook(LocalDate returnDate) {
        Objects.requireNonNull(returnDate, "returnDate must not be null");
        if (this.returnDate != null) {
            throw new IllegalStateException("Loan " + loanId + " is already closed");
        }
        if (returnDate.isBefore(checkoutDate)) {
            throw new IllegalArgumentException("returnDate cannot be before checkoutDate");
        }
        this.returnDate = returnDate;
    }

    /**
     * Extends the due date, e.g. for a renewal.
     *
     * @throws IllegalStateException if the loan is already closed
     */
    public void renew(int extraDays) {
        if (returnDate != null) {
            throw new IllegalStateException("Cannot renew a closed loan");
        }
        if (extraDays <= 0) {
            throw new IllegalArgumentException("extraDays must be positive");
        }
        this.dueDate = this.dueDate.plusDays(extraDays);
    }

    /** True if the book has not been returned and the due date has passed. */
    public boolean isOverdue(LocalDate asOf) {
        return returnDate == null && asOf.isAfter(dueDate);
    }

    /**
     * Fine owed as of the given date. Zero if the loan is not overdue.
     * If the book was returned late, the fine is based on the return date.
     */
    public double calculateFine(LocalDate asOf) {
        LocalDate effectiveEnd = (returnDate != null) ? returnDate : asOf;
        long daysLate = ChronoUnit.DAYS.between(dueDate, effectiveEnd);
        return daysLate > 0 ? daysLate * DAILY_FINE : 0.0;
    }

    /** Current lifecycle status, derived rather than stored. */
    public Status getStatus() {
        if (returnDate != null) {
            return Status.RETURNED;
        }
        return isOverdue(LocalDate.now()) ? Status.OVERDUE : Status.ACTIVE;
    }

    // ---- Getters ---------------------------------------------------------

    public String getLoanId()        { return loanId; }
    public String getBookIsbn()      { return bookIsbn; }
    public String getMemberId()      { return memberId; }
    public LocalDate getCheckoutDate() { return checkoutDate; }
    public LocalDate getDueDate()    { return dueDate; }

    /** Return date, or null if the book is still out. */
    public LocalDate getReturnDate() { return returnDate; }

    // ---- Object contract -------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BookLoan)) return false;
        BookLoan other = (BookLoan) o;
        return loanId.equals(other.loanId);
    }

    @Override
    public int hashCode() {
        return loanId.hashCode();
    }

    @Override
    public String toString() {
        return "BookLoan{id=" + loanId
                + ", isbn=" + bookIsbn
                + ", member=" + memberId
                + ", checkedOut=" + checkoutDate
                + ", due=" + dueDate
                + ", returned=" + returnDate
                + ", status=" + getStatus() + "}";
    }
}
```

A few design decisions worth calling out:

1. **IDs instead of object references.** I used `bookIsbn` and `memberId` strings rather than `Book` and `Member` objects. That keeps the class self-contained for this example and mirrors how you'd model it with a relational database anyway. If you already have `Book`/`Member` domain classes and want rich navigation, swap the fields — the rest of the class doesn't change.

2. **Derived state over stored state.** Overdue status and fines are *computed* from `dueDate`/`returnDate` rather than stored in a field. Stored flags like `boolean overdue` go stale the moment midnight passes; deriving them makes that bug impossible.

3. **`LocalDate`, not `Date`.** Always use `java.time` for new code. `LocalDate` is the right granularity for library loans — you almost certainly don't care about the hour of checkout, and it sidesteps time-zone headaches entirely.

4. **Guarded state transitions.** `returnBook` and `renew` throw `IllegalStateException` on a closed loan, so the object can't get into a nonsense state (returned twice, renewed after return). Constructor validation with `Objects.requireNonNull` fails fast at creation.

5. **Identity via `loanId`.** `equals`/`hashCode` are based only on the generated UUID, since two loans of the same book to the same member on the same day are still distinct loans.

Two things I deliberately kept simple that you'd change in production:

- **Money as `double`** is fine for an exercise but use `BigDecimal` (or an integer cent count / a `Money` type) in real billing code — floating point accumulates rounding errors.
- **Fine policy is hardcoded.** If fine rules vary by member type or item type, extract a `FinePolicy` interface and inject it instead of the `DAILY_FINE` constant.

If you're on Java 17+ and the loan were purely a data carrier (no lifecycle), a `record` would be a good fit — but since a loan mutates (return, renew), a class with controlled transitions is the better model here.
