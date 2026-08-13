# ch02-overdue-helper — results

**Contract outcome (11 Aug 2026): the generated half of Exhibit 2A came back BETTER than
the drafted version, and §2.1's reading was corrected on the evidence.**

## What was asked

```
Write a small helper method that works out whether a loan is overdue.
```

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls)
- Model: claude-fable-5
- Date: 11 August 2026
- Edits made: none. Full response in generated/run1_response.md.

## What the draft assumed, and what happened

The drafted generated half was a one-liner that never checked whether the loan had been
returned, so a book handed back a day late would read as overdue forever. The chapter's
argument rested on it: the scruffy hand-written version is correct, the immaculate
generated one is not, and the polish stopped you noticing.

The real run handles the returned loan. It also guards its argument with
`Objects.requireNonNull`, gets the boundary right by treating the due date itself as not
yet overdue, and volunteers the alternative in case the library counts it the other way.
It is good code.

So the chapter's original reading was false and has been rewritten. The corrected reading
is stronger, because it survives the tools improving:

**1. What the colleague has that the model cannot.** One word in the scruffy comment:
*Deirdre*. The comment records a verification event, a named person at the circulation
desk asked on a stated date. That is contact with the domain, and no amount of model
capability produces it.

**2. The judgment is in the prose, not the diff.** The response calls `LocalDate.now()`
inside the method, which makes the helper untestable without the wall clock, and then
says so in a tip *after* the code: add an overload that takes a clock. The advice is
correct and the code does not take it. This is the "raised, not implemented" pattern that
Chapters 11 through 15 name repeatedly, appearing here in the book's first exhibit.

**3. Three absences, in code and in prose.** No branch calendar, so a loan is overdue on a
day the library was shut. No zone on `now()`, so the date is whatever the container was
started with rather than the day it is in Ardara. And no statement of what *this* library
means by overdue at all.

## Reading

Exhibit 2A is this run, printed verbatim beside the hand-written version. The chapter no
longer argues that the generated code is wrong. It argues that the generated code is
right, well-reasoned, and still cannot know the thing the scruffy comment records.
