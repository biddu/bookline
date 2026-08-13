# ch01-first-loan — run 3, verbatim

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim, identical to run 1): "Write a Java class to represent a book loan in a library system."
- Edits made: none.
- **Note: this is the only run of five that modelled the loan against a physical copy rather than an ISBN, and it said so explicitly. It also invented a renewal cap of 2.**

---

Here's a `BookLoan` class I'd suggest for the library system. I'll walk through the design decisions after the code.

```java
package com.library.loans;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a loan of a single book copy to a library member.
 *
 * A loan is created when a book is checked out and is closed when the
 * book is returned. Overdue status and fines are derived from the due
 * date rather than stored, so they can never go stale.
 */
public class BookLoan {

    /** Possible lifecycle states of a loan. */
    public enum Status {
        ACTIVE,
        RETURNED,
        LOST
    }

    private static final int DEFAULT_LOAN_PERIOD_DAYS = 14;
    private static final int MAX_RENEWALS = 2;

    private final UUID loanId;
    private final String bookCopyId;   // identifies the physical copy, not just the ISBN
    private final String memberId;
    private final LocalDate checkoutDate;

    private LocalDate dueDate;
    private LocalDate returnDate;      // null until the book comes back
    private Status status;
    private int renewalCount;

    /**
     * Creates a new active loan with the default loan period.
     */
    public BookLoan(String bookCopyId, String memberId, LocalDate checkoutDate) {
        this(bookCopyId, memberId, checkoutDate,
             checkoutDate.plusDays(DEFAULT_LOAN_PERIOD_DAYS));
    }

    /**
     * Creates a new active loan with an explicit due date (e.g. shorter
     * periods for high-demand titles).
     */
    public BookLoan(String bookCopyId, String memberId,
                    LocalDate checkoutDate, LocalDate dueDate) {
        this.bookCopyId = requireNonBlank(bookCopyId, "bookCopyId");
        this.memberId = requireNonBlank(memberId, "memberId");
        this.checkoutDate = Objects.requireNonNull(checkoutDate, "checkoutDate must not be null");
        Objects.requireNonNull(dueDate, "dueDate must not be null");
        if (dueDate.isBefore(checkoutDate)) {
            throw new IllegalArgumentException(
                "dueDate " + dueDate + " must not be before checkoutDate " + checkoutDate);
        }
        this.loanId = UUID.randomUUID();
        this.dueDate = dueDate;
        this.status = Status.ACTIVE;
        this.renewalCount = 0;
    }

    // ---------------------------------------------------------------
    // Behaviour
    // ---------------------------------------------------------------

    /**
     * Marks the book as returned on the given date.
     *
     * @throws IllegalStateException if the loan is not active
     */
    public void returnBook(LocalDate returnDate) {
        Objects.requireNonNull(returnDate, "returnDate must not be null");
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Cannot return a loan with status " + status);
        }
        if (returnDate.isBefore(checkoutDate)) {
            throw new IllegalArgumentException("returnDate cannot be before checkoutDate");
        }
        this.returnDate = returnDate;
        this.status = Status.RETURNED;
    }

    /**
     * Extends the due date by the default loan period.
     *
     * @throws IllegalStateException if the loan is not active, is already
     *         overdue, or has reached the renewal limit
     */
    public void renew(LocalDate today) {
        Objects.requireNonNull(today, "today must not be null");
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Cannot renew a loan with status " + status);
        }
        if (isOverdue(today)) {
            throw new IllegalStateException("Cannot renew an overdue loan");
        }
        if (renewalCount >= MAX_RENEWALS) {
            throw new IllegalStateException("Renewal limit of " + MAX_RENEWALS + " reached");
        }
        this.dueDate = dueDate.plusDays(DEFAULT_LOAN_PERIOD_DAYS);
        this.renewalCount++;
    }

    /** Marks the book as lost, closing the loan. */
    public void markLost() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Cannot mark a loan as lost with status " + status);
        }
        this.status = Status.LOST;
    }

    /** An active loan is overdue once today is past the due date. */
    public boolean isOverdue(LocalDate today) {
        Objects.requireNonNull(today, "today must not be null");
        return status == Status.ACTIVE && today.isAfter(dueDate);
    }

    /**
     * Days past the due date as of the given date (or as of the return
     * date, if the book has been returned late). Zero if not overdue.
     */
    public long overdueDays(LocalDate today) {
        Objects.requireNonNull(today, "today must not be null");
        LocalDate effectiveEnd = (returnDate != null) ? returnDate : today;
        if (!effectiveEnd.isAfter(dueDate)) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(dueDate, effectiveEnd);
    }

    // ---------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------

    public UUID getLoanId()            { return loanId; }
    public String getBookCopyId()      { return bookCopyId; }
    public String getMemberId()        { return memberId; }
    public LocalDate getCheckoutDate() { return checkoutDate; }
    public LocalDate getDueDate()      { return dueDate; }
    public Status getStatus()          { return status; }
    public int getRenewalCount()       { return renewalCount; }

    /** The return date, or null if the book has not been returned. */
    public LocalDate getReturnDate()   { return returnDate; }

    // ---------------------------------------------------------------
    // Object contract
    // ---------------------------------------------------------------

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
        return "BookLoan{loanId=" + loanId
             + ", bookCopyId='" + bookCopyId + '\''
             + ", memberId='" + memberId + '\''
             + ", checkoutDate=" + checkoutDate
             + ", dueDate=" + dueDate
             + ", returnDate=" + returnDate
             + ", status=" + status
             + ", renewals=" + renewalCount
             + '}';
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
        return value;
    }
}
```

A few design notes on the choices I made:

1. **Rich domain object, not a bag of getters/setters.** State transitions go through `returnBook`, `renew`, and `markLost`, which enforce the rules (can't return twice, can't renew an overdue loan, renewal cap). There are deliberately no setters — the invariants can't be violated from outside.

2. **Overdue status is computed, not stored.** `isOverdue` and `overdueDays` take `today` as a parameter instead of calling `LocalDate.now()` internally. That keeps the class deterministic and trivially testable — no clock mocking needed. Same reason I compute overdue days from the return date for late returns: the history stays correct after the fact.

3. **References by ID, not object.** The loan holds `bookCopyId` and `memberId` rather than `Book` and `Member` objects. That keeps it decoupled and maps cleanly onto persistence (JPA, JDBC, whatever you're using). Note it's a *copy* ID, not an ISBN — a library has multiple copies of the same title, and you're loaning a specific physical one.

4. **`LocalDate` over `Date`/`Calendar`.** Loans operate on calendar days; `java.time` is immutable and the legacy date classes shouldn't appear in new code.

5. **Identity-based equality.** Two loans are equal iff they have the same `loanId` — a loan is an entity, not a value. If you move to JPA, you'll want to revisit this (generated IDs and Hibernate proxies make equals/hashCode more subtle), but for an in-memory ID assigned at construction this is correct.

Things you might adjust for your context: if fines matter, I'd put the fine *calculation* in a separate policy class (`FinePolicy.calculate(loan, today)`) rather than inside the loan, since fine rules vary by member type and change over time. And if you're on Java 17+, `Status` could carry behavior, or the whole thing could be split into an immutable record plus a small state machine — but for a mutable entity in a typical CRUD-ish system, the shape above is what I'd start with.
