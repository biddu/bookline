package ie.ardaralibraries.bookline.circulation;

import java.time.LocalDate;
import java.util.Objects;

public final class Loan {

    private final Barcode copy;
    private final MembershipNumber member;
    private final LocalDate checkedOutAt;
    private final LocalDate dueAt;
    private LocalDate returnedAt;
    private int renewalCount;

    public Loan(Barcode copy, MembershipNumber member,
                LocalDate checkedOutAt, LocalDate dueAt) {
        this.copy = Objects.requireNonNull(copy, "copy");
        this.member = Objects.requireNonNull(member, "member");
        this.checkedOutAt = Objects.requireNonNull(checkedOutAt, "checkedOutAt");
        this.dueAt = Objects.requireNonNull(dueAt, "dueAt");
        if (!dueAt.isAfter(checkedOutAt)) {
            throw new IllegalArgumentException(
                "dueAt must be strictly after checkedOutAt");
        }
    }

    public void markReturned(LocalDate on) {
        Objects.requireNonNull(on, "on");
        if (returnedAt != null) {
            throw new IllegalStateException(
                "loan was already returned on " + returnedAt);
        }
        if (on.isBefore(checkedOutAt)) {
            throw new IllegalArgumentException(
                "return date cannot precede checkout");
        }
        this.returnedAt = on;
    }

    public boolean isOpen()               { return returnedAt == null; }
    public Barcode copy()                 { return copy; }
    public MembershipNumber member()      { return member; }
    public LocalDate checkedOutAt()       { return checkedOutAt; }
    public LocalDate dueAt()              { return dueAt; }
    public LocalDate returnedAt()         { return returnedAt; }
    public int renewalCount()             { return renewalCount; }
}
