# Prompt Pair 1A — completion-tool arm, two runs, verbatim

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Edits made: none.

## Method, and its one disclosed approximation

A real completion tool is driven by an editor plugin, which this book has no way to run
inside a reproducible clean-context harness. The arm below approximates it in the only
way that preserves the property under test: the model is given the file up to the cursor
and instructed to emit **only** the raw continuation, with no prose, no explanation and no
markdown. That is the defining constraint of the completion category, and it is the
constraint the section's argument turns on. The approximation is disclosed here and in
§1.4 rather than presented as a plugin capture.

## The file given, cursor at the end of the last line

```java
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
     * Checks whether the loan is currently overdue.
     */
    public boolean isOverdue() {
        return returnedAt == null && LocalDateTime.now().isAfter(dueAt);
    }

    // A loan is not treated as overdue during a short grace period
    // after the due date.
    public boolean isOverdueAfterGracePeriod
```

## Run 1, complete output

```java
() {
        // Grace period of 2 days after the due date
        return returnedAt == null && LocalDateTime.now().isAfter(dueAt.plusDays(2));
    }
}
```

## Run 2, complete output

```java
() {
        // Grace period of 2 days after the due date
        return returnedAt == null && LocalDateTime.now().isAfter(dueAt.plusDays(2));
    }

    /**
     * Marks the loan as returned.
     */
    public void markReturned() {
        this.returnedAt = LocalDateTime.now();
    }
}
```

## Reading

Both runs invented **two days**. Both wrote the invented number into a comment
(`// Grace period of 2 days after the due date`) which states it as though it were a
recorded policy. Neither run flagged it, hedged it, or asked. Neither could: the category's
defining constraint is that there is no channel for prose, so a completion tool has nowhere
to put a caveat even if it has one.

The output is three lines of Java, in the surrounding file's style, using the surrounding
file's names, compiling first time. Run 2 additionally volunteered a `markReturned()`
method the cursor position did not ask for.
