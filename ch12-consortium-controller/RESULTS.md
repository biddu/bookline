# ch12-consortium-controller — results

**Contract outcome (10 Aug 2026): all four drafted REST defects refused, in both runs.
The two runs then disagreed with each other about the one decision that matters most for
a six-partner contract, and one of them named the other's choice as the thing to push
back on.**

## What was asked

The vague half of Prompt Pair 12A, verbatim, twice, in clean context:

```
Build a REST endpoint so consortium partners can search our catalogue
and check whether a title is available.
```

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Tool calls: zero in both runs.
- Edits made: none. Full responses in generated/run1_response.md, generated/run2_response.md.

## The four drafted defects

| Drafted defect | Run 1 | Run 2 |
|---|---|---|
| Verbs in paths | **refused.** `/api/v1/catalogue/search`, `/api/v1/catalogue/items/{isbn}/availability` | **refused.** `/api/partner/v1/catalogue/search`, `/{isbn}/availability` |
| 200 for everything | **refused.** `@RestControllerAdvice` mapping to 404 and 400, using RFC 9457 `ProblemDetail` | **refused.** `@RestControllerAdvice` with `@ResponseStatus` 404 / 400 and a stable `{code, message}` error body |
| Non-idempotent retryable operations | **not applicable and correctly so.** Both endpoints are `GET`, both services `@Transactional(readOnly = true)` | same |
| No versioning | **refused.** `/v1/` in the path | **refused.** `/v1/` in the path |

Run 1 reached for `ProblemDetail`, which is RFC 9457's `application/problem+json`, without
being asked. Both capped page size at 100, both defaulted to 20, both sorted by title,
both required at least one search criterion and returned 400 otherwise, both validated the
ISBN with a regex, and both flagged that the endpoint must not ship unauthenticated.

The 2023-era REST defects are gone. §12.3 has been rewritten accordingly.

## What survives

**1. The two runs disagree about the response envelope, and one calls the other out.**
Run 1 defines a `SearchResultDto<T>` record with `results`, `page`, `size`,
`totalResults`, `totalPages`, and closes with: *"The one thing I'd push back on if anyone
suggests it: don't return the JPA entity or Spring's raw `Page` object directly. Both leak
internals into a contract external partners will build against, and you'll be stuck with
it."* Run 2's controller signature is:

```java
    public Page<BookSummaryDto> search(
```

which returns Spring's `Page` directly. Same prompt, same day. One run produced the thing
the other named as the mistake it would argue against. For a public envelope shipped to
six partners, this is the single most expensive decision in the file, and the tool has no
settled opinion about it.

**2. Availability is a stored counter in both runs.** Run 1 puts `copiesAvailable` on the
catalogue entity and notes it "assumes your circulation module keeps it updated." Run 2
reads `b.getCopiesAvailable() > 0`. A partner reading a stale counter is the interlending
double-ship, which is INV-1 arriving through a cache rather than through a race.

**3. Both search with `LIKE '%...%'`, and both say so.** Run 1: *"won't use an index. Fine
up to maybe low hundreds of thousands of rows."* Run 2: *"fine for a modest catalogue…
move search to a proper full-text index."* Correctly diagnosed, not fixed, and the fix is
the subject of §12.6.

**4. Authentication: raised by both, implemented by neither.** Run 1 supplies a
`SecurityFilterChain` bean but calls it a placeholder "while you decide", and says *"This
must not go out unauthenticated."* Run 2: *"Security is not in this snippet but is not
optional… I didn't want to guess at our existing auth setup."* Both are right to refuse to
guess. Both also produced a complete, runnable, unauthenticated partner-facing endpoint
with the refusal underneath it.

**5. ISBN as the public identifier, in both runs, unexamined.** Both expose the title-level
ISBN as the resource identifier for an availability check. Chapter 1's five runs made the
same title-versus-copy conflation; here it reaches the contract, where six partners will
build against it and it can no longer be changed cheaply.

## Reading

Exhibit 12A in the book is run 1, printed verbatim, and read for what survives rather than
for the four defects the draft predicted. §12.3 keeps the four named defects as a review
vocabulary, because inherited controllers and smaller models still commit them, and marks
them as retired in the current tools with the date on which that was measured.
