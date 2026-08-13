# Prompt Pair 1A — chat-tool arm, verbatim

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim): "Our library system should allow a short grace period after a loan's due date before the loan counts as overdue. Can you add support for this to our Loan class?" — followed by the same `Loan.java` given to the completion arm.
- Edits made: none.

---

Here's how I'd add it. The key design point: once you have a grace period, "past the due date" and "overdue" become two different concepts, and it's worth exposing both — reminder emails care about the due date, fines care about overdue status.

```java
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Represents a loan of a library book to a member.
 *
 * A loan that is past its due date is not immediately considered overdue:
 * members get a short grace period after the due date before the loan
 * counts as overdue (e.g. for fines or borrowing blocks).
 */
public class Loan {

    /** Grace period after the due date before a loan counts as overdue. */
    public static final Duration GRACE_PERIOD = Duration.ofDays(2);

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

    public void renew() {
        this.dueAt = this.dueAt.plusDays(14);
        this.renewalCount++;
    }

    public void returnBook() {
        this.returnedAt = LocalDateTime.now();
    }

    /**
     * The moment the grace period ends. At or after this time, an
     * unreturned loan is overdue.
     */
    public LocalDateTime overdueAt() {
        return dueAt.plus(GRACE_PERIOD);
    }

    /**
     * True if the loan is past its due date and not yet returned.
     * The loan may still be within the grace period — use
     * {@link #isOverdue()} to check that.
     */
    public boolean isPastDue() {
        return returnedAt == null && LocalDateTime.now().isAfter(dueAt);
    }

    /**
     * True if the loan is unreturned and past the due date plus the
     * grace period.
     */
    public boolean isOverdue() {
        return returnedAt == null && LocalDateTime.now().isAfter(overdueAt());
    }
}
```

What changed and why:

1. **`GRACE_PERIOD` constant** — a `Duration` rather than a magic number inside `isOverdue()`, so the policy is visible, documented, and referenced in one place. I picked 2 days as a placeholder; set it to whatever the actual policy is.

2. **`isOverdue()` now includes the grace period** — this keeps the existing meaning callers rely on ("should we fine/flag this member?") while softening it per the new requirement. Existing call sites don't need to change.

3. **`isPastDue()` added** — the old strict check, preserved under a more precise name. You'll likely want this for "your book was due yesterday" reminder notices, which should go out *during* the grace period, not after it.

4. **`overdueAt()` helper** — useful for display ("overdue on Aug 14") and for scheduling fine jobs, and it keeps the boundary calculation in one spot.

A couple of things to consider beyond this change (happy to do these as a follow-up):

- **Testability**: all the `LocalDateTime.now()` calls make this class hard to unit test — you can't easily write a test for "loan due 1 day ago, within grace" without sleeping or mocking statics. The standard fix is injecting a `java.time.Clock` into the class (or passing `LocalDateTime now` into `isOverdue(now)`). I'd strongly recommend that before writing fine-calculation logic on top of this.
- **Per-loan grace periods**: if grace ever varies by member type or item type (e.g. shorter for high-demand holds), the constant becomes a `Duration gracePeriod` field set in the constructor. The `overdueAt()` structure above makes that a one-line change.
- **Boundary semantics**: with `isAfter`, a loan becomes overdue strictly *after* `dueAt + GRACE_PERIOD`, i.e. at exactly the boundary instant it is not yet overdue. That matches the previous behavior of `isOverdue`, but worth stating in case anyone writes boundary tests.
