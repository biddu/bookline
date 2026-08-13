Here are the tests. Five test classes, split by unit under test, in the two source packages so they can reach the package-private types.

**Before the code — three things you should read, because they affect whether these tests are the ones you want.**

I only had `Core.java` and `Billing.java` to work from. No spec, no ticket, no existing tests. That is fine for the mechanical parts (a `CopyUnavailableException` when the barcode is unknown is obviously right), but there are several places where the code makes a decision and I cannot tell whether the decision is the intended rule or an accident. Where that happened I wrote the test to pin the **current** behaviour and named the test so the assumption is visible. If any of these are wrong, the test is wrong too, and it will now actively defend the bug:

1. **The fine window excludes both ends.** `accrueUpTo` loops `for (day = dueDate.plusDays(1); day.isBefore(end); ...)`. So the due date is free (fine — a grace day), *and so is the last day*: an item returned on the 5th, due on the 1st, is charged for the 2nd, 3rd and 4th only. For a still-out item, today is never charged, so a borrower who is one day overdue owes nothing. That may be deliberate (you only fine for days that have completely elapsed), or it may be an off-by-one that quietly under-charges every fine by a day. Tests `doesNotChargeForToday` and `doesNotChargeForTheReturnDay` encode the current rule.
2. **`renew` refuses when the only outstanding hold is the borrower's own.** `findUnsatisfiedByTitle` is not filtered by membership number, so a member who has a hold on the next volume of the thing they are reading cannot renew it. Pinned in `throwsWhenTheOnlyHoldBelongsToTheRenewingMember`.
3. **`renew` never moves `dueAt`.** It increments `renewalCount` and saves. Unless something downstream recomputes the due date, a renewal buys the borrower nothing. I have asserted the current behaviour in `doesNotChangeDueDate` — this is the one I would most expect to be a real bug.

Two more, lower stakes, that I tested as-is rather than around:

- **`returnCopy` is not atomic.** It saves the loan as returned *before* looking up the copy, and that lookup is a bare `orElseThrow()`. If the copy row is missing, the loan is already closed and the copy never returns to `AVAILABLE`. `closesLoanEvenWhenCopyLookupFails` documents it.
- **`existsForLoanAndDate` is check-then-act.** Two concurrent accrual runs for the same loan can both pass the check and double-charge a day. Not unit-testable at this level; it needs a unique constraint on `(loan_id, accrual_date)`. Mentioning it because a passing test suite should not be read as "concurrency is fine".

Also worth a glance: `LibraryCalendar` in the circulation package is never used by anything in either file — `FineAccrualService` uses its own `BillingCalendar` with an identical shape. And `LoanPolicy.dailyFineRate` is never read in circulation; billing takes the rate from `OverdueLoanFacts` instead. Two calendars and two rate sources are two chances to disagree.

**On the mocking.** `FineCalculator` is a pure function with no collaborators, so `FineCalculatorTest` does not mock anything — mocking it there would only test Mockito. In `FineAccrualServiceTest` the calculator *is* mocked, but for most tests I delegate the mock to a real `FineCalculator` (`useRealCalculator()`), so the cap arithmetic stays honest while I can still use an `ArgumentCaptor` to prove the running total the service feeds it grows correctly. That captor test is the one that actually pins the accumulate-locally-after-seeding-from-the-repository logic, which is the least obvious thing in the class.

I ran these against your sources: 65 test methods, 72 executions with the parameterised rows, all green on JDK 21 / JUnit 5.10.2 / Mockito 5.11.0.

---

### `CirculationServiceTest.java`

```java
package ie.ardaralibraries.bookline.circulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CirculationService}. All four collaborators are mocked; the
 * domain objects ({@link Copy}, {@link Loan}, {@link Member}) are real, because they are
 * mutable value holders with no behaviour worth faking.
 */
@ExtendWith(MockitoExtension.class)
class CirculationServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T10:00:00Z");
    private static final Duration TWO_WEEKS = Duration.ofDays(14);
    private static final String BARCODE = "BC-1";
    private static final String TITLE_ID = "T-1";
    private static final String MEMBER_NO = "M-1";

    @Mock
    private LoanRepository loanRepository;
    @Mock
    private CopyRepository copyRepository;
    @Mock
    private HoldRepository holdRepository;
    @Mock
    private LoanPolicyResolver policyResolver;

    private CirculationService service;

    @BeforeEach
    void setUp() {
        service = new CirculationService(loanRepository, copyRepository, holdRepository, policyResolver);
    }

    // ---------------------------------------------------------------- helpers

    private static Member member() {
        return new Member(MEMBER_NO, "STANDARD");
    }

    private static Copy availableCopy() {
        return copyWith(CopyStatus.AVAILABLE);
    }

    private static Copy copyWith(CopyStatus status) {
        return new Copy(BARCODE, TITLE_ID, status, new BigDecimal("25.00"));
    }

    private static LoanPolicy policy(int renewalLimit, int concurrentLimit) {
        return new LoanPolicy(TWO_WEEKS, renewalLimit, concurrentLimit, new BigDecimal("0.20"));
    }

    private static Loan openLoanOwnedBy(String membershipNumber) {
        return new Loan("L-1", BARCODE, TITLE_ID, membershipNumber,
                NOW.minus(Duration.ofDays(7)), NOW.plus(TWO_WEEKS));
    }

    /** Makes {@code loanRepository.save} behave like a real repository: return what it was given. */
    private void echoLoanSave() {
        when(loanRepository.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Loan captureSavedLoan() {
        ArgumentCaptor<Loan> captor = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(captor.capture());
        return captor.getValue();
    }

    // --------------------------------------------------------------- checkout

    @Nested
    @DisplayName("checkout")
    class Checkout {

        @Test
        @DisplayName("persists a loan whose due date is now plus the resolved loan period")
        void persistsLoanWithPolicyDerivedDueDate() {
            Copy copy = availableCopy();
            Member member = member();
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 5));
            when(loanRepository.findOpenLoansByMember(MEMBER_NO)).thenReturn(List.of());
            echoLoanSave();

            Loan loan = service.checkout(member, BARCODE, NOW);

            assertEquals(BARCODE, loan.copyBarcode());
            assertEquals(TITLE_ID, loan.titleId());
            assertEquals(MEMBER_NO, loan.membershipNumber());
            assertEquals(NOW, loan.checkedOutAt());
            assertEquals(NOW.plus(TWO_WEEKS), loan.dueAt());
            assertEquals(0, loan.renewalCount());
            assertTrue(loan.isOpen());
            assertTrue(loan.returnedAt().isEmpty());
            // id must be a usable unique identifier
            assertEquals(loan.id(), UUID.fromString(loan.id()).toString());
        }

        @Test
        @DisplayName("marks the copy ON_LOAN and saves the copy before the loan")
        void marksCopyOnLoanAndSavesItBeforeTheLoan() {
            Copy copy = availableCopy();
            Member member = member();
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 5));
            when(loanRepository.findOpenLoansByMember(MEMBER_NO)).thenReturn(List.of());
            echoLoanSave();

            service.checkout(member, BARCODE, NOW);

            assertEquals(CopyStatus.ON_LOAN, copy.status());
            InOrder order = inOrder(copyRepository, loanRepository);
            order.verify(copyRepository).save(copy);
            order.verify(loanRepository).save(any(Loan.class));
        }

        @Test
        @DisplayName("returns the instance handed back by the loan repository, not the local one")
        void returnsInstanceReturnedByRepository() {
            Copy copy = availableCopy();
            Member member = member();
            Loan persisted = openLoanOwnedBy(MEMBER_NO);
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 5));
            when(loanRepository.findOpenLoansByMember(MEMBER_NO)).thenReturn(List.of());
            when(loanRepository.save(any(Loan.class))).thenReturn(persisted);

            assertSame(persisted, service.checkout(member, BARCODE, NOW));
        }

        @Test
        @DisplayName("generates a distinct id for every loan")
        void generatesDistinctLoanIds() {
            Member member = member();
            Copy first = availableCopy();
            Copy second = availableCopy();
            when(copyRepository.findByBarcode(BARCODE))
                    .thenReturn(Optional.of(first), Optional.of(second));
            when(policyResolver.policyFor(eq(member), any(Copy.class))).thenReturn(policy(2, 5));
            when(loanRepository.findOpenLoansByMember(MEMBER_NO)).thenReturn(List.of());
            echoLoanSave();

            Loan a = service.checkout(member, BARCODE, NOW);
            Loan b = service.checkout(member, BARCODE, NOW);

            assertNotEquals(a.id(), b.id());
        }

        @Test
        @DisplayName("rejects an unknown barcode")
        void throwsWhenCopyUnknown() {
            when(copyRepository.findByBarcode("nope")).thenReturn(Optional.empty());

            CopyUnavailableException ex = assertThrows(CopyUnavailableException.class,
                    () -> service.checkout(member(), "nope", NOW));

            assertTrue(ex.getMessage().contains("nope"));
            verifyNoInteractions(loanRepository, policyResolver);
        }

        @ParameterizedTest(name = "rejects a copy in state {0}")
        @EnumSource(value = CopyStatus.class, names = {"ON_LOAN", "REPAIR", "LOST"})
        void throwsWhenCopyNotAvailable(CopyStatus status) {
            Copy copy = copyWith(status);
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));

            assertThrows(CopyUnavailableException.class,
                    () -> service.checkout(member(), BARCODE, NOW));

            assertEquals(status, copy.status(), "an unavailable copy must not be mutated");
            verifyNoInteractions(loanRepository, policyResolver);
            verify(copyRepository, never()).save(any(Copy.class));
        }

        @Test
        @DisplayName("allows a member who is exactly one loan below the concurrent limit")
        void allowsMemberOneBelowConcurrentLimit() {
            Copy copy = availableCopy();
            Member member = member();
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 3));
            when(loanRepository.findOpenLoansByMember(MEMBER_NO))
                    .thenReturn(List.of(openLoanOwnedBy(MEMBER_NO), openLoanOwnedBy(MEMBER_NO)));
            echoLoanSave();

            assertEquals(BARCODE, service.checkout(member, BARCODE, NOW).copyBarcode());
        }

        @Test
        @DisplayName("refuses once the member is at the concurrent loan limit")
        void throwsWhenConcurrentLimitReached() {
            Copy copy = availableCopy();
            Member member = member();
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 2));
            when(loanRepository.findOpenLoansByMember(MEMBER_NO))
                    .thenReturn(List.of(openLoanOwnedBy(MEMBER_NO), openLoanOwnedBy(MEMBER_NO)));

            LoanLimitExceededException ex = assertThrows(LoanLimitExceededException.class,
                    () -> service.checkout(member, BARCODE, NOW));

            assertTrue(ex.getMessage().contains("STANDARD"));
        }

        @Test
        @DisplayName("refuses a zero-limit policy even for a member with no loans")
        void throwsWhenConcurrentLimitIsZero() {
            Copy copy = availableCopy();
            Member member = member();
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 0));
            when(loanRepository.findOpenLoansByMember(MEMBER_NO)).thenReturn(List.of());

            assertThrows(LoanLimitExceededException.class,
                    () -> service.checkout(member, BARCODE, NOW));
        }

        @Test
        @DisplayName("leaves the copy untouched when the loan limit is exceeded")
        void doesNotMutateOrSaveCopyWhenLimitExceeded() {
            Copy copy = availableCopy();
            Member member = member();
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 1));
            when(loanRepository.findOpenLoansByMember(MEMBER_NO))
                    .thenReturn(List.of(openLoanOwnedBy(MEMBER_NO)));

            assertThrows(LoanLimitExceededException.class,
                    () -> service.checkout(member, BARCODE, NOW));

            assertEquals(CopyStatus.AVAILABLE, copy.status());
            verify(copyRepository, never()).save(any(Copy.class));
            verify(loanRepository, never()).save(any(Loan.class));
        }
    }

    // ------------------------------------------------------------------ renew

    @Nested
    @DisplayName("renew")
    class Renew {

        @Test
        @DisplayName("increments the renewal count and saves the loan")
        void incrementsRenewalCountAndSavesLoan() {
            Member member = member();
            Copy copy = copyWith(CopyStatus.ON_LOAN);
            Loan loan = openLoanOwnedBy(MEMBER_NO);
            when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(3, 5));
            when(holdRepository.findUnsatisfiedByTitle(TITLE_ID)).thenReturn(List.of());
            echoLoanSave();

            Loan renewed = service.renew(member, BARCODE, NOW);

            assertEquals(1, renewed.renewalCount());
            assertSame(loan, captureSavedLoan());
        }

        @Test
        @DisplayName("does not extend the due date (renewal count only)")
        void doesNotChangeDueDate() {
            Member member = member();
            Copy copy = copyWith(CopyStatus.ON_LOAN);
            Loan loan = openLoanOwnedBy(MEMBER_NO);
            Instant originalDueAt = loan.dueAt();
            when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(3, 5));
            when(holdRepository.findUnsatisfiedByTitle(TITLE_ID)).thenReturn(List.of());
            echoLoanSave();

            assertEquals(originalDueAt, service.renew(member, BARCODE, NOW).dueAt());
        }

        @Test
        @DisplayName("refuses when there is no open loan for the barcode")
        void throwsWhenNoOpenLoan() {
            when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.empty());

            assertThrows(RenewalRefusedException.class,
                    () -> service.renew(member(), BARCODE, NOW));

            verifyNoInteractions(copyRepository, policyResolver, holdRepository);
        }

        @Test
        @DisplayName("refuses when the loan belongs to another member")
        void throwsWhenLoanBelongsToAnotherMember() {
            when(loanRepository.findOpenLoanByCopy(BARCODE))
                    .thenReturn(Optional.of(openLoanOwnedBy("M-OTHER")));

            RenewalRefusedException ex = assertThrows(RenewalRefusedException.class,
                    () -> service.renew(member(), BARCODE, NOW));

            assertTrue(ex.getMessage().contains("does not belong"));
            verifyNoInteractions(copyRepository, policyResolver, holdRepository);
        }

        @Test
        @DisplayName("refuses once the renewal limit has been reached")
        void throwsWhenRenewalLimitReached() {
            Member member = member();
            Copy copy = copyWith(CopyStatus.ON_LOAN);
            Loan loan = openLoanOwnedBy(MEMBER_NO);
            loan.recordRenewal();
            loan.recordRenewal();
            when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(2, 5));

            assertThrows(RenewalRefusedException.class,
                    () -> service.renew(member, BARCODE, NOW));

            assertEquals(2, loan.renewalCount(), "a refused renewal must not be counted");
            verify(loanRepository, never()).save(any(Loan.class));
            verifyNoInteractions(holdRepository);
        }

        @Test
        @DisplayName("refuses immediately under a zero-renewal policy")
        void throwsWhenRenewalLimitIsZero() {
            Member member = member();
            Copy copy = copyWith(CopyStatus.ON_LOAN);
            Loan loan = openLoanOwnedBy(MEMBER_NO);
            when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(0, 5));

            assertThrows(RenewalRefusedException.class,
                    () -> service.renew(member, BARCODE, NOW));

            assertEquals(0, loan.renewalCount());
        }

        @Test
        @DisplayName("refuses when any unsatisfied hold exists on the title")
        void throwsWhenUnsatisfiedHoldExists() {
            Member member = member();
            Copy copy = copyWith(CopyStatus.ON_LOAN);
            Loan loan = openLoanOwnedBy(MEMBER_NO);
            when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(3, 5));
            when(holdRepository.findUnsatisfiedByTitle(TITLE_ID))
                    .thenReturn(List.of(new Hold("H-1", TITLE_ID, "M-OTHER", 1, NOW)));

            RenewalRefusedException ex = assertThrows(RenewalRefusedException.class,
                    () -> service.renew(member, BARCODE, NOW));

            assertTrue(ex.getMessage().contains("hold"));
            assertEquals(0, loan.renewalCount());
            verify(loanRepository, never()).save(any(Loan.class));
        }

        @Test
        @DisplayName("also refuses when the only unsatisfied hold is the borrower's own")
        void throwsWhenTheOnlyHoldBelongsToTheRenewingMember() {
            Member member = member();
            Copy copy = copyWith(CopyStatus.ON_LOAN);
            Loan loan = openLoanOwnedBy(MEMBER_NO);
            when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));
            when(policyResolver.policyFor(member, copy)).thenReturn(policy(3, 5));
            when(holdRepository.findUnsatisfiedByTitle(TITLE_ID))
                    .thenReturn(List.of(new Hold("H-1", TITLE_ID, MEMBER_NO, 1, NOW)));

            assertThrows(RenewalRefusedException.class,
                    () -> service.renew(member, BARCODE, NOW));
        }

        @Test
        @DisplayName("propagates NoSuchElementException when the loan references a missing copy")
        void propagatesNoSuchElementWhenCopyMissing() {
            Member member = member();
            when(loanRepository.findOpenLoanByCopy(BARCODE))
                    .thenReturn(Optional.of(openLoanOwnedBy(MEMBER_NO)));
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.empty());

            assertThrows(NoSuchElementException.class,
                    () -> service.renew(member, BARCODE, NOW));
        }
    }

    // ------------------------------------------------------------- returnCopy

    @Nested
    @DisplayName("returnCopy")
    class ReturnCopy {

        @Test
        @DisplayName("records the return time on the loan and saves it")
        void recordsReturnTimeAndSavesLoan() {
            Loan loan = openLoanOwnedBy(MEMBER_NO);
            when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copyWith(CopyStatus.ON_LOAN)));

            service.returnCopy(BARCODE, NOW);

            assertEquals(Optional.of(NOW), loan.returnedAt());
            assertFalse(loan.isOpen());
            assertSame(loan, captureSavedLoan());
        }

        @Test
        @DisplayName("marks the copy AVAILABLE and saves it")
        void marksCopyAvailableAndSavesIt() {
            Copy copy = copyWith(CopyStatus.ON_LOAN);
            when(loanRepository.findOpenLoanByCopy(BARCODE))
                    .thenReturn(Optional.of(openLoanOwnedBy(MEMBER_NO)));
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));

            service.returnCopy(BARCODE, NOW);

            assertEquals(CopyStatus.AVAILABLE, copy.status());
            verify(copyRepository).save(copy);
        }

        @Test
        @DisplayName("throws when the copy has no open loan")
        void throwsWhenNoOpenLoan() {
            when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.empty());

            assertThrows(CopyUnavailableException.class, () -> service.returnCopy(BARCODE, NOW));

            verifyNoInteractions(copyRepository);
            verify(loanRepository, never()).save(any(Loan.class));
        }

        @Test
        @DisplayName("has already closed the loan when the copy lookup fails (non-atomic)")
        void closesLoanEvenWhenCopyLookupFails() {
            Loan loan = openLoanOwnedBy(MEMBER_NO);
            when(loanRepository.findOpenLoanByCopy(BARCODE)).thenReturn(Optional.of(loan));
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.empty());

            assertThrows(NoSuchElementException.class, () -> service.returnCopy(BARCODE, NOW));

            // Documents current behaviour: the loan is persisted as returned even though the
            // copy was never flipped back to AVAILABLE.
            verify(loanRepository).save(loan);
            assertFalse(loan.isOpen());
        }

        @Test
        @DisplayName("marking a copy AVAILABLE is not conditional on the previous status")
        void marksAvailableFromAnyStatus() {
            Copy copy = copyWith(CopyStatus.LOST);
            when(loanRepository.findOpenLoanByCopy(BARCODE))
                    .thenReturn(Optional.of(openLoanOwnedBy(MEMBER_NO)));
            when(copyRepository.findByBarcode(BARCODE)).thenReturn(Optional.of(copy));

            service.returnCopy(BARCODE, NOW);

            assertEquals(CopyStatus.AVAILABLE, copy.status());
        }
    }

    // ----------------------------------------------------------- overdueLoans

    @Nested
    @DisplayName("overdueLoans")
    class OverdueLoans {

        @Test
        @DisplayName("delegates to the repository using the supplied instant")
        void delegatesToRepository() {
            List<Loan> due = List.of(openLoanOwnedBy(MEMBER_NO));
            when(loanRepository.findOpenLoansDueBefore(NOW)).thenReturn(due);

            assertSame(due, service.overdueLoans(NOW));
            verify(loanRepository, times(1)).findOpenLoansDueBefore(NOW);
            verifyNoInteractions(copyRepository, holdRepository, policyResolver);
        }

        @Test
        @DisplayName("returns an empty list when nothing is overdue")
        void returnsEmptyWhenNoneDue() {
            when(loanRepository.findOpenLoansDueBefore(NOW)).thenReturn(List.of());

            assertTrue(service.overdueLoans(NOW).isEmpty());
        }
    }
}
```

### `HoldQueueServiceTest.java`

```java
package ie.ardaralibraries.bookline.circulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldQueueServiceTest {

    private static final String TITLE_ID = "T-1";
    private static final Instant T0 = Instant.parse("2024-06-01T09:00:00Z");

    @Mock
    private HoldRepository holdRepository;

    private HoldQueueService service;

    @BeforeEach
    void setUp() {
        service = new HoldQueueService(holdRepository);
    }

    private static Hold hold(String id, int priorityClass, Instant placedAt) {
        return new Hold(id, TITLE_ID, "M-" + id, priorityClass, placedAt);
    }

    @Test
    @DisplayName("nextHoldFor returns empty when the queue is empty")
    void nextHoldForReturnsEmptyWhenNoHolds() {
        when(holdRepository.findUnsatisfiedByTitle(TITLE_ID)).thenReturn(List.of());

        assertTrue(service.nextHoldFor(TITLE_ID).isEmpty());
    }

    @Test
    @DisplayName("nextHoldFor returns the only hold when the queue has one entry")
    void nextHoldForReturnsSingleHold() {
        Hold only = hold("H-1", 5, T0);
        when(holdRepository.findUnsatisfiedByTitle(TITLE_ID)).thenReturn(List.of(only));

        assertSame(only, service.nextHoldFor(TITLE_ID).orElseThrow());
    }

    @Test
    @DisplayName("nextHoldFor orders by priority class ascending, regardless of input order")
    void nextHoldForOrdersByPriorityClass() {
        Hold low = hold("H-low", 9, T0);
        Hold high = hold("H-high", 1, T0.plusSeconds(60));
        Hold mid = hold("H-mid", 4, T0);
        when(holdRepository.findUnsatisfiedByTitle(TITLE_ID)).thenReturn(List.of(low, mid, high));

        assertEquals("H-high", service.nextHoldFor(TITLE_ID).orElseThrow().id());
    }

    @Test
    @DisplayName("nextHoldFor breaks ties within a priority class by earliest placement")
    void nextHoldForBreaksTiesByPlacedAt() {
        Hold later = hold("H-later", 2, T0.plusSeconds(3600));
        Hold earlier = hold("H-earlier", 2, T0);
        when(holdRepository.findUnsatisfiedByTitle(TITLE_ID)).thenReturn(List.of(later, earlier));

        assertEquals("H-earlier", service.nextHoldFor(TITLE_ID).orElseThrow().id());
    }

    @Test
    @DisplayName("nextHoldFor lets priority class beat an earlier placement time")
    void priorityClassOutranksPlacementTime() {
        Hold earlierButLowerPriority = hold("H-old", 3, T0);
        Hold laterButHigherPriority = hold("H-new", 1, T0.plusSeconds(86_400));
        when(holdRepository.findUnsatisfiedByTitle(TITLE_ID))
                .thenReturn(List.of(earlierButLowerPriority, laterButHigherPriority));

        assertEquals("H-new", service.nextHoldFor(TITLE_ID).orElseThrow().id());
    }

    @Test
    @DisplayName("nextHoldFor is a read-only query: nothing is marked or saved")
    void nextHoldForDoesNotMutateOrPersist() {
        Hold head = hold("H-1", 1, T0);
        when(holdRepository.findUnsatisfiedByTitle(TITLE_ID)).thenReturn(List.of(head));

        service.nextHoldFor(TITLE_ID);

        assertFalse(head.isSatisfied());
        verify(holdRepository, never()).save(any(Hold.class));
    }

    @Test
    @DisplayName("satisfyNext marks the head of the queue satisfied and saves it")
    void satisfyNextMarksAndSavesHead() {
        Hold head = hold("H-head", 1, T0);
        Hold tail = hold("H-tail", 1, T0.plusSeconds(10));
        when(holdRepository.findUnsatisfiedByTitle(TITLE_ID)).thenReturn(List.of(tail, head));

        Optional<Hold> satisfied = service.satisfyNext(TITLE_ID);

        assertSame(head, satisfied.orElseThrow());
        assertTrue(head.isSatisfied());
        assertFalse(tail.isSatisfied(), "only the head of the queue may be satisfied");
        verify(holdRepository).save(head);
    }

    @Test
    @DisplayName("satisfyNext is a no-op when the queue is empty")
    void satisfyNextIsNoOpWhenQueueEmpty() {
        when(holdRepository.findUnsatisfiedByTitle(TITLE_ID)).thenReturn(List.of());

        assertTrue(service.satisfyNext(TITLE_ID).isEmpty());
        verify(holdRepository, never()).save(any(Hold.class));
    }
}
```

### `FineCalculatorTest.java`

```java
package ie.ardaralibraries.bookline.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link FineCalculator} has no collaborators, so nothing is mocked here. The class is a pure
 * function of (rate, replacement cost, amount accrued so far).
 */
class FineCalculatorTest {

    private final FineCalculator calculator = new FineCalculator();

    private static OverdueLoanFacts facts(String dailyRate, String replacementCost) {
        return new OverdueLoanFacts("L-1", LocalDate.of(2024, 6, 1), Optional.empty(),
                new BigDecimal(dailyRate), new BigDecimal(replacementCost));
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    @DisplayName("charges the full daily rate while there is ample headroom")
    void chargesFullRateWhenHeadroomExceedsRate() {
        assertAmount("0.50", calculator.dailyIncrement(facts("0.50", "20.00"), new BigDecimal("2.00")));
    }

    @Test
    @DisplayName("charges the full daily rate when headroom is exactly the rate")
    void chargesFullRateWhenHeadroomEqualsRate() {
        assertAmount("0.50", calculator.dailyIncrement(facts("0.50", "20.00"), new BigDecimal("19.50")));
    }

    @Test
    @DisplayName("charges the whole cap on the first day when nothing has accrued yet")
    void chargesFullRateWhenNothingAccrued() {
        assertAmount("0.50", calculator.dailyIncrement(facts("0.50", "20.00"), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("clamps the final increment to the remaining headroom")
    void clampsFinalIncrementToRemainingHeadroom() {
        assertAmount("0.20", calculator.dailyIncrement(facts("0.50", "20.00"), new BigDecimal("19.80")));
    }

    @Test
    @DisplayName("returns zero once the accrued total equals the replacement cost")
    void returnsZeroWhenAccruedEqualsCap() {
        assertAmount("0", calculator.dailyIncrement(facts("0.50", "20.00"), new BigDecimal("20.00")));
    }

    @Test
    @DisplayName("returns zero when the accrued total has somehow overshot the cap")
    void returnsZeroWhenAccruedExceedsCap() {
        assertAmount("0", calculator.dailyIncrement(facts("0.50", "20.00"), new BigDecimal("25.00")));
    }

    @Test
    @DisplayName("returns zero for a zero replacement cost")
    void returnsZeroWhenCapIsZero() {
        assertSame(BigDecimal.ZERO,
                calculator.dailyIncrement(facts("0.50", "0.00"), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("treats a zero daily rate as a zero increment without exhausting the cap")
    void returnsZeroForZeroDailyRate() {
        assertAmount("0", calculator.dailyIncrement(facts("0.00", "20.00"), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("returns one of its operands, so the scale of the input is preserved")
    void preservesScaleOfTheReturnedOperand() {
        BigDecimal clamped = calculator.dailyIncrement(facts("0.500", "20.00"), new BigDecimal("19.80"));
        assertEquals(2, clamped.scale(), "clamped value comes from the cap subtraction");

        BigDecimal full = calculator.dailyIncrement(facts("0.500", "20.00"), BigDecimal.ZERO);
        assertEquals(3, full.scale(), "unclamped value comes from the configured rate");
    }

    @ParameterizedTest(name = "rate {0}, cap {1}, accrued {2} -> {3}")
    @CsvSource({
            "0.10, 5.00,  0.00, 0.10",
            "0.10, 5.00,  4.95, 0.05",
            "0.10, 5.00,  5.00, 0",
            "1.00, 1.00,  0.00, 1.00",
            "2.50, 1.00,  0.00, 1.00",
            "0.25, 10.00, 9.99, 0.01"
    })
    void dailyIncrementTable(String rate, String cap, String accrued, String expected) {
        assertAmount(expected, calculator.dailyIncrement(facts(rate, cap), new BigDecimal(accrued)));
    }
}
```

### `FineAccrualServiceTest.java`

```java
package ie.ardaralibraries.bookline.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FineAccrualServiceTest {

    private static final String LOAN_ID = "L-1";
    private static final BigDecimal RATE = new BigDecimal("0.50");
    private static final BigDecimal REPLACEMENT_COST = new BigDecimal("20.00");

    @Mock
    private FineAccrualRepository accrualRepository;
    @Mock
    private BillingCalendar calendar;
    @Mock
    private FineCalculator calculator;

    private FineAccrualService service;

    @BeforeEach
    void setUp() {
        service = new FineAccrualService(accrualRepository, calendar, calculator);
    }

    // ---------------------------------------------------------------- helpers

    private static LocalDate june(int day) {
        return LocalDate.of(2024, 6, day);
    }

    /** Midday UTC is 13:00 in Europe/Dublin during June, so the local date is unambiguous. */
    private static Instant middayUtcOn(LocalDate date) {
        return date.atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    private static OverdueLoanFacts openFacts(LocalDate dueDate) {
        return new OverdueLoanFacts(LOAN_ID, dueDate, Optional.empty(), RATE, REPLACEMENT_COST);
    }

    private static OverdueLoanFacts returnedFacts(LocalDate dueDate, LocalDate returnedDate) {
        return new OverdueLoanFacts(LOAN_ID, dueDate, Optional.of(returnedDate), RATE, REPLACEMENT_COST);
    }

    private static OverdueLoanFacts cappedFacts(LocalDate dueDate, String cap) {
        return new OverdueLoanFacts(LOAN_ID, dueDate, Optional.empty(), RATE, new BigDecimal(cap));
    }

    private void libraryAlwaysOpen() {
        when(calendar.isOpenOn(any(LocalDate.class))).thenReturn(true);
    }

    private void noExistingAccruals() {
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of());
    }

    private void echoSave() {
        when(accrualRepository.save(any(FineAccrual.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** Delegates the mock to the real calculation so cap behaviour stays honest. */
    private void useRealCalculator() {
        FineCalculator real = new FineCalculator();
        when(calculator.dailyIncrement(any(OverdueLoanFacts.class), any(BigDecimal.class)))
                .thenAnswer(i -> real.dailyIncrement(i.getArgument(0), i.getArgument(1)));
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("writes one accrual per day strictly between the due date and today")
    void writesOneAccrualPerDayBetweenDueDateAndToday() {
        noExistingAccruals();
        libraryAlwaysOpen();
        useRealCalculator();
        echoSave();

        List<FineAccrual> written = service.accrueUpTo(openFacts(june(1)), middayUtcOn(june(5)));

        assertEquals(List.of(june(2), june(3), june(4)),
                written.stream().map(FineAccrual::accrualDate).toList());
        written.forEach(a -> assertAmount("0.50", a.amount()));
        written.forEach(a -> assertEquals(LOAN_ID, a.loanId()));
    }

    @Test
    @DisplayName("does not charge for the due date itself (one grace day)")
    void doesNotChargeForTheDueDate() {
        noExistingAccruals();
        libraryAlwaysOpen();
        useRealCalculator();
        echoSave();

        List<FineAccrual> written = service.accrueUpTo(openFacts(june(1)), middayUtcOn(june(3)));

        assertEquals(List.of(june(2)), written.stream().map(FineAccrual::accrualDate).toList());
    }

    @Test
    @DisplayName("does not charge for the current day; the window ends before today")
    void doesNotChargeForToday() {
        noExistingAccruals();
        libraryAlwaysOpen();
        useRealCalculator();
        echoSave();

        List<FineAccrual> written = service.accrueUpTo(openFacts(june(1)), middayUtcOn(june(4)));

        assertTrue(written.stream().noneMatch(a -> a.accrualDate().equals(june(4))));
        assertEquals(2, written.size());
    }

    @Test
    @DisplayName("writes nothing while the loan is not yet overdue")
    void writesNothingWhenNotYetOverdue() {
        noExistingAccruals();

        assertTrue(service.accrueUpTo(openFacts(june(10)), middayUtcOn(june(5))).isEmpty());

        verify(accrualRepository, never()).save(any(FineAccrual.class));
        verifyNoInteractions(calendar, calculator);
    }

    @Test
    @DisplayName("writes nothing on the first day after the due date")
    void writesNothingOnTheDayAfterTheDueDate() {
        noExistingAccruals();

        assertTrue(service.accrueUpTo(openFacts(june(4)), middayUtcOn(june(5))).isEmpty());

        verifyNoInteractions(calendar, calculator);
    }

    @Test
    @DisplayName("uses the return date, not today, as the end of the window")
    void usesReturnDateAsEndOfWindow() {
        noExistingAccruals();
        libraryAlwaysOpen();
        useRealCalculator();
        echoSave();

        List<FineAccrual> written =
                service.accrueUpTo(returnedFacts(june(1), june(5)), middayUtcOn(june(20)));

        assertEquals(List.of(june(2), june(3), june(4)),
                written.stream().map(FineAccrual::accrualDate).toList());
    }

    @Test
    @DisplayName("does not charge for the day the item was returned")
    void doesNotChargeForTheReturnDay() {
        noExistingAccruals();

        assertTrue(service.accrueUpTo(returnedFacts(june(1), june(2)), middayUtcOn(june(20))).isEmpty());

        verifyNoInteractions(calendar, calculator);
    }

    @Test
    @DisplayName("skips days on which the library is closed")
    void skipsClosedDays() {
        noExistingAccruals();
        libraryAlwaysOpen();
        when(calendar.isOpenOn(june(3))).thenReturn(false);
        useRealCalculator();
        echoSave();

        List<FineAccrual> written = service.accrueUpTo(openFacts(june(1)), middayUtcOn(june(5)));

        assertEquals(List.of(june(2), june(4)),
                written.stream().map(FineAccrual::accrualDate).toList());
        verify(calculator, times(2)).dailyIncrement(any(OverdueLoanFacts.class), any(BigDecimal.class));
    }

    @Test
    @DisplayName("writes nothing when the library was closed for the whole window")
    void writesNothingWhenLibraryClosedThroughout() {
        noExistingAccruals();
        when(calendar.isOpenOn(any(LocalDate.class))).thenReturn(false);

        assertTrue(service.accrueUpTo(openFacts(june(1)), middayUtcOn(june(5))).isEmpty());

        verify(accrualRepository, never()).save(any(FineAccrual.class));
        verifyNoInteractions(calculator);
    }

    @Test
    @DisplayName("is idempotent: days already accrued are skipped and not re-charged")
    void skipsDaysAlreadyAccrued() {
        noExistingAccruals();
        libraryAlwaysOpen();
        when(accrualRepository.existsForLoanAndDate(LOAN_ID, june(2))).thenReturn(true);
        useRealCalculator();
        echoSave();

        List<FineAccrual> written = service.accrueUpTo(openFacts(june(1)), middayUtcOn(june(5)));

        assertEquals(List.of(june(3), june(4)),
                written.stream().map(FineAccrual::accrualDate).toList());
        verify(accrualRepository, never()).save(new FineAccrual(LOAN_ID, june(2), RATE));
    }

    @Test
    @DisplayName("seeds the running total from fines already on the loan")
    void seedsRunningTotalFromExistingAccruals() {
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of(
                new FineAccrual(LOAN_ID, june(2), new BigDecimal("0.50")),
                new FineAccrual(LOAN_ID, june(3), new BigDecimal("0.75"))));
        libraryAlwaysOpen();
        useRealCalculator();
        echoSave();

        service.accrueUpTo(openFacts(june(1)), middayUtcOn(june(5)));

        ArgumentCaptor<BigDecimal> accrued = ArgumentCaptor.forClass(BigDecimal.class);
        verify(calculator, times(3)).dailyIncrement(any(OverdueLoanFacts.class), accrued.capture());
        assertAmount("1.25", accrued.getAllValues().get(0));
    }

    @Test
    @DisplayName("passes a running total that grows by each increment it just wrote")
    void passesGrowingRunningTotalToCalculator() {
        noExistingAccruals();
        libraryAlwaysOpen();
        useRealCalculator();
        echoSave();

        service.accrueUpTo(openFacts(june(1)), middayUtcOn(june(5)));

        ArgumentCaptor<BigDecimal> accrued = ArgumentCaptor.forClass(BigDecimal.class);
        verify(calculator, times(3)).dailyIncrement(any(OverdueLoanFacts.class), accrued.capture());
        assertAmount("0.00", accrued.getAllValues().get(0));
        assertAmount("0.50", accrued.getAllValues().get(1));
        assertAmount("1.00", accrued.getAllValues().get(2));
    }

    @Test
    @DisplayName("stops accruing once the replacement cost cap is reached")
    void stopsAtTheReplacementCostCap() {
        noExistingAccruals();
        libraryAlwaysOpen();
        useRealCalculator();
        echoSave();

        List<FineAccrual> written = service.accrueUpTo(cappedFacts(june(1), "1.20"), middayUtcOn(june(10)));

        assertEquals(List.of(june(2), june(3), june(4)),
                written.stream().map(FineAccrual::accrualDate).toList());
        assertAmount("0.50", written.get(0).amount());
        assertAmount("0.50", written.get(1).amount());
        assertAmount("0.20", written.get(2).amount());
        // the loop breaks rather than continuing to walk the remaining days
        verify(calculator, times(4)).dailyIncrement(any(OverdueLoanFacts.class), any(BigDecimal.class));
        verify(calendar, times(4)).isOpenOn(any(LocalDate.class));
    }

    @Test
    @DisplayName("writes nothing when existing fines already exhaust the cap")
    void writesNothingWhenAlreadyAtCap() {
        when(accrualRepository.findByLoan(LOAN_ID))
                .thenReturn(List.of(new FineAccrual(LOAN_ID, june(2), new BigDecimal("20.00"))));
        libraryAlwaysOpen();
        useRealCalculator();

        assertTrue(service.accrueUpTo(openFacts(june(1)), middayUtcOn(june(10))).isEmpty());

        verify(accrualRepository, never()).save(any(FineAccrual.class));
    }

    @Test
    @DisplayName("resolves 'today' in the library time zone, not UTC")
    void resolvesTodayInLibraryTimeZone() {
        assertEquals(ZoneId.of("Europe/Dublin"), FineAccrualService.LIBRARY_ZONE);
        noExistingAccruals();
        libraryAlwaysOpen();
        useRealCalculator();
        echoSave();

        // 23:30 UTC on 30 June is 00:30 on 1 July in Dublin (IST, UTC+1), so 30 June is chargeable.
        List<FineAccrual> written =
                service.accrueUpTo(openFacts(june(28)), Instant.parse("2024-06-30T23:30:00Z"));

        assertEquals(List.of(june(29), june(30)),
                written.stream().map(FineAccrual::accrualDate).toList());
    }

    @Test
    @DisplayName("returns the instances handed back by the repository, not the local ones")
    void returnsPersistedInstances() {
        FineAccrual persisted = new FineAccrual(LOAN_ID, june(2), new BigDecimal("9.99"));
        noExistingAccruals();
        libraryAlwaysOpen();
        useRealCalculator();
        when(accrualRepository.save(any(FineAccrual.class))).thenReturn(persisted);

        List<FineAccrual> written = service.accrueUpTo(openFacts(june(1)), middayUtcOn(june(3)));

        assertEquals(1, written.size());
        assertSame(persisted, written.get(0));
    }

    @Test
    @DisplayName("reads the loan's existing fines exactly once, before the loop")
    void readsExistingFinesOnce() {
        noExistingAccruals();
        libraryAlwaysOpen();
        useRealCalculator();
        echoSave();

        service.accrueUpTo(openFacts(june(1)), middayUtcOn(june(5)));

        verify(accrualRepository, times(1)).findByLoan(eq(LOAN_ID));
    }
}
```

### `FinesTest.java`

```java
package ie.ardaralibraries.bookline.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinesTest {

    private static final String LOAN_ID = "L-1";

    @Mock
    private FineAccrualRepository accrualRepository;

    private static FineAccrual accrual(int day, String amount) {
        return new FineAccrual(LOAN_ID, LocalDate.of(2024, 6, day), new BigDecimal(amount));
    }

    @Test
    @DisplayName("sums every accrual recorded against the loan")
    void sumsAllAccruals() {
        when(accrualRepository.findByLoan(LOAN_ID))
                .thenReturn(List.of(accrual(2, "0.50"), accrual(3, "0.50"), accrual(4, "0.25")));

        assertEquals(0, new BigDecimal("1.25").compareTo(Fines.totalFor(accrualRepository, LOAN_ID)));
    }

    @Test
    @DisplayName("returns zero for a loan with no accruals")
    void returnsZeroWhenNoAccruals() {
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of());

        assertSame(BigDecimal.ZERO, Fines.totalFor(accrualRepository, LOAN_ID));
    }

    @Test
    @DisplayName("returns the single amount unchanged when only one accrual exists")
    void returnsSingleAmount() {
        when(accrualRepository.findByLoan(LOAN_ID)).thenReturn(List.of(accrual(2, "0.50")));

        assertEquals(0, new BigDecimal("0.50").compareTo(Fines.totalFor(accrualRepository, LOAN_ID)));
    }

    @Test
    @DisplayName("queries the repository with the loan id it was given")
    void queriesRepositoryWithLoanId() {
        when(accrualRepository.findByLoan("L-OTHER")).thenReturn(List.of());

        Fines.totalFor(accrualRepository, "L-OTHER");

        verify(accrualRepository).findByLoan("L-OTHER");
    }
}
```

---

### Build dependencies

`junit-jupiter` (includes `junit-jupiter-params`, which the two parameterised tests need) and `mockito-junit-jupiter`:

```xml
<dependency>
  <groupId>org.junit.jupiter</groupId>
  <artifactId>junit-jupiter</artifactId>
  <version>5.10.2</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.mockito</groupId>
  <artifactId>mockito-junit-jupiter</artifactId>
  <version>5.11.0</version>
  <scope>test</scope>
</dependency>
```

### Coverage

Every branch in `CirculationService`, `HoldQueueService`, `FineCalculator`, `FineAccrualService` and `Fines` is exercised, including both sides of each guard and the `break` versus loop-exhaustion paths in `accrueUpTo`. What is *not* covered, deliberately: the trivial accessors on `Copy`, `Loan`, `Member` and `Hold` are only hit incidentally, and I have not written tests that assert getters return what the constructor was given. `Copy.replacementCost()` and `LibraryCalendar` are not covered at all because nothing in either file calls them.

The gap that coverage will not show you is the three ambiguities at the top. High line coverage over unverified behaviour is exactly how an off-by-one gets frozen into a regression suite — so please confirm the fine window, the own-hold renewal block, and the missing due-date extension before you merge these, rather than after.
