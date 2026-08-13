Here is the circulation extension. A few words on the shape of it before the code.

**Design decisions.** Holds and renewal live in two small services rather than being bolted onto `CirculationService`, since checkout and return are already owned there. Every state change that other modules care about is published as a typed event through a `CirculationEventPublisher` port; the implementation of that port belongs to platform wiring. Because notification may import only platform, the platform-side adapter is expected to serialize these events onto the platform bus — notification never imports circulation types, and billing (which may import circulation) can subscribe to the typed events directly. Fulfilling a hold performs the checkout **through the injected `CirculationService`**, so the concurrent-loan limit is enforced in exactly one place and is not re-implemented or weakened here.

**Assumed contracts on the injected types** (adjust names to match your actual signatures — nothing else depends on them):

- `LoanRepository`: `Optional<Loan> findById(LoanId)`, `void save(Loan)`
- `Loan` (existing circulation type): `id()`, `memberId()`, `titleId()`, `format()`, `dueDate()`, `renewalCount()`, `isOpen()`, and a mutator `renewTo(LocalDate newDueDate)` that increments the renewal count — if the mutator does not exist yet it must be added, which is circulation-internal
- `HoldRepository`: `Optional<Hold> findById(HoldId)`, `void save(Hold)`, `boolean existsUnsatisfiedByMemberAndTitle(MemberId, TitleId)`, `Optional<Hold> findOldestOpenByTitle(TitleId)`, `boolean anyUnsatisfiedByTitle(TitleId)`
- `CirculationService`: `Loan checkout(MemberId, CopyId)` — enforces the concurrent-loan limit and member standing, throwing if violated
- `LoanPolicy` (resolves tier through membership internally): `Period loanPeriod(MemberId, Format)`, `int renewalLimit(MemberId, Format)`, `int concurrentLoanLimit(MemberId)`
- `LibraryCalendar`: `LocalDate nextOpenDayOnOrAfter(LocalDate)`
- Identifier types from catalogue (`TitleId`, `CopyId`, `Format`) and membership (`MemberId`)

---

### `circulation/HoldId.java`

```java
package org.ardara.bookline.circulation;

import java.util.UUID;

public record HoldId(UUID value) {

    public HoldId {
        if (value == null) {
            throw new IllegalArgumentException("HoldId value must not be null");
        }
    }

    public static HoldId newId() {
        return new HoldId(UUID.randomUUID());
    }
}
```

### `circulation/HoldStatus.java`

```java
package org.ardara.bookline.circulation;

public enum HoldStatus {
    /** Waiting in the queue; no copy assigned yet. */
    OPEN,
    /** A copy has been reserved and is waiting on the hold shelf. */
    READY,
    /** The member has collected the copy; the hold is complete. */
    FULFILLED,
    /** Cancelled by the member (or on their behalf) before fulfilment. */
    CANCELLED
}
```

### `circulation/Hold.java`

```java
package org.ardara.bookline.circulation;

import org.ardara.bookline.catalogue.CopyId;
import org.ardara.bookline.catalogue.TitleId;
import org.ardara.bookline.membership.MemberId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public class Hold {

    private final HoldId id;
    private final MemberId memberId;
    private final TitleId titleId;
    private final Instant placedAt;
    private HoldStatus status;
    private CopyId reservedCopyId;

    private Hold(HoldId id,
                 MemberId memberId,
                 TitleId titleId,
                 Instant placedAt,
                 HoldStatus status,
                 CopyId reservedCopyId) {
        this.id = Objects.requireNonNull(id);
        this.memberId = Objects.requireNonNull(memberId);
        this.titleId = Objects.requireNonNull(titleId);
        this.placedAt = Objects.requireNonNull(placedAt);
        this.status = Objects.requireNonNull(status);
        this.reservedCopyId = reservedCopyId;
    }

    public static Hold place(MemberId memberId, TitleId titleId, Instant placedAt) {
        return new Hold(HoldId.newId(), memberId, titleId, placedAt, HoldStatus.OPEN, null);
    }

    /** Assign an available copy to this hold and move it to the hold shelf. */
    void markReady(CopyId copyId) {
        requireStatus(HoldStatus.OPEN, "assign a copy to");
        this.reservedCopyId = Objects.requireNonNull(copyId);
        this.status = HoldStatus.READY;
    }

    /** Complete the hold after the reserved copy has been checked out to the member. */
    void markFulfilled() {
        requireStatus(HoldStatus.READY, "fulfil");
        this.status = HoldStatus.FULFILLED;
    }

    /** Cancel the hold. Permitted while OPEN or READY; the reserved copy is released. */
    void cancel() {
        if (status != HoldStatus.OPEN && status != HoldStatus.READY) {
            throw new IllegalStateException(
                    "Cannot cancel hold %s in status %s".formatted(id, status));
        }
        this.status = HoldStatus.CANCELLED;
    }

    private void requireStatus(HoldStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Cannot %s hold %s in status %s".formatted(action, id, status));
        }
    }

    /** A hold counts against renewal until it is fulfilled or cancelled. */
    public boolean isUnsatisfied() {
        return status == HoldStatus.OPEN || status == HoldStatus.READY;
    }

    public HoldId id() {
        return id;
    }

    public MemberId memberId() {
        return memberId;
    }

    public TitleId titleId() {
        return titleId;
    }

    public Instant placedAt() {
        return placedAt;
    }

    public HoldStatus status() {
        return status;
    }

    public Optional<CopyId> reservedCopyId() {
        return Optional.ofNullable(reservedCopyId);
    }
}
```

### `circulation/CirculationEventPublisher.java`

```java
package org.ardara.bookline.circulation;

import org.ardara.bookline.circulation.events.CirculationEvent;

/**
 * Outbound port for domain events raised by circulation.
 *
 * <p>The implementation lives in platform wiring: it forwards each event onto
 * the platform event bus in a form that carries no circulation types, so that
 * notification (which imports only platform) can consume it. Billing, which is
 * permitted to import circulation, may subscribe to the typed events directly.
 */
public interface CirculationEventPublisher {

    void publish(CirculationEvent event);
}
```

### `circulation/HoldService.java`

```java
package org.ardara.bookline.circulation;

import org.ardara.bookline.catalogue.CopyId;
import org.ardara.bookline.catalogue.TitleId;
import org.ardara.bookline.circulation.events.HoldCancelled;
import org.ardara.bookline.circulation.events.HoldFulfilled;
import org.ardara.bookline.circulation.events.HoldPlaced;
import org.ardara.bookline.circulation.events.HoldReadyForPickup;
import org.ardara.bookline.membership.MemberId;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

public class HoldService {

    private final HoldRepository holds;
    private final CirculationService circulation;
    private final CirculationEventPublisher events;
    private final Clock clock;

    public HoldService(HoldRepository holds,
                       CirculationService circulation,
                       CirculationEventPublisher events,
                       Clock clock) {
        this.holds = Objects.requireNonNull(holds);
        this.circulation = Objects.requireNonNull(circulation);
        this.events = Objects.requireNonNull(events);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Place a hold on a title. A member may not queue twice for the same title. */
    public Hold placeHold(MemberId memberId, TitleId titleId) {
        if (holds.existsUnsatisfiedByMemberAndTitle(memberId, titleId)) {
            throw new DuplicateHoldException(memberId, titleId);
        }
        Hold hold = Hold.place(memberId, titleId, clock.instant());
        holds.save(hold);
        events.publish(new HoldPlaced(
                hold.id(), hold.memberId(), hold.titleId(), hold.placedAt()));
        return hold;
    }

    /**
     * Cancel a hold on behalf of the member who placed it. If the hold had a
     * copy reserved, that copy is immediately offered to the next hold in the
     * queue for the same title.
     */
    public void cancelHold(HoldId holdId, MemberId requestedBy) {
        Hold hold = holds.findById(holdId)
                .filter(h -> h.memberId().equals(requestedBy))
                .orElseThrow(() -> new HoldNotFoundException(holdId));
        CopyId freedCopy = hold.reservedCopyId().orElse(null);
        hold.cancel();
        holds.save(hold);
        events.publish(new HoldCancelled(
                hold.id(), hold.memberId(), hold.titleId(), clock.instant()));
        if (freedCopy != null) {
            offerCopy(hold.titleId(), freedCopy);
        }
    }

    /**
     * Entry point for the return flow: a copy of {@code titleId} has just
     * become available. If anyone is queued, the oldest open hold is satisfied
     * (moved to READY with this copy reserved) and an event is published so
     * notification can invite the member to collect it.
     *
     * @return the hold that was satisfied, or empty if the queue was empty and
     *         the copy may go back on the open shelf.
     */
    public Optional<Hold> onCopyAvailable(TitleId titleId, CopyId copyId) {
        return offerCopy(titleId, copyId);
    }

    /**
     * The member collects the reserved copy. Checkout is delegated to
     * {@link CirculationService}, which owns and enforces the concurrent-loan
     * limit for the member's tier; if that limit (or any other checkout rule)
     * is violated, the checkout throws and the hold remains READY.
     */
    public Loan fulfilHold(HoldId holdId) {
        Hold hold = holds.findById(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));
        CopyId copyId = hold.reservedCopyId()
                .orElseThrow(() -> new IllegalStateException(
                        "Hold %s has no reserved copy to collect".formatted(holdId)));
        Loan loan = circulation.checkout(hold.memberId(), copyId);
        hold.markFulfilled();
        holds.save(hold);
        events.publish(new HoldFulfilled(
                hold.id(), hold.memberId(), hold.titleId(), copyId, clock.instant()));
        return loan;
    }

    private Optional<Hold> offerCopy(TitleId titleId, CopyId copyId) {
        Optional<Hold> next = holds.findOldestOpenByTitle(titleId);
        next.ifPresent(hold -> {
            hold.markReady(copyId);
            holds.save(hold);
            events.publish(new HoldReadyForPickup(
                    hold.id(), hold.memberId(), titleId, copyId, clock.instant()));
        });
        return next;
    }
}
```

### `circulation/RenewalService.java`

```java
package org.ardara.bookline.circulation;

import org.ardara.bookline.circulation.events.LoanRenewed;
import org.ardara.bookline.membership.MemberId;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

public class RenewalService {

    private final LoanRepository loans;
    private final HoldRepository holds;
    private final LoanPolicy policy;
    private final LibraryCalendar calendar;
    private final CirculationEventPublisher events;
    private final Clock clock;

    public RenewalService(LoanRepository loans,
                          HoldRepository holds,
                          LoanPolicy policy,
                          LibraryCalendar calendar,
                          CirculationEventPublisher events,
                          Clock clock) {
        this.loans = Objects.requireNonNull(loans);
        this.holds = Objects.requireNonNull(holds);
        this.policy = Objects.requireNonNull(policy);
        this.calendar = Objects.requireNonNull(calendar);
        this.events = Objects.requireNonNull(events);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Renew a loan on behalf of the borrowing member.
     *
     * <p>Refused when the loan is not open, when any unsatisfied hold exists on
     * the title, or when the tier-and-format renewal limit has been reached.
     * The renewal limit and the loan period both come from {@link LoanPolicy};
     * no policy number appears here as a literal.
     */
    public Loan renew(LoanId loanId, MemberId requestedBy) {
        Loan loan = loans.findById(loanId)
                .filter(l -> l.memberId().equals(requestedBy))
                .orElseThrow(() -> new LoanNotFoundException(loanId));

        if (!loan.isOpen()) {
            throw new RenewalRefusedException(loanId, RenewalRefusedException.Reason.LOAN_NOT_OPEN);
        }
        if (holds.anyUnsatisfiedByTitle(loan.titleId())) {
            throw new RenewalRefusedException(loanId, RenewalRefusedException.Reason.HOLD_QUEUE_NOT_EMPTY);
        }
        int limit = policy.renewalLimit(loan.memberId(), loan.format());
        if (loan.renewalCount() >= limit) {
            throw new RenewalRefusedException(loanId, RenewalRefusedException.Reason.RENEWAL_LIMIT_REACHED);
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate base = loan.dueDate().isAfter(today) ? loan.dueDate() : today;
        LocalDate newDueDate = calendar.nextOpenDayOnOrAfter(
                base.plus(policy.loanPeriod(loan.memberId(), loan.format())));

        LocalDate previousDueDate = loan.dueDate();
        loan.renewTo(newDueDate);
        loans.save(loan);

        events.publish(new LoanRenewed(
                loan.id(), loan.memberId(), loan.titleId(),
                previousDueDate, newDueDate, loan.renewalCount(), clock.instant()));
        return loan;
    }
}
```

### `circulation/DuplicateHoldException.java`

```java
package org.ardara.bookline.circulation;

import org.ardara.bookline.catalogue.TitleId;
import org.ardara.bookline.membership.MemberId;

public class DuplicateHoldException extends RuntimeException {

    public DuplicateHoldException(MemberId memberId, TitleId titleId) {
        super("Member %s already has an open hold on title %s".formatted(memberId, titleId));
    }
}
```

### `circulation/HoldNotFoundException.java`

```java
package org.ardara.bookline.circulation;

public class HoldNotFoundException extends RuntimeException {

    public HoldNotFoundException(HoldId holdId) {
        super("No such hold: %s".formatted(holdId));
    }
}
```

### `circulation/LoanNotFoundException.java`

```java
package org.ardara.bookline.circulation;

public class LoanNotFoundException extends RuntimeException {

    public LoanNotFoundException(LoanId loanId) {
        super("No such loan: %s".formatted(loanId));
    }
}
```

### `circulation/RenewalRefusedException.java`

```java
package org.ardara.bookline.circulation;

public class RenewalRefusedException extends RuntimeException {

    public enum Reason {
        LOAN_NOT_OPEN,
        HOLD_QUEUE_NOT_EMPTY,
        RENEWAL_LIMIT_REACHED
    }

    private final Reason reason;

    public RenewalRefusedException(LoanId loanId, Reason reason) {
        super("Renewal of loan %s refused: %s".formatted(loanId, reason));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
```

### `circulation/events/CirculationEvent.java`

```java
package org.ardara.bookline.circulation.events;

import java.time.Instant;

public sealed interface CirculationEvent
        permits HoldPlaced, HoldCancelled, HoldReadyForPickup, HoldFulfilled, LoanRenewed {

    Instant occurredAt();
}
```

### `circulation/events/HoldPlaced.java`

```java
package org.ardara.bookline.circulation.events;

import org.ardara.bookline.catalogue.TitleId;
import org.ardara.bookline.circulation.HoldId;
import org.ardara.bookline.membership.MemberId;

import java.time.Instant;

public record HoldPlaced(
        HoldId holdId,
        MemberId memberId,
        TitleId titleId,
        Instant occurredAt) implements CirculationEvent {
}
```

### `circulation/events/HoldCancelled.java`

```java
package org.ardara.bookline.circulation.events;

import org.ardara.bookline.catalogue.TitleId;
import org.ardara.bookline.circulation.HoldId;
import org.ardara.bookline.membership.MemberId;

import java.time.Instant;

public record HoldCancelled(
        HoldId holdId,
        MemberId memberId,
        TitleId titleId,
        Instant occurredAt) implements CirculationEvent {
}
```

### `circulation/events/HoldReadyForPickup.java`

```java
package org.ardara.bookline.circulation.events;

import org.ardara.bookline.catalogue.CopyId;
import org.ardara.bookline.catalogue.TitleId;
import org.ardara.bookline.circulation.HoldId;
import org.ardara.bookline.membership.MemberId;

import java.time.Instant;

public record HoldReadyForPickup(
        HoldId holdId,
        MemberId memberId,
        TitleId titleId,
        CopyId copyId,
        Instant occurredAt) implements CirculationEvent {
}
```

### `circulation/events/HoldFulfilled.java`

```java
package org.ardara.bookline.circulation.events;

import org.ardara.bookline.catalogue.CopyId;
import org.ardara.bookline.catalogue.TitleId;
import org.ardara.bookline.circulation.HoldId;
import org.ardara.bookline.membership.MemberId;

import java.time.Instant;

public record HoldFulfilled(
        HoldId holdId,
        MemberId memberId,
        TitleId titleId,
        CopyId copyId,
        Instant occurredAt) implements CirculationEvent {
}
```

### `circulation/events/LoanRenewed.java`

```java
package org.ardara.bookline.circulation.events;

import org.ardara.bookline.catalogue.TitleId;
import org.ardara.bookline.circulation.LoanId;
import org.ardara.bookline.membership.MemberId;

import java.time.Instant;
import java.time.LocalDate;

public record LoanRenewed(
        LoanId loanId,
        MemberId memberId,
        TitleId titleId,
        LocalDate previousDueDate,
        LocalDate newDueDate,
        int renewalCount,
        Instant occurredAt) implements CirculationEvent {
}
```

---

### How the evaluation criteria are met

- **Imports.** Every class imports only from catalogue (`TitleId`, `CopyId`, `Format` via `Loan`), membership (`MemberId`), circulation itself, and `java.*`. Nothing from billing, notification, or any infrastructure library. No SQL, no mail — persistence stays behind the injected repositories, and outbound effects stop at `CirculationEventPublisher`.
- **Policy numbers.** The renewal limit and loan period are read from `LoanPolicy` at the point of use. The concurrent-loan limit is never touched here at all: hold fulfilment routes through `CirculationService.checkout`, the single place that enforces it, so the N-open-loans rule cannot be weakened by this code.
- **Renewal vs. holds.** `RenewalService.renew` refuses with `HOLD_QUEUE_NOT_EMPTY` whenever `HoldRepository.anyUnsatisfiedByTitle` is true, where "unsatisfied" covers both OPEN and READY holds.

### Edge cases this implementation does not handle

1. **Race on hold satisfaction.** Two copies returned near-simultaneously can both read the same oldest OPEN hold and try to mark it READY. One will fail on the status guard only if the repository serialises the writes; otherwise you need pessimistic locking or an optimistic-version check in `HoldRepository`. The same race exists between `renew` reading the hold queue and a hold being placed a moment later.
2. **Hold-shelf expiry.** A READY hold has no pickup deadline, because a shelf period is a policy number and `LoanPolicy` (as specified) does not carry one — inventing a literal here would violate your own constraint. The right fix is to add a hold-shelf period to `LoanPolicy` and run a scheduled sweep that expires READY holds and re-offers the copy via `onCopyAvailable`.
3. **Overdue loans renew from today.** An overdue loan is renewable if it passes the other checks, with the new period counted from today. Many libraries block renewal once a loan is overdue or fines exceed a threshold; that decision belongs to billing and would need a policy flag or an inbound check, not a literal here.
4. **No existence or standing checks on placement.** `placeHold` does not verify that the title exists in the catalogue, that any copy of it is circulating, or that the member is in good standing — no catalogue or membership lookup type was in the injected set, so I did not invent one. Invalid IDs currently create orphan holds; suspended members are only stopped later, at checkout.
5. **Hold scope is the title, not the format.** A hold on any edition of a title blocks renewal of every format of that title. If Bookline queues holds per format, `anyUnsatisfiedByTitle` should be narrowed to the loan's format.
6. **No idempotency on `onCopyAvailable`.** Calling it twice for the same physical copy reserves that copy against two different holds. The return flow must call it exactly once per return, or the method needs to check whether the copy is already reserved.
7. **Direct borrowing does not consume a hold.** A member with a READY hold who checks out a *different* copy of the same title at the desk keeps their hold occupying a reserved copy. Linking checkout to an existing hold would require a change inside `CirculationService.checkout`, which is out of scope here.
8. **Date semantics.** Due dates are `LocalDate` in the library's civil calendar via the injected `Clock`; if loans ever carry time-of-day due times or the service runs across time zones, the `LocalDate.now(clock)` comparison in renewal needs revisiting.
