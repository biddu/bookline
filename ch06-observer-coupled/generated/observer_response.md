Here's how I'd add hold notification to the return flow. The key design decisions:

1. **The return path should not know about email or SMS.** `ReturnService` depends on a small `HoldNotifier` interface; the concrete channels (`EmailNotifier`, `SmsNotifier`) are injected at wiring time. That keeps the return logic testable and lets you add channels later without touching it.
2. **Only one member gets notified per returned copy.** A returned copy can satisfy exactly one hold, so we pick the *winning* hold — the oldest active hold on that title (FIFO by `placedAt`) — mark it ready for pickup, and notify just that member. Notifying everyone with a hold would promise a copy to people who won't get one.
3. **The state change and the notification are separated.** We first transition the hold to `READY_FOR_PICKUP` and persist it, then notify. If notification fails, the hold is still correctly reserved.

### `HoldStatus.java`

```java
public enum HoldStatus {
    ACTIVE,
    READY_FOR_PICKUP,
    FULFILLED,
    CANCELLED
}
```

### `HoldNotifier.java` — the seam between return logic and delivery channels

```java
/**
 * Notifies a member that a hold is ready for collection.
 * Implementations decide the channel (email, SMS, ...).
 */
public interface HoldNotifier {
    void holdReady(Hold hold);
}
```

### `EmailNotifier.java` and `SmsNotifier.java`

```java
public final class EmailNotifier implements HoldNotifier {

    private final EmailGateway gateway;

    public EmailNotifier(EmailGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void holdReady(Hold hold) {
        var member = hold.member();
        gateway.send(
            member.email(),
            "Your hold is ready for collection",
            "Hi %s, \"%s\" is ready for you at the front desk. We'll keep it for 7 days."
                .formatted(member.name(), hold.title())
        );
    }
}
```

```java
public final class SmsNotifier implements HoldNotifier {

    private final SmsGateway gateway;

    public SmsNotifier(SmsGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void holdReady(Hold hold) {
        var member = hold.member();
        gateway.send(
            member.phoneNumber(),
            "Library: \"%s\" is ready for collection. Held for 7 days."
                .formatted(hold.title())
        );
    }
}
```

### `CompositeHoldNotifier.java` — fan out to every configured channel

```java
import java.util.List;

public final class CompositeHoldNotifier implements HoldNotifier {

    private final List<HoldNotifier> notifiers;

    public CompositeHoldNotifier(List<HoldNotifier> notifiers) {
        this.notifiers = List.copyOf(notifiers);
    }

    @Override
    public void holdReady(Hold hold) {
        for (var notifier : notifiers) {
            try {
                notifier.holdReady(hold);
            } catch (RuntimeException e) {
                // One failing channel must not block the others.
                // Log and continue; a real system would enqueue a retry.
            }
        }
    }
}
```

### `ReturnService.java` — the return path itself

```java
import java.util.Comparator;
import java.util.Optional;

public final class ReturnService {

    private final HoldRepository holdRepository;
    private final HoldNotifier holdNotifier;

    public ReturnService(HoldRepository holdRepository, HoldNotifier holdNotifier) {
        this.holdRepository = holdRepository;
        this.holdNotifier = holdNotifier;
    }

    public void processReturn(Copy copy) {
        copy.markReturned();

        Optional<Hold> winner = holdRepository.findByTitle(copy.isbn()).stream()
                .filter(h -> h.status() == HoldStatus.ACTIVE)
                .min(Comparator.comparing(Hold::placedAt));   // oldest hold wins

        winner.ifPresentOrElse(
            hold -> {
                copy.reserveFor(hold);
                hold.markReadyForPickup();
                holdRepository.save(hold);
                holdNotifier.holdReady(hold);   // notify AFTER state is persisted
            },
            copy::markAvailable                 // no holds: back on the shelf
        );
    }
}
```

### Wiring (composition root)

```java
HoldNotifier notifier = new CompositeHoldNotifier(List.of(
    new EmailNotifier(emailGateway),
    new SmsNotifier(smsGateway)
));

ReturnService returnService = new ReturnService(holdRepository, notifier);
```

### Notes

- **Assumed shape of `Hold`:** it exposes `member()`, `title()`, `placedAt()`, `status()`, and a `markReadyForPickup()` transition. If your `Hold` differs, adjust the accessors — the structure of the solution doesn't change.
- **Tie-breaking:** `min(comparing(Hold::placedAt))` gives first-come-first-served. If your library has priority tiers (e.g., staff or accessibility holds), swap in a comparator that sorts by priority then `placedAt`.
- **Concurrency:** if two copies of the same title come back at once, both threads could pick the same hold. Run `processReturn` inside a transaction and either lock the hold row (`SELECT ... FOR UPDATE`) or make `markReadyForPickup()` a guarded compare-and-set that throws if the hold is no longer `ACTIVE`, then retry with the next hold.
- **Testing:** `ReturnService` needs only a fake `HoldRepository` and a recording `HoldNotifier` — no email/SMS infrastructure in unit tests, which is exactly why the interface sits where it does.
