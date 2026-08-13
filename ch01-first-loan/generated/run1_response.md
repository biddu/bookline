# ch01-first-loan — run 1, verbatim (this is Exhibit 1A in the book)

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim): "Write a Java class to represent a book loan in a library system."
- Edits made: none.

---

Here's a solid `BookLoan` class. I've kept it framework-agnostic (plain Java), but structured it the way you'd want it before wiring in JPA or persistence.

```java
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents the loan of a book copy to a library member.
 *
 * A loan is created when a book is checked out and closed when it is
 * returned. Overdue status and fines are derived from the due date
 * rather than stored, so they can never go stale.
 */
public class BookLoan {

    /** Default loan period in days. */
    public static final int DEFAULT_LOAN_PERIOD_DAYS = 14;

    /** Fine charged per day overdue. */
    public static final double DAILY_FINE = 0.50;

    public enum Status {
        ACTIVE,
        RETURNED,
        LOST
    }

    private final UUID id;
    private final String bookIsbn;
    private final String memberId;
    private final LocalDate checkoutDate;
    private LocalDate dueDate;
    private LocalDate returnDate;   // null while the loan is open
    private Status status;
    private int renewalCount;

    public BookLoan(String bookIsbn, String memberId, LocalDate checkoutDate) {
        this(bookIsbn, memberId, checkoutDate,
             checkoutDate.plusDays(DEFAULT_LOAN_PERIOD_DAYS));
    }

    public BookLoan(String bookIsbn, String memberId,
                    LocalDate checkoutDate, LocalDate dueDate) {
        this.bookIsbn = Objects.requireNonNull(bookIsbn, "bookIsbn must not be null");
        this.memberId = Objects.requireNonNull(memberId, "memberId must not be null");
        this.checkoutDate = Objects.requireNonNull(checkoutDate, "checkoutDate must not be null");
        Objects.requireNonNull(dueDate, "dueDate must not be null");
        if (dueDate.isBefore(checkoutDate)) {
            throw new IllegalArgumentException("dueDate cannot be before checkoutDate");
        }
        this.id = UUID.randomUUID();
        this.dueDate = dueDate;
        this.status = Status.ACTIVE;
        this.renewalCount = 0;
    }

    // ---- Behaviour -------------------------------------------------------

    /** Marks the book as returned on the given date and closes the loan. */
    public void returnBook(LocalDate returnDate) {
        Objects.requireNonNull(returnDate, "returnDate must not be null");
        requireActive();
        if (returnDate.isBefore(checkoutDate)) {
            throw new IllegalArgumentException("returnDate cannot be before checkoutDate");
        }
        this.returnDate = returnDate;
        this.status = Status.RETURNED;
    }

    /** Extends the due date by the standard loan period. */
    public void renew() {
        requireActive();
        this.dueDate = this.dueDate.plusDays(DEFAULT_LOAN_PERIOD_DAYS);
        this.renewalCount++;
    }

    /** Marks the book as lost and closes the loan. */
    public void markLost() {
        requireActive();
        this.status = Status.LOST;
    }

    /** True if the loan is still open and past its due date as of {@code asOf}. */
    public boolean isOverdue(LocalDate asOf) {
        return status == Status.ACTIVE && asOf.isAfter(dueDate);
    }

    /**
     * Fine accrued as of {@code asOf}. For a returned loan, the fine is
     * based on the actual return date; for an open loan, on {@code asOf}.
     */
    public double calculateFine(LocalDate asOf) {
        LocalDate effectiveEnd = (returnDate != null) ? returnDate : asOf;
        long daysOverdue = java.time.temporal.ChronoUnit.DAYS.between(dueDate, effectiveEnd);
        return daysOverdue > 0 ? daysOverdue * DAILY_FINE : 0.0;
    }

    private void requireActive() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException(
                "Operation not allowed: loan is " + status);
        }
    }

    // ---- Getters ---------------------------------------------------------

    public UUID getId()               { return id; }
    public String getBookIsbn()       { return bookIsbn; }
    public String getMemberId()       { return memberId; }
    public LocalDate getCheckoutDate(){ return checkoutDate; }
    public LocalDate getDueDate()     { return dueDate; }
    public LocalDate getReturnDate()  { return returnDate; }
    public Status getStatus()         { return status; }
    public int getRenewalCount()      { return renewalCount; }

    // ---- Object contract -------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BookLoan other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "BookLoan{id=%s, isbn=%s, member=%s, due=%s, status=%s}"
                .formatted(id, bookIsbn, memberId, dueDate, status);
    }
}
```

A few design decisions worth calling out:

1. **Overdue status is computed, not stored.** `isOverdue()` and `calculateFine()` take an `asOf` date instead of calling `LocalDate.now()` internally. This makes the class trivially testable (no clock mocking) and means the state can never be stale.

2. **References by ID, not object.** I used `bookIsbn` and `memberId` strings rather than `Book` and `Member` objects to keep the class self-contained. If you have those domain classes, swap the fields to direct references — or keep IDs if this will back a JPA entity and you want to avoid eager-loading chains.

3. **State transitions are guarded.** You can't return or renew a loan that's already `RETURNED` or `LOST` — `requireActive()` throws `IllegalStateException`. Invalid dates (return before checkout, due before checkout) throw `IllegalArgumentException` at the boundary, so a `BookLoan` can never exist in a nonsensical state.

4. **`double` for money is a shortcut.** Fine for an exercise, but if this is going to production, use `BigDecimal` (or a `Money` type / cents as `long`) for the fine calculation to avoid floating-point rounding issues.

5. **Identity-based equality.** `equals`/`hashCode` use only the immutable `id`, which is the right call for a mutable entity — two loans are the same loan iff they share an ID, regardless of current state.

If you're targeting JPA/Hibernate, the changes are small: add `@Entity`, `@Id` on `id`, `@Enumerated(EnumType.STRING)` on `status`, a protected no-arg constructor, and drop the `final` on the fields JPA needs to set. Happy to sketch that version if you want it.
