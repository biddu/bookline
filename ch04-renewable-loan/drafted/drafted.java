// drafted illustrative code, written to contain the exact defects the prose dissects

// In Loan.java the generated diff also changes:
//     private final LocalDate dueAt;
// to:
//     protected LocalDate dueAt;

public class RenewableLoan extends Loan {

    private int renewalCount = 0;
    private static final int MAX_RENEWALS = 2;

    public void setDueDate(LocalDate newDueDate) {
        this.dueAt = newDueDate;
    }

    public void renew() {
        if (renewalCount < MAX_RENEWALS) {
            setDueDate(this.dueAt.plusWeeks(3));
            renewalCount++;
        }
    }
}
