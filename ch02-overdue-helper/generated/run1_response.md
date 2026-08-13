# ch02-overdue-helper — run 1, verbatim (the generated half of Exhibit 2A)

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls)
- Model: claude-fable-5
- Date: 11 August 2026
- Prompt (verbatim): "Write a small helper method that works out whether a loan is overdue."
- Edits made: none.

---

```java
import java.time.LocalDate;

/**
 * Determines whether a loan is overdue.
 *
 * @param dueDate      the date the loan is due (must not be null)
 * @param returnedDate the date the item was returned, or null if still out
 * @return true if the loan is overdue
 */
public static boolean isOverdue(LocalDate dueDate, LocalDate returnedDate) {
    Objects.requireNonNull(dueDate, "dueDate must not be null");
    LocalDate reference = (returnedDate != null) ? returnedDate : LocalDate.now();
    return reference.isAfter(dueDate);
}
```

A few notes on the choices here:

- **`LocalDate` over `Date`/`Calendar`** — the `java.time` API is the modern standard; loans are date-granular, so `LocalDate` fits better than `LocalDateTime`.
- **Handles returned items** — a loan returned late was still overdue, and a loan still out is only overdue once today passes the due date. Passing `returnedDate` as `null` means "not yet returned", so the check falls back to today's date.
- **`isAfter`, not `!isBefore`** — an item due today is not overdue yet; it becomes overdue the day after the due date. If your library counts the due date itself as overdue, change it to `!reference.isBefore(dueDate)`.

If your `Loan` class already holds these fields, it reads more naturally as an instance method:

```java
public boolean isOverdue() {
    LocalDate reference = (returnedDate != null) ? returnedDate : LocalDate.now();
    return reference.isAfter(dueDate);
}
```

One testability tip: if you want deterministic unit tests, add an overload that accepts a `Clock` (or a "today" parameter) instead of calling `LocalDate.now()` directly — e.g. `isOverdue(LocalDate today)` — so tests don't depend on the wall clock.
