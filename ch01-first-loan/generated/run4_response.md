# ch01-first-loan — run 4, verbatim

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim, identical to run 1): "Write a Java class to represent a book loan in a library system."
- Edits made: none.
- **Note: this run put `LocalDate.now()` back inside the entity, in `getStatus()` and `getDaysOverdue()`, while its own design note claims status is derived so it "can never go stale".**

---

Here's a `BookLoan` class I'd suggest for a library system:

```java
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a loan of a book to a library member.
 * Tracks the loan period, due date, and return status.
 */
public class BookLoan {

    public enum Status {
        ACTIVE,
        RETURNED,
        OVERDUE
    }

    private static final int DEFAULT_LOAN_PERIOD_DAYS = 14;

    private final String loanId;
    private final String bookIsbn;
    private final String memberId;
    private final LocalDate loanDate;
    private LocalDate dueDate;
    private LocalDate returnDate;   // null until the book is returned

    public BookLoan(String bookIsbn, String memberId) {
        this(bookIsbn, memberId, LocalDate.now(), DEFAULT_LOAN_PERIOD_DAYS);
    }

    public BookLoan(String bookIsbn, String memberId, LocalDate loanDate, int loanPeriodDays) {
        if (loanPeriodDays <= 0) {
            throw new IllegalArgumentException("Loan period must be positive, got: " + loanPeriodDays);
        }
        this.loanId = UUID.randomUUID().toString();
        this.bookIsbn = Objects.requireNonNull(bookIsbn, "bookIsbn must not be null");
        this.memberId = Objects.requireNonNull(memberId, "memberId must not be null");
        this.loanDate = Objects.requireNonNull(loanDate, "loanDate must not be null");
        this.dueDate = loanDate.plusDays(loanPeriodDays);
    }

    /** Marks the book as returned today. */
    public void returnBook() {
        returnBook(LocalDate.now());
    }

    /** Marks the book as returned on the given date. */
    public void returnBook(LocalDate returnDate) {
        if (this.returnDate != null) {
            throw new IllegalStateException("Book has already been returned on " + this.returnDate);
        }
        Objects.requireNonNull(returnDate, "returnDate must not be null");
        if (returnDate.isBefore(loanDate)) {
            throw new IllegalArgumentException("Return date cannot be before loan date");
        }
        this.returnDate = returnDate;
    }

    /** Extends the due date by the given number of days. */
    public void renew(int extraDays) {
        if (returnDate != null) {
            throw new IllegalStateException("Cannot renew a returned loan");
        }
        if (extraDays <= 0) {
            throw new IllegalArgumentException("Extension must be positive, got: " + extraDays);
        }
        this.dueDate = this.dueDate.plusDays(extraDays);
    }

    public Status getStatus() {
        if (returnDate != null) {
            return Status.RETURNED;
        }
        return LocalDate.now().isAfter(dueDate) ? Status.OVERDUE : Status.ACTIVE;
    }

    public boolean isOverdue() {
        return getStatus() == Status.OVERDUE;
    }

    /** Days past the due date as of today (0 if not overdue). */
    public long getDaysOverdue() {
        LocalDate effectiveEnd = (returnDate != null) ? returnDate : LocalDate.now();
        long days = java.time.temporal.ChronoUnit.DAYS.between(dueDate, effectiveEnd);
        return Math.max(0, days);
    }

    // --- Getters ---

    public String getLoanId()      { return loanId; }
    public String getBookIsbn()    { return bookIsbn; }
    public String getMemberId()    { return memberId; }
    public LocalDate getLoanDate() { return loanDate; }
    public LocalDate getDueDate()  { return dueDate; }

    /** May be null if the book has not been returned yet. */
    public LocalDate getReturnDate() { return returnDate; }

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
                + ", loaned=" + loanDate
                + ", due=" + dueDate
                + ", status=" + getStatus()
                + "}";
    }
}
```

A few design notes on the choices I made:

- **References by ID, not object.** I used `bookIsbn` and `memberId` strings rather than holding `Book` and `Member` objects. In a real system the loan is usually an association record, and referencing by ID keeps it decoupled and easy to persist. If you already have `Book`/`Member` domain classes and want direct references, swapping them in is trivial.
- **`LocalDate` over `Date`.** Always prefer `java.time` — it's immutable and has no time-zone footguns for a date-only concept like a due date.
- **Status is derived, not stored.** `ACTIVE` vs `OVERDUE` is computed from the due date at call time, so it can never go stale. Only the return date is stored state.
- **Invariants enforced in the class.** You can't return a book twice, return it before it was loaned, or renew a completed loan. Keeping these rules inside the entity means callers can't put it in a nonsense state.
- **Identity-based equality.** `equals`/`hashCode` use only `loanId`, which is the right semantics for an entity (two loans with the same book and member on the same day are still distinct loans).

One thing deliberately left out: fine calculation. I'd put that in a separate `FineCalculator` service rather than in the entity, since fine policy (rates, grace periods, caps) tends to change independently of the loan data itself — but `getDaysOverdue()` gives that service what it needs.

If you're on Java 17+ and this is purely a persistence/DTO layer, you could also consider a `record` plus a small service class, but since a loan has a lifecycle (return, renew), a mutable entity like this is usually the better fit.
