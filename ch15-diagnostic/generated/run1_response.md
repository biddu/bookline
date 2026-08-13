# ch15-diagnostic — run 1, verbatim (this is Exhibit 15A in the book)

**KEEP UNPUBLISHED until launch. This is the answer key for the second diagnostic.**

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls)
- Model: claude-fable-5
- Date: 11 August 2026
- Prompt (verbatim): "Tidy up our hold-assignment routine. When a copy is returned at a branch desk, it should either go to the next waiting hold for that book, or go back on the open shelves. Holds are satisfied in the order they were placed so the queue is fair. It should be transactional and the member should be notified that their item is ready for collection. Make it clearer than what we have."
- Edits made: none.

---

Here's how I'd restructure it. The core idea: returning a copy is one atomic decision — "who gets this next?" — so I'd pull it into a single transactional service method with two clearly named branches, and push the queue logic down into the repository so the service reads like the business rule you just described.

**CopyReturnService.java**

```java
@Service
public class CopyReturnService {

    private static final Logger log = LoggerFactory.getLogger(CopyReturnService.class);

    private final BookCopyRepository copyRepository;
    private final HoldRepository holdRepository;
    private final MemberNotifier memberNotifier;
    private final Clock clock;

    public CopyReturnService(BookCopyRepository copyRepository,
                             HoldRepository holdRepository,
                             MemberNotifier memberNotifier,
                             Clock clock) {
        this.copyRepository = copyRepository;
        this.holdRepository = holdRepository;
        this.memberNotifier = memberNotifier;
        this.clock = clock;
    }

    /**
     * Handles a copy being returned at a branch desk.
     * Either assigns it to the longest-waiting hold for the book,
     * or returns it to the open shelves if nobody is waiting.
     */
    @Transactional
    public ReturnOutcome processReturn(Long copyId) {
        BookCopy copy = copyRepository.findById(copyId)
                .orElseThrow(() -> new CopyNotFoundException(copyId));

        return holdRepository
                .findOldestActiveHoldForUpdate(copy.getBook().getId())
                .map(hold -> assignToHold(copy, hold))
                .orElseGet(() -> returnToShelf(copy));
    }

    private ReturnOutcome assignToHold(BookCopy copy, Hold hold) {
        Instant now = clock.instant();

        hold.markReadyForCollection(copy, now);
        copy.setStatus(CopyStatus.ON_HOLD_SHELF);

        log.info("Copy {} assigned to hold {} for member {}",
                copy.getId(), hold.getId(), hold.getMember().getId());

        memberNotifier.notifyItemReadyForCollection(hold);

        return ReturnOutcome.assignedToHold(hold);
    }

    private ReturnOutcome returnToShelf(BookCopy copy) {
        copy.setStatus(CopyStatus.AVAILABLE);
        log.info("Copy {} returned to open shelves", copy.getId());
        return ReturnOutcome.returnedToShelf();
    }
}
```

**HoldRepository.java** — the fairness rule lives here, in one query:

```java
public interface HoldRepository extends JpaRepository<Hold, Long> {

    /**
     * Oldest active hold for the book — FIFO by placement time.
     * Pessimistic lock so two desks returning copies of the same
     * book at once can't both grab the same hold.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT h FROM Hold h
           WHERE h.book.id = :bookId
             AND h.status = com.example.library.hold.HoldStatus.WAITING
           ORDER BY h.placedAt ASC
           LIMIT 1
           """)
    Optional<Hold> findOldestActiveHoldForUpdate(@Param("bookId") Long bookId);
}
```

**Hold.java** — give the entity the state transition instead of setting fields from the service:

```java
@Entity
public class Hold {

    @Id @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Member member;

    @OneToOne(fetch = FetchType.LAZY)
    private BookCopy assignedCopy;

    @Enumerated(EnumType.STRING)
    private HoldStatus status;

    private Instant placedAt;
    private Instant readyAt;

    public void markReadyForCollection(BookCopy copy, Instant when) {
        if (status != HoldStatus.WAITING) {
            throw new IllegalStateException(
                "Hold " + id + " is not waiting; status=" + status);
        }
        this.assignedCopy = copy;
        this.status = HoldStatus.READY_FOR_COLLECTION;
        this.readyAt = when;
    }

    // getters...
}
```

**ReturnOutcome.java** — so the controller/desk UI can tell the librarian what to physically do with the item:

```java
public record ReturnOutcome(Disposition disposition, Long holdId, String memberName) {

    public enum Disposition { ASSIGNED_TO_HOLD, RETURNED_TO_SHELF }

    public static ReturnOutcome assignedToHold(Hold hold) {
        return new ReturnOutcome(Disposition.ASSIGNED_TO_HOLD,
                hold.getId(), hold.getMember().getFullName());
    }

    public static ReturnOutcome returnedToShelf() {
        return new ReturnOutcome(Disposition.RETURNED_TO_SHELF, null, null);
    }
}
```

**MemberNotifier** — keep the service decoupled from *how* we notify:

```java
public interface MemberNotifier {
    void notifyItemReadyForCollection(Hold hold);
}
```

A few things worth calling out, since they're the deliberate choices rather than incidental style:

**1. The pessimistic lock matters.** Two copies of the same popular book can come back at two desks within the same second. Without `PESSIMISTIC_WRITE` on the queue-head query, both transactions can read the same hold, both mark it ready, and one copy ends up orphaned on the hold shelf. The lock serializes them: the second transaction waits, sees the hold is no longer `WAITING`, and picks the *next* hold (or shelves the copy). That's also why `markReadyForCollection` guards on status — belt and braces.

**2. Fairness is one `ORDER BY placedAt ASC`, in one place.** If the queue ever looks unfair, there's exactly one query to check. Don't scatter hold-selection logic across services. (If you're on an older Hibernate that doesn't support `LIMIT` in JPQL, use `ORDER BY h.placedAt ASC` with `PageRequest.of(0, 1)` and a `List` return, or a native query — same semantics.)

**3. Notification inside the transaction is a judgment call.** As written, if the email/SMS provider throws, the whole return rolls back — arguably wrong, since the hold assignment is valid even if the email bounced. If that bites you, the clean fix is to publish an event instead:

```java
applicationEventPublisher.publishEvent(new HoldReadyEvent(hold.getId()));
```

with a listener annotated `@TransactionalEventListener(phase = AFTER_COMMIT)` doing the actual send. That guarantees you only ever notify members about assignments that actually committed, and a flaky mail server can't roll back a book return. I'd do that as a follow-up; the interface seam (`MemberNotifier`) means the service code doesn't change either way.

**4. `Clock` injected rather than `Instant.now()`** — makes the "was marked ready at the right time" tests trivial. Register a `Clock.systemUTC()` bean if you don't have one.

**5. Statuses are explicit.** `WAITING → READY_FOR_COLLECTION` on the hold, and the copy goes to `ON_HOLD_SHELF`, not `AVAILABLE` — a copy sitting on the hold shelf must never be findable by the "available copies" queries, or someone at the desk will check it out over the top of the hold.

One test I'd insist on before merging: two concurrent `processReturn` calls for the same book with one waiting hold — assert exactly one copy is `ON_HOLD_SHELF` and the other is `AVAILABLE`. That's the race the old code presumably had, and it's cheap to pin down with `@SpringBootTest` and a two-thread executor against a real database (H2 won't exercise the lock the same way Postgres does, so run it against your Testcontainers setup if you have one).
