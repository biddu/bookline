Here's a `Member` class for your library system. I've made a few design decisions worth flagging up front: the balance uses `BigDecimal` (never use `double` for money), the loans list is encapsulated so callers can't mutate it behind the member's back, and borrowing-history consent defaults to **false** so you're opt-in by default.

```java
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A member of the library system.
 */
public class Member {

    /** Membership tiers offered by the library. */
    public enum MembershipTier {
        STANDARD,
        STUDENT,
        SENIOR,
        PREMIUM
    }

    private static final AtomicLong MEMBER_NUMBER_SEQUENCE = new AtomicLong(1000);

    private final long memberNumber;
    private String name;
    private String email;
    private MembershipTier tier;
    private BigDecimal outstandingBalance;
    private final List<Loan> currentLoans;
    private boolean borrowingHistoryConsent;

    /**
     * Creates a new member with an auto-generated member number,
     * a zero fine balance, no loans, and no history consent.
     */
    public Member(String name, String email, MembershipTier tier) {
        this.memberNumber = MEMBER_NUMBER_SEQUENCE.incrementAndGet();
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.email = Objects.requireNonNull(email, "email must not be null");
        this.tier = Objects.requireNonNull(tier, "tier must not be null");
        this.outstandingBalance = BigDecimal.ZERO;
        this.currentLoans = new ArrayList<>();
        this.borrowingHistoryConsent = false;
    }

    // --- Identity ---

    public long getMemberNumber() {
        return memberNumber;
    }

    // --- Basic details ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = Objects.requireNonNull(email, "email must not be null");
    }

    public MembershipTier getTier() {
        return tier;
    }

    public void setTier(MembershipTier tier) {
        this.tier = Objects.requireNonNull(tier, "tier must not be null");
    }

    // --- Fines ---

    public BigDecimal getOutstandingBalance() {
        return outstandingBalance;
    }

    /** Adds a fine to the member's outstanding balance. */
    public void addFine(BigDecimal amount) {
        requirePositive(amount);
        outstandingBalance = outstandingBalance.add(amount);
    }

    /**
     * Records a payment against the outstanding balance.
     *
     * @throws IllegalArgumentException if the payment exceeds the balance owed
     */
    public void payFine(BigDecimal amount) {
        requirePositive(amount);
        if (amount.compareTo(outstandingBalance) > 0) {
            throw new IllegalArgumentException(
                "Payment of " + amount + " exceeds outstanding balance of " + outstandingBalance);
        }
        outstandingBalance = outstandingBalance.subtract(amount);
    }

    public boolean hasOutstandingBalance() {
        return outstandingBalance.compareTo(BigDecimal.ZERO) > 0;
    }

    private static void requirePositive(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
    }

    // --- Loans ---

    /** Returns a read-only view of the member's current loans. */
    public List<Loan> getCurrentLoans() {
        return Collections.unmodifiableList(currentLoans);
    }

    public void addLoan(Loan loan) {
        currentLoans.add(Objects.requireNonNull(loan, "loan must not be null"));
    }

    public boolean removeLoan(Loan loan) {
        return currentLoans.remove(loan);
    }

    public int getLoanCount() {
        return currentLoans.size();
    }

    // --- Borrowing-history consent ---

    public boolean hasBorrowingHistoryConsent() {
        return borrowingHistoryConsent;
    }

    /** Records that the member consents to the library keeping their borrowing history. */
    public void grantBorrowingHistoryConsent() {
        this.borrowingHistoryConsent = true;
    }

    /** Records that the member has withdrawn consent to keeping their borrowing history. */
    public void revokeBorrowingHistoryConsent() {
        this.borrowingHistoryConsent = false;
    }

    // --- Object contract ---

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Member)) {
            return false;
        }
        Member other = (Member) o;
        return memberNumber == other.memberNumber;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(memberNumber);
    }

    @Override
    public String toString() {
        return "Member{" +
            "memberNumber=" + memberNumber +
            ", name='" + name + '\'' +
            ", tier=" + tier +
            ", outstandingBalance=" + outstandingBalance +
            ", currentLoans=" + currentLoans.size() +
            '}';
    }
}
```

A few notes on the choices I made:

- **Fields are all `private`**, with `memberNumber` and `currentLoans` also `final`. Identity never changes, and the loans list reference is fixed even though its contents change.
- **Member number generation** uses a static `AtomicLong` starting above 1000, so numbers are unique and thread-safe without a database. In production you'd more likely have your persistence layer (or a dedicated ID service) assign this — if so, replace the sequence with a constructor parameter.
- **`BigDecimal` for the balance**, initialized to `BigDecimal.ZERO`. Floating-point types accumulate rounding errors with currency. All comparisons use `compareTo` rather than `equals`, since `equals` on `BigDecimal` is scale-sensitive (`0.00` != `0`).
- **The loans list is never exposed directly.** `getCurrentLoans()` returns an unmodifiable view, and mutations go through `addLoan`/`removeLoan` so the class stays in control of its own state.
- **Consent defaults to `false`** and is changed through explicit `grant`/`revoke` methods rather than a bare setter — that reads better at call sites and gives you an obvious place to add audit logging later (which you'll likely want for GDPR-style compliance).
- **`equals`/`hashCode` are based on member number only**, since it's the stable identity; names and emails can change.

This assumes a `Loan` class exists elsewhere in your system. If you'd like, I can sketch that next, along with a `Library` service that enforces per-tier loan limits and blocks borrowing when fines exceed a threshold.
