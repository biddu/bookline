# ch15-diagnostic — results

**Contract outcome (11 Aug 2026): the central defect MET, in both runs, and the
supporting defects refused in a way that makes the diagnostic harder rather than easier.
This is the best possible outcome for an assessment instrument.**

**KEEP UNPUBLISHED until launch. This directory is the answer key.**

## What was asked

```
Tidy up our hold-assignment routine. When a copy is returned at a branch desk,
it should either go to the next waiting hold for that book, or go back on the
open shelves. Holds are satisfied in the order they were placed so the queue is
fair. It should be transactional and the member should be notified that their
item is ready for collection. Make it clearer than what we have.
```

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls)
- Model: claude-fable-5
- Date: 11 August 2026
- Edits made: none. Full responses in generated/run1_response.md, generated/run2_response.md.

## The contract, clause by clause

| Clause | Run 1 | Run 2 |
|---|---|---|
| Queries holds by title, not by copy | **met** | **met** |
| Sorts by placedAt alone, dropping the priority class (INV-4) | **MET** | **MET** |
| Non-atomic read-then-write, so two same-minute returns double-satisfy | **refused**, `PESSIMISTIC_WRITE` | **refused**, `PESSIMISTIC_WRITE` + 3s lock timeout |
| Module-dependency violation for Q3 | **met**, service depends on `MemberNotifier` directly | refused, publishes an event |

### The defect that matters: INV-4's priority class is gone, in both runs

Run 1:
```java
           SELECT h FROM Hold h
           WHERE h.book.id = :bookId
             AND h.status = com.example.library.hold.HoldStatus.WAITING
           ORDER BY h.placedAt ASC
           LIMIT 1
```

Run 2 is the same with an `h.id asc` tiebreaker added.

INV-4 reads: "The hold queue for a title is first-come-first-served **within a priority
class**, and one available copy satisfies exactly one hold." Both runs implement the first
half and delete the second qualifier. The housebound-service class, the reason the
invariant has the clause at all, is served in strict placement order alongside everyone
else, which means a housebound member who placed a hold on Tuesday waits behind eleven
ordinary members who placed theirs on Monday.

Both runs also announce the omission as a feature. Run 1: *"Fairness is one
`ORDER BY placedAt ASC`, in one place. If the queue ever looks unfair, there's exactly one
query to check."* Run 2: *"`order by placedAt asc, id asc` in the repository query is the
entire queue policy… If you ever add priority tiers (staff holds, interlibrary loans),
only this query changes."*

Run 2's sentence is the most useful line in the exhibit for teaching purposes. It shows
the model *knows* priority tiers are a thing that exists, mentions them by name, and
files them under future work, because nothing in the prompt said Ardara already has one.
This is not ignorance. It is the absence-driven failure family: the tool cannot know which
of the world's optional features your county already bought.

### The race is fixed, and that is what makes the diagnostic hard now

Both runs take a pessimistic write lock on the queue head and both explain the exact
scenario. Run 1: *"Two copies of the same popular book can come back at two desks within
the same second. Without `PESSIMISTIC_WRITE` on the queue-head query, both transactions
can read the same hold, both mark it ready, and one copy ends up orphaned on the hold
shelf."* Both guard the state transition in the entity as well, so a double-processed
return throws rather than silently reassigning.

Chapter 2's diagnostic was a race. A reader who memorised Chapter 2 will come to this
exhibit looking for a race, find it handled, find it handled *with an explanation*, and
approve the code. That is precisely the trap the brief asked for, and the tool built it
without being asked.

### Q3, and the run-to-run split

Run 1 calls `memberNotifier.notifyItemReadyForCollection(hold)` inside the transaction,
then discloses it: *"Notification inside the transaction is a judgment call. As written,
if the email/SMS provider throws, the whole return rolls back — arguably wrong, since the
hold assignment is valid even if the email bounced… I'd do that as a follow-up."* So the
defect ships with its own correction attached, below the code, as a follow-up nobody
scheduled. It also couples `circulation` to `notification` directly, which is the
module-dependency violation the contract wanted.

Run 2 publishes a `HoldReadyEvent` and handles it `AFTER_COMMIT`, which is correct, and
adds the reasoning run 1 omitted: *"a rollback after the send means a member gets 'your
book is ready' for a book that was never actually assigned to them."*

One more disagreement worth the reader's attention: run 2 adds `id asc` as a tiebreaker
and says why — *"two holds placed in the same millisecond would otherwise be ordered
nondeterministically"* — and run 1 does not. So run 1's queue is unfair twice over, once
by design and once by accident, and only the second is visible to a reader who knows to
look for it.

## Exhibit 15A is run 1

It carries three findings at three depths, which is what a ninety-second read has to
triage:

1. **Scan tier will miss all three.** The method reads beautifully.
2. **Read tier should catch the notification inside the transaction**, because it is
   visible in the call sequence, and the disclosure note is right there.
3. **Audit tier catches INV-4**, and only for a reader who holds the invariant in mind
   rather than the code, because nothing in the file mentions a priority class. There is
   nothing to notice. There is only something absent.

That ordering is the chapter's whole argument about the Graduated Read, measured on one
artefact.
