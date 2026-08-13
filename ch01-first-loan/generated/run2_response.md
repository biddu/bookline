# ch01-first-loan — run 2, verbatim

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim, identical to run 1): "Write a Java class to represent a book loan in a library system."
- Edits made: none.
- **Note: this is the run that chose a 21-day loan period.**

---

Here's a solid, self-contained `BookLoan` class using `java.time` and defensive validation:

```java
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Represents a single loan of a book to a library member.
 *
 * A loan is created when the book is checked out and is "closed"
 * by calling {@link #returnBook(LocalDate)}.
 */
public class BookLoan {

    /** Default loan period in days. */
    public static final int DEFAULT_LOAN_PERIOD_DAYS = 21;

    private final String loanId;
    private final String bookIsbn;
    private final String memberId;
    private final LocalDate loanDate;
    private LocalDate dueDate;
    private LocalDate returnDate;   // null until the book is returned

    public BookLoan(String bookIsbn, String memberId, LocalDate loanDate) {
        this(bookIsbn, memberId, loanDate, loanDate.plusDays(DEFAULT_LOAN_PERIOD_DAYS));
    }

    public BookLoan(String bookIsbn, String memberId, LocalDate loanDate, LocalDate dueDate) {
        this.loanId = UUID.randomUUID().toString();
        this.bookIsbn = requireNonBlank(bookIsbn, "bookIsbn");
        this.memberId = requireNonBlank(memberId, "memberId");
        this.loanDate = Objects.requireNonNull(loanDate, "loanDate must not be null");
        Objects.requireNonNull(dueDate, "dueDate must not be null");
        if (dueDate.isBefore(loanDate)) {
            throw new IllegalArgumentException("dueDate cannot be before loanDate");
        }
        this.dueDate = dueDate;
    }

    /** Marks the book as returned. May only be called once. */
    public void returnBook(LocalDate returnDate) {
        Objects.requireNonNull(returnDate, "returnDate must not be null");
        if (isReturned()) {
            throw new IllegalStateException("Loan " + loanId + " is already closed");
        }
        if (returnDate.isBefore(loanDate)) {
            throw new IllegalArgumentException("returnDate cannot be before loanDate");
        }
        this.returnDate = returnDate;
    }

    /** Extends the due date, e.g. for a renewal. */
    public void renew(int extraDays) {
        if (isReturned()) {
            throw new IllegalStateException("Cannot renew a closed loan");
        }
        if (extraDays <= 0) {
            throw new IllegalArgumentException("extraDays must be positive");
        }
        this.dueDate = this.dueDate.plusDays(extraDays);
    }

    public boolean isReturned() {
        return returnDate != null;
    }

    /** True if the loan is past due as of the given date (open loans only). */
    public boolean isOverdue(LocalDate asOf) {
        LocalDate effectiveEnd = isReturned() ? returnDate : Objects.requireNonNull(asOf);
        return effectiveEnd.isAfter(dueDate);
    }

    /** Days overdue as of the given date; 0 if not overdue. */
    public long daysOverdue(LocalDate asOf) {
        LocalDate effectiveEnd = isReturned() ? returnDate : Objects.requireNonNull(asOf);
        long days = ChronoUnit.DAYS.between(dueDate, effectiveEnd);
        return Math.max(0, days);
    }

    // --- Getters ---

    public String getLoanId()      { return loanId; }
    public String getBookIsbn()    { return bookIsbn; }
    public String getMemberId()    { return memberId; }
    public LocalDate getLoanDate() { return loanDate; }
    public LocalDate getDueDate()  { return dueDate; }

    public Optional<LocalDate> getReturnDate() {
        return Optional.ofNullable(returnDate);
    }

    // --- Helpers / identity ---

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BookLoan other)) return false;
        return loanId.equals(other.loanId);
    }

    @Override
    public int hashCode() {
        return loanId.hashCode();
    }

    @Override
    public String toString() {
        return "BookLoan{id=%s, isbn=%s, member=%s, loaned=%s, due=%s, returned=%s}"
                .formatted(loanId, bookIsbn, memberId, loanDate, dueDate,
                           returnDate == null ? "no" : returnDate);
    }
}
```

A few design decisions worth flagging:

1. **IDs instead of object references.** I used `bookIsbn` and `memberId` (strings) rather than `Book` and `Member` objects. That keeps the class decoupled and maps cleanly onto a database schema. If your domain model already has `Book`/`Member` entities and you're doing in-memory object graphs (or JPA relationships), swap them in — the rest of the class doesn't change.

2. **State via nullable `returnDate` rather than a status enum.** "Returned or not" is fully derivable from whether `returnDate` is set, so a separate `LoanStatus` field would just be something to keep in sync. Note that `isOverdue()` is *computed*, not stored — storing an `OVERDUE` status is a classic bug source because it goes stale at midnight. The getter exposes it as `Optional<LocalDate>` so callers can't forget the null case.

3. **Dates passed in, not `LocalDate.now()`.** Methods like `isOverdue(LocalDate asOf)` take the clock as a parameter. This makes the class trivially unit-testable and timezone-safe. If you'd rather have no-arg convenience versions, add them as overloads that delegate — or better, inject a `java.time.Clock`.

4. **Invariants enforced in constructor and mutators.** You can't create a loan due before it started, return a book twice, or renew a closed loan. Illegal states throw immediately rather than corrupting data quietly.

5. **Equality on `loanId` only.** It's an entity, not a value object, so identity is the ID. Don't include mutable fields in `equals`/`hashCode` or the object will misbehave in hash-based collections after a renewal.

Things I deliberately left out that you may want depending on scope: fine calculation (I'd put that in a `FineCalculator` service, not the entity — fine policy varies and doesn't belong in loan state), persistence annotations (`@Entity`, `@Id` if JPA — note JPA also needs a protected no-arg constructor and non-final fields), and a maximum-renewals limit (easy to add as a counter checked in `renew`).

If you're on Java 17+ and this is a pure data carrier in your context (e.g., a DTO for an API response), a `record` would be the leaner choice — but since a loan has a lifecycle (checkout → renew → return), a class with controlled mutation like the above is the better fit for the domain model.
