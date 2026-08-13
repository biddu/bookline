package ie.ardaralibraries.bookline.circulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HoldQueueServiceTest {

    private static final String TITLE = "title-42";
    private static final Instant T0 = Instant.parse("2026-02-01T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-02-02T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-02-03T09:00:00Z");

    @Mock HoldRepository holdRepository;

    private HoldQueueService service;

    @BeforeEach
    void setUp() {
        service = new HoldQueueService(holdRepository);
    }

    @Test
    void nextHoldFor_noUnsatisfiedHolds_returnsEmpty() {
        when(holdRepository.findUnsatisfiedByTitle(TITLE)).thenReturn(List.of());

        assertTrue(service.nextHoldFor(TITLE).isEmpty());
    }

    @Test
    void nextHoldFor_prefersLowerPriorityClassEvenIfPlacedLater() {
        Hold earlyLowUrgency = new Hold("H-1", TITLE, "M-1", 2, T0);
        Hold lateHighUrgency = new Hold("H-2", TITLE, "M-2", 1, T2);
        when(holdRepository.findUnsatisfiedByTitle(TITLE))
                .thenReturn(List.of(earlyLowUrgency, lateHighUrgency));

        Optional<Hold> next = service.nextHoldFor(TITLE);

        assertTrue(next.isPresent());
        assertSame(lateHighUrgency, next.get());
    }

    @Test
    void nextHoldFor_samePriorityClass_firstComeFirstServed() {
        Hold second = new Hold("H-2", TITLE, "M-2", 1, T1);
        Hold first = new Hold("H-1", TITLE, "M-1", 1, T0);
        when(holdRepository.findUnsatisfiedByTitle(TITLE)).thenReturn(List.of(second, first));

        Optional<Hold> next = service.nextHoldFor(TITLE);

        assertTrue(next.isPresent());
        assertSame(first, next.get());
    }

    @Test
    void satisfyNext_marksTheWinningHoldSatisfiedAndSavesIt() {
        Hold winner = new Hold("H-1", TITLE, "M-1", 1, T0);
        Hold loser = new Hold("H-2", TITLE, "M-2", 1, T1);
        when(holdRepository.findUnsatisfiedByTitle(TITLE)).thenReturn(List.of(winner, loser));
        when(holdRepository.save(winner)).thenReturn(winner);

        Optional<Hold> satisfied = service.satisfyNext(TITLE);

        assertTrue(satisfied.isPresent());
        assertSame(winner, satisfied.get());
        assertTrue(winner.isSatisfied());
        assertFalse(loser.isSatisfied());
        verify(holdRepository).save(winner);
        verify(holdRepository, never()).save(loser);
    }

    @Test
    void satisfyNext_noHolds_returnsEmptyAndSavesNothing() {
        when(holdRepository.findUnsatisfiedByTitle(TITLE)).thenReturn(List.of());

        assertTrue(service.satisfyNext(TITLE).isEmpty());
        verify(holdRepository, never()).save(any());
    }
}
