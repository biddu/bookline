# ch01-first-loan — results

**Contract outcome (10 Aug 2026): refused in all five runs. The chapter's argument
survives, on different and better evidence.**

## What was asked

Five clean-context runs of one prompt, identical wording every time, 13 words:

```
Write a Java class to represent a book loan in a library system.
```

Plus both arms of Prompt Pair 1A: two runs of the completion-tool arm and one of the
chat-tool arm. All seven responses are in `generated/`, verbatim, unedited.

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Edits made: none.

## The defect contract, and why it failed

The drafted exhibit was to show "mutable setters including `setDueDate()` (INV-2 seed),
no validation, plausible naming." **Not one of the five runs produced it.** All five used
private fields with no setters, all five validated in the constructor
(`Objects.requireNonNull`, and a check that the due date is not before the checkout date),
and all five guarded their state transitions so a loan cannot be returned twice or renewed
after return. Three of the five explicitly explained that overdue status is *derived*
rather than stored, giving the staleness argument this book makes in Chapter 3.

The 2023-era shape is gone. What replaced it is more interesting, because §1.2's four
claims are about production, not about defect shapes, and the runs strengthen all four.

## Cross-run variation from an identical prompt

| | Run 1 | Run 2 | Run 3 | Run 4 | Run 5 |
|---|---|---|---|---|---|
| Lines of Java | 133 | 120 | **184** | 121 | 146 |
| Words of prose alongside | 329 | 436 | 421 | 366 | 408 |
| **Loan period invented** | **14 days** | **21 days** | 14 days | 14 days | 14 days |
| Item identifier | `bookIsbn` | `bookIsbn` | **`bookCopyId`** | `bookIsbn` | `bookIsbn` |
| Daily fine invented | **0.50** | none | none | none | **0.50** |
| Renewal cap invented | none | none | **2** | none | none |
| Renewal semantics | +loan period | caller passes days | +loan period | caller passes days | caller passes days |
| `LocalDate.now()` inside the entity | 0 | 0 | 0 | **4** | **1** |
| Class name | `BookLoan` | `BookLoan` | `BookLoan` | `BookLoan` | `BookLoan` |

Five findings follow, in rising order of consequence.

**1. Volume.** 13 words in; 120 to 184 lines of Java out, plus 329 to 436 words of design
commentary, every time. Nobody asked for a fine calculator, a lost-item state, a renewal
cap, a `toString`, or an `equals`/`hashCode` contract. Each is a decision, made by nobody,
now the reader's responsibility.

**2. The loan period is invented, and the tool does not agree with itself.** Four runs
chose 14 days; one chose 21. The prompt named no library, no jurisdiction, and no policy.
Ardara County Library Service was consulted zero times out of five. This is the single
cleanest demonstration in the book that a generated business rule is a sample from a
distribution rather than a fact: run the same words twice and the members' due dates move
by a week.

**3. Four of five loan the wrong thing.** `bookIsbn` identifies a *title*, not a copy. A
library with eleven branches holds many copies of one ISBN, and a loan is of a physical
copy; model it by ISBN and two members cannot both borrow the same title, or worse, the
system cannot tell which copy came back. Only run 3 got it right, and run 3 said so
without being asked: *"Note it's a copy ID, not an ISBN — a library has multiple copies of
the same title, and you're loaning a specific physical one."* One run in five knew the
distinction and volunteered it; four wrote a domain error that compiles, reads well, and
would survive most code review. This is INV-4's territory, arriving in Chapter 1.

**4. Two runs put the clock back inside the object while claiming they had not.** Runs 4
and 5 call `LocalDate.now()` inside the entity, in `getStatus()` and `getDaysOverdue()`,
in the same response whose design notes say derived state "can never go stale" and
"deriving them makes that bug impossible." The prose is correct. The code beneath it is
not. This is the book's recurring finding that judgment has moved from the diff into the
notes above it, in its sharpest form: here the notes and the diff contradict each other,
and only the diff runs.

**5. The refusals are real but partial.** Every run refused the drafted defect. No run
refused to invent. What retired is a *shape* with a name; what did not retire is filling
silence, which has no name and no canon to retire it.

## Prompt Pair 1A

**Completion arm, two runs, identical output on the line that matters:**

```java
        // Grace period of 2 days after the due date
        return returnedAt == null && LocalDateTime.now().isAfter(dueAt.plusDays(2));
```

Two days, invented, twice, written into a comment that presents it as a recorded policy.
No hedge, no flag, no question. The category has no channel for one.

**Chat arm, same request:** the same invented two days, but as a named
`public static final Duration GRACE_PERIOD = Duration.ofDays(2)`, accompanied by
*"I picked 2 days as a placeholder; set it to whatever the actual policy is,"* plus
`isPastDue()` preserved under a more precise name, an `overdueAt()` helper, and three
follow-up considerations including an unprompted warning that the class's
`LocalDateTime.now()` calls make it hard to test.

**Correction to the drafted reading.** The draft predicted the chat tool would return
~130 lines with an interface, a custom exception and a configuration property, and framed
the contrast as small-and-hidden versus large-and-over-engineered. It did not
over-engineer. The over-engineering retired along with the god class and the singleton.
What actually separates the two categories on this evidence is **disclosure**: both
invented the identical number, and one of them told you. §1.4 has been rewritten around
that, which is a sharper point than the one it replaces, because it survives the tools
getting better at design.

## Reading

Exhibit 1A in the book is run 1, printed verbatim. The cross-run table is §1.2's evidence
for provenance and invention, and the Prompt Pair reading in §1.4 is rebuilt around
disclosure rather than size.
