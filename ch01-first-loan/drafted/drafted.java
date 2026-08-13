import java.time.LocalDateTime;

/**
 * Represents a loan of a library book to a member.
 */
public class Loan {

    public String copyId;
    public String memberId;
    public LocalDateTime checkedOutAt;
    public LocalDateTime dueAt;
    public LocalDateTime returnedAt;
    public int renewalCount;

    public Loan(String copyId, String memberId) {
        this.copyId = copyId;
        this.memberId = memberId;
        this.checkedOutAt = LocalDateTime.now();
        // Standard loan period is 14 days
        this.dueAt = this.checkedOutAt.plusDays(14);
        this.renewalCount = 0;
    }

    /**
     * Renews the loan, extending the due date by another 14 days.
     */
    public void renew() {
        this.dueAt = this.dueAt.plusDays(14);
        this.renewalCount++;
    }

    /**
     * Marks the loan as returned.
     */
    public void returnBook() {
        this.returnedAt = LocalDateTime.now();
    }

    /**
     * Checks whether the loan is currently overdue.
     */
    public boolean isOverdue() {
        return returnedAt == null && LocalDateTime.now().isAfter(dueAt);
    }
}
