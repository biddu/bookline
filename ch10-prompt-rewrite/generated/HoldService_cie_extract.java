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
