# ch15-diagnostic — run 2, verbatim

**KEEP UNPUBLISHED until launch.**

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls)
- Model: claude-fable-5
- Date: 11 August 2026
- Prompt: identical to run 1.
- Edits made: none.
- **Note: this run publishes an event handled AFTER_COMMIT rather than notifying inside the transaction, and adds an `id asc` tiebreaker run 1 lacks. It drops INV-4's priority class exactly as run 1 does, and names priority tiers explicitly as future work.**

---

Here's how I'd restructure the check-in / hold-assignment flow. The core idea: one small transactional service method whose body reads like the business rule you just stated — "next hold wins, otherwise back on the shelf" — with the queue ordering pushed down into the repository and the notification deferred until the transaction actually commits.

**The service**

```java
@Service
public class CopyReturnService {

    private final HoldRepository holdRepository;
    private final BookCopyRepository bookCopyRepository;
    private final ApplicationEventPublisher eventPublisher;

    public CopyReturnService(HoldRepository holdRepository,
                             BookCopyRepository bookCopyRepository,
                             ApplicationEventPublisher eventPublisher) {
        this.holdRepository = holdRepository;
        this.bookCopyRepository = bookCopyRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Processes a copy returned at a branch desk: assign it to the oldest
     * outstanding hold for the book, or return it to the open shelves.
     */
    @Transactional
    public ReturnOutcome processReturn(Long copyId) {
        BookCopy copy = bookCopyRepository.findById(copyId)
                .orElseThrow(() -> new CopyNotFoundException(copyId));

        return holdRepository.findNextPendingForUpdate(copy.getBookId())
                .map(hold -> assignToHold(copy, hold))
                .orElseGet(() -> returnToShelf(copy));
    }

    private ReturnOutcome assignToHold(BookCopy copy, Hold hold) {
        hold.markReadyForCollection(copy);
        copy.setStatus(CopyStatus.AWAITING_COLLECTION);

        eventPublisher.publishEvent(
                new HoldReadyEvent(hold.getId(), hold.getMemberId(), copy.getId()));

        return ReturnOutcome.assignedToHold(hold.getId());
    }

    private ReturnOutcome returnToShelf(BookCopy copy) {
        copy.setStatus(CopyStatus.ON_SHELF);
        return ReturnOutcome.shelved();
    }
}
```

**The repository — fairness and concurrency live here**

```java
public interface HoldRepository extends JpaRepository<Hold, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("""
           select h from Hold h
           where h.bookId = :bookId
             and h.status = com.example.library.hold.HoldStatus.PENDING
           order by h.placedAt asc, h.id asc
           limit 1
           """)
    Optional<Hold> findNextPendingForUpdate(@Param("bookId") Long bookId);
}
```

**The entity keeps its own state transitions honest**

```java
@Entity
public class Hold {

    @Id @GeneratedValue
    private Long id;
    private Long bookId;
    private Long memberId;
    private Instant placedAt;
    @Enumerated(EnumType.STRING)
    private HoldStatus status;
    private Long assignedCopyId;
    private Instant readyAt;

    public void markReadyForCollection(BookCopy copy) {
        if (status != HoldStatus.PENDING) {
            throw new IllegalStateException(
                    "Hold %d is %s, cannot mark ready".formatted(id, status));
        }
        this.status = HoldStatus.READY_FOR_COLLECTION;
        this.assignedCopyId = copy.getId();
        this.readyAt = Instant.now();
    }
    // getters...
}
```

**Notification — after commit, not inside the transaction**

```java
@Component
public class HoldNotificationListener {

    private final NotificationService notificationService;

    public HoldNotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onHoldReady(HoldReadyEvent event) {
        notificationService.notifyItemReadyForCollection(
                event.memberId(), event.holdId(), event.copyId());
    }
}

public record HoldReadyEvent(Long holdId, Long memberId, Long copyId) {}
```

And a small result type so the desk UI can tell the librarian what happened:

```java
public record ReturnOutcome(Disposition disposition, Long holdId) {
    public enum Disposition { ASSIGNED_TO_HOLD, SHELVED }

    public static ReturnOutcome assignedToHold(Long holdId) {
        return new ReturnOutcome(Disposition.ASSIGNED_TO_HOLD, holdId);
    }
    public static ReturnOutcome shelved() {
        return new ReturnOutcome(Disposition.SHELVED, null);
    }
}
```

A few notes on the reasoning, since these are the bits that tend to get debated in review:

1. **Fairness is enforced in one place.** `order by placedAt asc, id asc` in the repository query is the entire queue policy. The `id` tiebreaker matters — two holds placed in the same millisecond would otherwise be ordered nondeterministically. If you ever add priority tiers (staff holds, interlibrary loans), only this query changes.

2. **Pessimistic lock on the hold row, not optimistic retries.** Two copies of the same title can come back at two desks simultaneously. With `PESSIMISTIC_WRITE`, the second transaction blocks until the first commits, then re-runs the query and sees that hold is no longer `PENDING`, so it correctly picks the *next* hold (or shelves the copy). Optimistic locking would work too but you'd be writing retry loops for a case the database handles for you. The 3-second lock timeout stops a stuck transaction from freezing every desk in the system.

3. **Notification happens `AFTER_COMMIT`.** This is the one people get wrong most often. If you call the email/SMS service inside the transaction, two bad things can happen: a slow SMTP server holds your row locks open, and — worse — a rollback after the send means a member gets "your book is ready" for a book that was never actually assigned to them. Publishing a domain event and handling it in a `@TransactionalEventListener` means the member is only told once the assignment is durably committed. The flip side: if the notification send fails, the assignment still stands, which is what you want — the hold is real, and you can retry the notification (worth routing through a queue or outbox table if delivery matters a lot).

4. **State transition guarded in the entity.** `markReadyForCollection` throws if the hold isn't `PENDING`, so a bug elsewhere (double-processing a return, say) fails loudly instead of silently reassigning a hold someone's already been notified about.

5. **The service method is the readable summary.** `findNextPendingForUpdate(...).map(assignToHold).orElseGet(returnToShelf)` — anyone reading it gets the business rule in one line, which was the "make it clearer" part of the brief.

One thing to check against your existing schema: I assumed holds are placed against a *book* (title) and satisfied by any *copy*. If your holds are placed against specific copies or the book has multiple editions that shouldn't cross-satisfy, the `bookId` in the query becomes whatever your grouping key is — the structure stays the same. Also, if `NotificationService` is currently called from the controller, delete that call; the listener is now the single notification path.
