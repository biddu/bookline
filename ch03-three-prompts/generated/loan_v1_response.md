Here's a Java class representing a library loan:

```java
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a loan of a library item (e.g., a book) to a borrower.
 * Tracks checkout and due dates, renewals, return status, and late fees.
 */
public class Loan {

    /** Standard loan period in days. */
    public static final int LOAN_PERIOD_DAYS = 14;

    /** Maximum number of times a loan may be renewed. */
    public static final int MAX_RENEWALS = 2;

    /** Late fee charged per day overdue, in dollars. */
    public static final double LATE_FEE_PER_DAY = 0.25;

    /** Cap on the total late fee for a single loan, in dollars. */
    public static final double MAX_LATE_FEE = 10.00;

    private final String loanId;
    private final String itemId;
    private final String borrowerId;
    private final LocalDate checkoutDate;
    private LocalDate dueDate;
    private LocalDate returnDate;   // null until the item is returned
    private int renewalCount;

    /**
     * Creates a new loan starting today with the standard loan period.
     *
     * @param itemId     identifier of the item being borrowed
     * @param borrowerId identifier of the borrower
     */
    public Loan(String itemId, String borrowerId) {
        this(itemId, borrowerId, LocalDate.now());
    }

    /**
     * Creates a new loan starting on the given checkout date.
     *
     * @param itemId       identifier of the item being borrowed
     * @param borrowerId   identifier of the borrower
     * @param checkoutDate the date the item was checked out
     */
    public Loan(String itemId, String borrowerId, LocalDate checkoutDate) {
        this.loanId = UUID.randomUUID().toString();
        this.itemId = Objects.requireNonNull(itemId, "itemId must not be null");
        this.borrowerId = Objects.requireNonNull(borrowerId, "borrowerId must not be null");
        this.checkoutDate = Objects.requireNonNull(checkoutDate, "checkoutDate must not be null");
        this.dueDate = checkoutDate.plusDays(LOAN_PERIOD_DAYS);
        this.returnDate = null;
        this.renewalCount = 0;
    }

    /**
     * Renews the loan, extending the due date by the standard loan period.
     *
     * @throws IllegalStateException if the item has already been returned,
     *                               the loan is overdue, or the renewal limit
     *                               has been reached
     */
    public void renew() {
        if (isReturned()) {
            throw new IllegalStateException("Cannot renew: item has already been returned.");
        }
        if (isOverdue()) {
            throw new IllegalStateException("Cannot renew: loan is overdue.");
        }
        if (renewalCount >= MAX_RENEWALS) {
            throw new IllegalStateException(
                "Cannot renew: maximum of " + MAX_RENEWALS + " renewals reached.");
        }
        dueDate = dueDate.plusDays(LOAN_PERIOD_DAYS);
        renewalCount++;
    }

    /**
     * Marks the item as returned today.
     *
     * @throws IllegalStateException if the item has already been returned
     */
    public void returnItem() {
        returnItem(LocalDate.now());
    }

    /**
     * Marks the item as returned on the given date.
     *
     * @param date the return date
     * @throws IllegalStateException    if the item has already been returned
     * @throws IllegalArgumentException if the return date is before checkout
     */
    public void returnItem(LocalDate date) {
        if (isReturned()) {
            throw new IllegalStateException("Item has already been returned.");
        }
        Objects.requireNonNull(date, "return date must not be null");
        if (date.isBefore(checkoutDate)) {
            throw new IllegalArgumentException("Return date cannot be before checkout date.");
        }
        this.returnDate = date;
    }

    /** @return true if the item has been returned */
    public boolean isReturned() {
        return returnDate != null;
    }

    /**
     * @return true if the loan is past its due date (as of today if outstanding,
     *         or as of the return date if returned)
     */
    public boolean isOverdue() {
        LocalDate reference = isReturned() ? returnDate : LocalDate.now();
        return reference.isAfter(dueDate);
    }

    /**
     * @return the number of days the loan is overdue (0 if not overdue)
     */
    public long getDaysOverdue() {
        LocalDate reference = isReturned() ? returnDate : LocalDate.now();
        long days = ChronoUnit.DAYS.between(dueDate, reference);
        return Math.max(0, days);
    }

    /**
     * @return the late fee owed in dollars, capped at {@link #MAX_LATE_FEE}
     */
    public double getLateFee() {
        double fee = getDaysOverdue() * LATE_FEE_PER_DAY;
        return Math.min(fee, MAX_LATE_FEE);
    }

    // --- Getters ---

    public String getLoanId() {
        return loanId;
    }

    public String getItemId() {
        return itemId;
    }

    public String getBorrowerId() {
        return borrowerId;
    }

    public LocalDate getCheckoutDate() {
        return checkoutDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    /** @return the return date, or null if the item is still out */
    public LocalDate getReturnDate() {
        return returnDate;
    }

    public int getRenewalCount() {
        return renewalCount;
    }

    @Override
    public String toString() {
        return "Loan{" +
                "loanId='" + loanId + '\'' +
                ", itemId='" + itemId + '\'' +
                ", borrowerId='" + borrowerId + '\'' +
                ", checkoutDate=" + checkoutDate +
                ", dueDate=" + dueDate +
                ", returnDate=" + (returnDate != null ? returnDate : "not returned") +
                ", renewalCount=" + renewalCount +
                '}';
    }
}
```

**Key features:**

- **Loan lifecycle:** created at checkout with a 14-day loan period, renewable up to 2 times (14 days per renewal), and closed out with `returnItem()`.
- **Overdue tracking:** `isOverdue()` and `getDaysOverdue()` work both while the loan is outstanding (measured against today) and after return (measured against the return date).
- **Late fees:** $0.25 per day overdue, capped at $10.00 per loan.
- **Safety:** null checks on required fields, and guards against invalid state transitions (renewing a returned or overdue loan, returning twice, returning before checkout).
- **Identity:** each loan gets a unique `loanId` via `UUID`.

**Example usage:**

```java
Loan loan = new Loan("book-978-0134685991", "member-42");
System.out.println("Due: " + loan.getDueDate());

loan.renew();                       // extends due date by 14 days
loan.returnItem();                  // returned today
System.out.println("Late fee: $" + loan.getLateFee());
```

A few things you might want to adjust depending on your system: if fees matter for real accounting, use `BigDecimal` (or integer cents) instead of `double`; if the library has different loan periods per item type or member type, pass the period into the constructor instead of using a constant; and if loans are persisted, you may want the ID assigned by the database rather than generated in the constructor.
