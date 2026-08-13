Here's the extension to the circulation module. The four constraints map directly onto the shape of the code, so let me state the mapping up front:

- **Kiosk sees a narrower contract.** `KioskCirculation` exposes only checkout, return, and current loans. `CirculationService` extends it with holds and renewals for the branch terminal. Wire the kiosk against `KioskCirculation` and the restriction is enforced at compile time — no runtime role checks.
- **No policy literals.** Every limit and period comes through the `LoanPolicy` port, resolved from the member's `Membership`. The service compares against what the policy returns; it never knows the numbers.
- **Publish and stop.** The moment a loan becomes interesting to fines or notices (a late return, a hold ready for pickup), the service publishes a `CirculationEvent` and does nothing further. No fine arithmetic, no notice text, no report rows.
- **Ports, not SQL.** `LoanRepository`, `HoldRepository`, and `CirculationEventPublisher` are interfaces injected into the service. Persistence lives elsewhere.

One assumption: the `membership` package exposes `MemberId`, `Membership`, and a `MembershipDirectory` with `Membership membershipOf(MemberId)`, and `catalogue` exposes `ItemId`. Adjust those names to whatever the packages actually export.

### The two caller-facing contracts

```java
// KioskCirculation.java
package ie.ardaralibraries.bookline.circulation;

import ie.ardaralibraries.bookline.catalogue.ItemId;
import ie.ardaralibraries.bookline.membership.MemberId;

import java.util.List;

/**
 * The subset of circulation available to the self-service kiosk.
 * The kiosk is wired against this type and nothing wider, so holds
 * and renewals are invisible to it at compile time.
 */
public interface KioskCirculation {

    Loan checkOut(MemberId member, ItemId item);

    void returnItem(ItemId item);

    List<Loan> currentLoans(MemberId member);
}
```

```java
// CirculationService.java
package ie.ardaralibraries.bookline.circulation;

import ie.ardaralibraries.bookline.catalogue.ItemId;
import ie.ardaralibraries.bookline.membership.MemberId;

/**
 * Full circulation contract for the branch terminal service:
 * everything the kiosk can do, plus holds and renewals.
 */
public interface CirculationService extends KioskCirculation {

    Hold placeHold(MemberId member, ItemId item);

    void cancelHold(HoldId hold);

    Loan renew(MemberId member, ItemId item);
}
```

### Policy and ports

```java
// LoanPolicy.java
package ie.ardaralibraries.bookline.circulation;

import ie.ardaralibraries.bookline.membership.Membership;

import java.time.Period;

/**
 * Source of every policy value in circulation. Implementations resolve
 * values from the member's tier; the numbers live behind this port and
 * nowhere else, so tier rules can change without touching this package.
 */
public interface LoanPolicy {

    int concurrentLoanLimit(Membership membership);

    Period loanPeriod(Membership membership);

    int renewalLimit(Membership membership);

    Period holdShelfPeriod(Membership membership);
}
```

```java
// LoanRepository.java
package ie.ardaralibraries.bookline.circulation;

import ie.ardaralibraries.bookline.catalogue.ItemId;
import ie.ardaralibraries.bookline.membership.MemberId;

import java.util.List;
import java.util.Optional;

/** Persistence port for loans. Implemented outside this package. */
public interface LoanRepository {

    Optional<Loan> openLoanFor(ItemId item);

    List<Loan> openLoansFor(MemberId member);

    void save(Loan loan);
}
```

```java
// HoldRepository.java
package ie.ardaralibraries.bookline.circulation;

import ie.ardaralibraries.bookline.catalogue.ItemId;

import java.util.Optional;

/** Persistence port for holds. Implemented outside this package. */
public interface HoldRepository {

    Optional<Hold> byId(HoldId id);

    /** The earliest-placed hold on this item that is still queued or ready. */
    Optional<Hold> headOfQueueFor(ItemId item);

    boolean anyOutstandingFor(ItemId item);

    void save(Hold hold);
}
```

```java
// CirculationEventPublisher.java
package ie.ardaralibraries.bookline.circulation;

/**
 * Outbound port for domain events. Fines, notices, and reports subscribe
 * on the other side; circulation publishes and stops.
 */
public interface CirculationEventPublisher {

    void publish(CirculationEvent event);
}
```

### Domain events

```java
// CirculationEvent.java
package ie.ardaralibraries.bookline.circulation;

/** Marker for events crossing out of circulation. */
public interface CirculationEvent {
}
```

```java
// LateReturnRecorded.java
package ie.ardaralibraries.bookline.circulation;

import ie.ardaralibraries.bookline.catalogue.ItemId;
import ie.ardaralibraries.bookline.membership.MemberId;

import java.time.LocalDate;

/**
 * A loan came back after its due date. Whether that costs anything,
 * and how much, is the fines module's business — not ours.
 */
public record LateReturnRecorded(
        LoanId loan,
        MemberId member,
        ItemId item,
        LocalDate dueOn,
        LocalDate returnedOn) implements CirculationEvent {
}
```

```java
// HoldReadyForPickup.java
package ie.ardaralibraries.bookline.circulation;

import ie.ardaralibraries.bookline.catalogue.ItemId;
import ie.ardaralibraries.bookline.membership.MemberId;

import java.time.LocalDate;

/**
 * A held item is waiting on the shelf. Telling the member is the
 * notices module's business — not ours.
 */
public record HoldReadyForPickup(
        HoldId hold,
        MemberId member,
        ItemId item,
        LocalDate readyUntil) implements CirculationEvent {
}
```

### Identifiers

```java
// LoanId.java
package ie.ardaralibraries.bookline.circulation;

import java.util.UUID;

public record LoanId(UUID value) {

    public static LoanId newId() {
        return new LoanId(UUID.randomUUID());
    }
}
```

```java
// HoldId.java
package ie.ardaralibraries.bookline.circulation;

import java.util.UUID;

public record HoldId(UUID value) {

    public static HoldId newId() {
        return new HoldId(UUID.randomUUID());
    }
}
```

### Entities

```java
// Loan.java
package ie.ardaralibraries.bookline.circulation;

import ie.ardaralibraries.bookline.catalogue.ItemId;
import ie.ardaralibraries.bookline.membership.MemberId;

import java.time.LocalDate;
import java.util.Objects;

public final class Loan {

    private final LoanId id;
    private final MemberId member;
    private final ItemId item;
    private final LocalDate checkedOutOn;
    private LocalDate dueOn;
    private int renewals;
    private LoanStatus status;

    public static Loan open(MemberId member, ItemId item,
                            LocalDate checkedOutOn, LocalDate dueOn) {
        return new Loan(LoanId.newId(), member, item, checkedOutOn, dueOn);
    }

    private Loan(LoanId id, MemberId member, ItemId item,
                 LocalDate checkedOutOn, LocalDate dueOn) {
        this.id = Objects.requireNonNull(id);
        this.member = Objects.requireNonNull(member);
        this.item = Objects.requireNonNull(item);
        this.checkedOutOn = Objects.requireNonNull(checkedOutOn);
        this.dueOn = Objects.requireNonNull(dueOn);
        this.renewals = 0;
        this.status = LoanStatus.OPEN;
    }

    /**
     * Extend the loan. The caller supplies the renewal ceiling and the new
     * due date, both resolved from LoanPolicy — this entity holds no policy.
     */
    public void renew(LocalDate newDueOn, int renewalLimit) {
        requireOpen();
        if (renewals >= renewalLimit) {
            throw new RenewalNotAllowedException(
                    "Renewal limit reached for loan " + id.value());
        }
        this.dueOn = Objects.requireNonNull(newDueOn);
        this.renewals = renewals + 1;
    }

    public void markReturned() {
        requireOpen();
        this.status = LoanStatus.RETURNED;
    }

    public boolean isLateAsOf(LocalDate date) {
        return date.isAfter(dueOn);
    }

    private void requireOpen() {
        if (status != LoanStatus.OPEN) {
            throw new IllegalStateException(
                    "Loan " + id.value() + " is not open");
        }
    }

    public LoanId id()              { return id; }
    public MemberId member()        { return member; }
    public ItemId item()            { return item; }
    public LocalDate checkedOutOn() { return checkedOutOn; }
    public LocalDate dueOn()        { return dueOn; }
    public int renewals()           { return renewals; }
    public LoanStatus status()      { return status; }
}
```

```java
// LoanStatus.java
package ie.ardaralibraries.bookline.circulation;

public enum LoanStatus {
    OPEN,
    RETURNED
}
```

```java
// Hold.java
package ie.ardaralibraries.bookline.circulation;

import ie.ardaralibraries.bookline.catalogue.ItemId;
import ie.ardaralibraries.bookline.membership.MemberId;

import java.time.LocalDate;
import java.util.Objects;

public final class Hold {

    private final HoldId id;
    private final MemberId member;
    private final ItemId item;
    private final LocalDate placedOn;
    private HoldStatus status;
    private LocalDate readyUntil;

    public static Hold queued(MemberId member, ItemId item, LocalDate placedOn) {
        return new Hold(HoldId.newId(), member, item, placedOn);
    }

    private Hold(HoldId id, MemberId member, ItemId item, LocalDate placedOn) {
        this.id = Objects.requireNonNull(id);
        this.member = Objects.requireNonNull(member);
        this.item = Objects.requireNonNull(item);
        this.placedOn = Objects.requireNonNull(placedOn);
        this.status = HoldStatus.QUEUED;
    }

    /** The item is on the shelf for this member until the given date. */
    public void markReady(LocalDate readyUntil) {
        requireStatus(HoldStatus.QUEUED);
        this.status = HoldStatus.READY;
        this.readyUntil = Objects.requireNonNull(readyUntil);
    }

    public void fulfil() {
        requireStatus(HoldStatus.READY);
        this.status = HoldStatus.FULFILLED;
    }

    public void cancel() {
        if (status == HoldStatus.FULFILLED) {
            throw new IllegalStateException(
                    "Hold " + id.value() + " is already fulfilled");
        }
        this.status = HoldStatus.CANCELLED;
    }

    public boolean isHeldBy(MemberId candidate) {
        return member.equals(candidate);
    }

    private void requireStatus(HoldStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Hold " + id.value() + " is " + status + ", expected " + expected);
        }
    }

    public HoldId id()            { return id; }
    public MemberId member()      { return member; }
    public ItemId item()          { return item; }
    public LocalDate placedOn()   { return placedOn; }
    public HoldStatus status()    { return status; }
    public LocalDate readyUntil() { return readyUntil; }
}
```

```java
// HoldStatus.java
package ie.ardaralibraries.bookline.circulation;

public enum HoldStatus {
    QUEUED,
    READY,
    FULFILLED,
    CANCELLED
}
```

### Exceptions

```java
// CirculationException.java
package ie.ardaralibraries.bookline.circulation;

public abstract class CirculationException extends RuntimeException {

    protected CirculationException(String message) {
        super(message);
    }
}
```

```java
// ItemUnavailableException.java
package ie.ardaralibraries.bookline.circulation;

public final class ItemUnavailableException extends CirculationException {

    public ItemUnavailableException(String message) {
        super(message);
    }
}
```

```java
// LoanLimitReachedException.java
package ie.ardaralibraries.bookline.circulation;

public final class LoanLimitReachedException extends CirculationException {

    public LoanLimitReachedException(String message) {
        super(message);
    }
}
```

```java
// RenewalNotAllowedException.java
package ie.ardaralibraries.bookline.circulation;

public final class RenewalNotAllowedException extends CirculationException {

    public RenewalNotAllowedException(String message) {
        super(message);
    }
}
```

### The service

```java
// StandardCirculationService.java
package ie.ardaralibraries.bookline.circulation;

import ie.ardaralibraries.bookline.catalogue.ItemId;
import ie.ardaralibraries.bookline.membership.MemberId;
import ie.ardaralibraries.bookline.membership.Membership;
import ie.ardaralibraries.bookline.membership.MembershipDirectory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class StandardCirculationService implements CirculationService {

    private final LoanRepository loans;
    private final HoldRepository holds;
    private final MembershipDirectory memberships;
    private final LoanPolicy policy;
    private final CirculationEventPublisher events;
    private final Clock clock;

    public StandardCirculationService(LoanRepository loans,
                                      HoldRepository holds,
                                      MembershipDirectory memberships,
                                      LoanPolicy policy,
                                      CirculationEventPublisher events,
                                      Clock clock) {
        this.loans = Objects.requireNonNull(loans);
        this.holds = Objects.requireNonNull(holds);
        this.memberships = Objects.requireNonNull(memberships);
        this.policy = Objects.requireNonNull(policy);
        this.events = Objects.requireNonNull(events);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Loan checkOut(MemberId member, ItemId item) {
        if (loans.openLoanFor(item).isPresent()) {
            throw new ItemUnavailableException(
                    "Item " + item + " is already on loan");
        }

        Optional<Hold> head = holds.headOfQueueFor(item);
        if (head.isPresent() && !head.get().isHeldBy(member)) {
            throw new ItemUnavailableException(
                    "Item " + item + " is held for another member");
        }

        Membership membership = memberships.membershipOf(member);
        int limit = policy.concurrentLoanLimit(membership);
        if (loans.openLoansFor(member).size() >= limit) {
            throw new LoanLimitReachedException(
                    "Member " + member + " is at their concurrent-loan limit");
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate dueOn = today.plus(policy.loanPeriod(membership));
        Loan loan = Loan.open(member, item, today, dueOn);
        loans.save(loan);

        head.ifPresent(hold -> {
            hold.fulfil();
            holds.save(hold);
        });

        return loan;
    }

    @Override
    public void returnItem(ItemId item) {
        Loan loan = loans.openLoanFor(item)
                .orElseThrow(() -> new ItemUnavailableException(
                        "No open loan for item " + item));

        LocalDate today = LocalDate.now(clock);
        boolean late = loan.isLateAsOf(today);
        LocalDate dueOn = loan.dueOn();
        loan.markReturned();
        loans.save(loan);

        if (late) {
            // Whether this costs anything is the fines module's decision.
            events.publish(new LateReturnRecorded(
                    loan.id(), loan.member(), loan.item(), dueOn, today));
        }

        holds.headOfQueueFor(item).ifPresent(next -> {
            Membership holderMembership = memberships.membershipOf(next.member());
            LocalDate readyUntil = today.plus(policy.holdShelfPeriod(holderMembership));
            next.markReady(readyUntil);
            holds.save(next);
            // Telling the member is the notices module's job.
            events.publish(new HoldReadyForPickup(
                    next.id(), next.member(), next.item(), readyUntil));
        });
    }

    @Override
    public List<Loan> currentLoans(MemberId member) {
        return List.copyOf(loans.openLoansFor(member));
    }

    @Override
    public Hold placeHold(MemberId member, ItemId item) {
        Hold hold = Hold.queued(member, item, LocalDate.now(clock));
        holds.save(hold);
        return hold;
    }

    @Override
    public void cancelHold(HoldId holdId) {
        Hold hold = holds.byId(holdId)
                .orElseThrow(() -> new ItemUnavailableException(
                        "No hold with id " + holdId.value()));
        hold.cancel();
        holds.save(hold);
    }

    @Override
    public Loan renew(MemberId member, ItemId item) {
        Loan loan = loans.openLoanFor(item)
                .filter(l -> l.member().equals(member))
                .orElseThrow(() -> new RenewalNotAllowedException(
                        "Member " + member + " has no open loan for item " + item));

        if (holds.anyOutstandingFor(item)) {
            throw new RenewalNotAllowedException(
                    "Item " + item + " has outstanding holds");
        }

        LocalDate today = LocalDate.now(clock);
        if (loan.isLateAsOf(today)) {
            throw new RenewalNotAllowedException(
                    "Overdue loans cannot be renewed; return the item instead");
        }

        Membership membership = memberships.membershipOf(member);
        LocalDate newDueOn = today.plus(policy.loanPeriod(membership));
        loan.renew(newDueOn, policy.renewalLimit(membership));
        loans.save(loan);
        return loan;
    }
}
```

### Wiring notes

- **Kiosk:** inject `KioskCirculation` into the kiosk endpoint. Both interfaces are implemented by the same `StandardCirculationService` bean; the kiosk simply receives the narrower type, so a kiosk code path that tried to call `renew` would not compile.
- **Branch terminal:** inject `CirculationService`.
- **`LoanPolicy` implementation** lives outside this package (it knows about tiers and their numbers, and it will change). This package only ever asks it questions.
- **Events** go through `CirculationEventPublisher`. Fines subscribes to `LateReturnRecorded`, notices to `HoldReadyForPickup`. If reports later wants a feed, it subscribes too — circulation does not change.
- **`Clock` is injected** so due-date and lateness logic is testable without touching the system clock.

Two behavioural decisions worth flagging in review: renewals are refused when the item has outstanding holds or the loan is already overdue (both are common library rules — say the word if Ardara's rules differ, they're one-line changes in `renew`); and a late return publishes exactly one event with the raw dates — no grace period or fine amount is computed here, because grace periods belong to the fines policy, not circulation.
