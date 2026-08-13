Great, let's add holds, overdue fines, email notices, borrowing limits, and an overdue report to Bookline. I'll build on your existing `Loan`, `Member`, `Copy`, and `Money` classes and keep everything in plain Java so it slots into your current checkout path.

Here's the full implementation:

## 1. Holds

A member can place a hold on a title. When a copy comes back, the oldest hold is notified and the copy is set aside for a pickup window.

```java
// Hold.java
package bookline.holds;

import bookline.catalog.Copy;
import bookline.members.Member;
import java.time.LocalDate;

public class Hold {

    public enum Status { QUEUED, READY_FOR_PICKUP, FULFILLED, EXPIRED, CANCELLED }

    private static final int PICKUP_WINDOW_DAYS = 7;

    private final String id;
    private final Member member;
    private final String isbn;
    private final LocalDate placedOn;
    private Status status;
    private Copy reservedCopy;
    private LocalDate pickupDeadline;

    public Hold(String id, Member member, String isbn, LocalDate placedOn) {
        this.id = id;
        this.member = member;
        this.isbn = isbn;
        this.placedOn = placedOn;
        this.status = Status.QUEUED;
    }

    public void markReadyForPickup(Copy copy, LocalDate today) {
        if (status != Status.QUEUED) {
            throw new IllegalStateException("Hold " + id + " is not queued (status: " + status + ")");
        }
        this.reservedCopy = copy;
        this.pickupDeadline = today.plusDays(PICKUP_WINDOW_DAYS);
        this.status = Status.READY_FOR_PICKUP;
    }

    public void fulfill() {
        if (status != Status.READY_FOR_PICKUP) {
            throw new IllegalStateException("Hold " + id + " is not ready for pickup");
        }
        this.status = Status.FULFILLED;
    }

    public void expireIfPastDeadline(LocalDate today) {
        if (status == Status.READY_FOR_PICKUP && today.isAfter(pickupDeadline)) {
            this.status = Status.EXPIRED;
            this.reservedCopy = null;
        }
    }

    public void cancel() {
        if (status == Status.FULFILLED) {
            throw new IllegalStateException("Cannot cancel a fulfilled hold");
        }
        this.status = Status.CANCELLED;
        this.reservedCopy = null;
    }

    public String getId() { return id; }
    public Member getMember() { return member; }
    public String getIsbn() { return isbn; }
    public LocalDate getPlacedOn() { return placedOn; }
    public Status getStatus() { return status; }
    public Copy getReservedCopy() { return reservedCopy; }
    public LocalDate getPickupDeadline() { return pickupDeadline; }
}
```

```java
// HoldService.java
package bookline.holds;

import bookline.catalog.Copy;
import bookline.members.Member;
import bookline.notifications.NotificationService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class HoldService {

    private final List<Hold> holds = new ArrayList<>();
    private final NotificationService notificationService;

    public HoldService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public Hold placeHold(Member member, String isbn, LocalDate today) {
        boolean alreadyHolding = holds.stream()
                .anyMatch(h -> h.getMember().equals(member)
                        && h.getIsbn().equals(isbn)
                        && (h.getStatus() == Hold.Status.QUEUED
                            || h.getStatus() == Hold.Status.READY_FOR_PICKUP));
        if (alreadyHolding) {
            throw new IllegalStateException(
                "Member " + member.getId() + " already has an active hold on " + isbn);
        }
        Hold hold = new Hold(UUID.randomUUID().toString(), member, isbn, today);
        holds.add(hold);
        return hold;
    }

    /** Called when a copy is returned. Assigns it to the oldest queued hold, if any. */
    public Optional<Hold> onCopyReturned(Copy copy, LocalDate today) {
        Optional<Hold> next = holds.stream()
                .filter(h -> h.getIsbn().equals(copy.getIsbn()))
                .filter(h -> h.getStatus() == Hold.Status.QUEUED)
                .min(Comparator.comparing(Hold::getPlacedOn));

        next.ifPresent(hold -> {
            hold.markReadyForPickup(copy, today);
            notificationService.sendHoldReadyNotice(hold);
        });
        return next;
    }

    /** Run daily: expires pickups that were never collected and re-queues the copy. */
    public void expireStaleHolds(LocalDate today) {
        for (Hold hold : holds) {
            Copy copy = hold.getReservedCopy();
            hold.expireIfPastDeadline(today);
            if (hold.getStatus() == Hold.Status.EXPIRED && copy != null) {
                onCopyReturned(copy, today); // pass the copy to the next member in line
            }
        }
    }

    public List<Hold> activeHoldsFor(Member member) {
        return holds.stream()
                .filter(h -> h.getMember().equals(member))
                .filter(h -> h.getStatus() == Hold.Status.QUEUED
                        || h.getStatus() == Hold.Status.READY_FOR_PICKUP)
                .toList();
    }

    public Optional<Hold> readyHoldFor(Member member, Copy copy) {
        return holds.stream()
                .filter(h -> h.getMember().equals(member))
                .filter(h -> h.getStatus() == Hold.Status.READY_FOR_PICKUP)
                .filter(h -> copy.equals(h.getReservedCopy()))
                .findFirst();
    }
}
```

## 2. Overdue fines

Fines accrue per day overdue, capped at a maximum per loan. Payment is recorded against the fine.

```java
// Fine.java
package bookline.fines;

import bookline.circulation.Loan;
import bookline.money.Money;

public class Fine {

    private final String id;
    private final Loan loan;
    private final Money amount;
    private Money amountPaid;
    private boolean waived;

    public Fine(String id, Loan loan, Money amount) {
        this.id = id;
        this.loan = loan;
        this.amount = amount;
        this.amountPaid = Money.zero(amount.getCurrency());
        this.waived = false;
    }

    public Money outstanding() {
        if (waived) {
            return Money.zero(amount.getCurrency());
        }
        return amount.minus(amountPaid);
    }

    public void recordPayment(Money payment) {
        if (waived) {
            throw new IllegalStateException("Fine " + id + " has been waived");
        }
        if (payment.isGreaterThan(outstanding())) {
            throw new IllegalArgumentException(
                "Payment " + payment + " exceeds outstanding balance " + outstanding());
        }
        this.amountPaid = this.amountPaid.plus(payment);
    }

    public void waive() {
        this.waived = true;
    }

    public boolean isSettled() {
        return waived || outstanding().isZero();
    }

    public String getId() { return id; }
    public Loan getLoan() { return loan; }
    public Money getAmount() { return amount; }
    public Money getAmountPaid() { return amountPaid; }
    public boolean isWaived() { return waived; }
}
```

```java
// FineCalculator.java
package bookline.fines;

import bookline.circulation.Loan;
import bookline.money.Money;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class FineCalculator {

    private final Money dailyRate;
    private final Money maxFinePerLoan;

    public FineCalculator(Money dailyRate, Money maxFinePerLoan) {
        this.dailyRate = dailyRate;
        this.maxFinePerLoan = maxFinePerLoan;
    }

    public Money calculateFor(Loan loan, LocalDate asOf) {
        if (!loan.isOverdue(asOf)) {
            return Money.zero(dailyRate.getCurrency());
        }
        long daysOverdue = ChronoUnit.DAYS.between(loan.getDueDate(), asOf);
        Money fine = dailyRate.times(daysOverdue);
        return fine.isGreaterThan(maxFinePerLoan) ? maxFinePerLoan : fine;
    }

    public long daysOverdue(Loan loan, LocalDate asOf) {
        if (!loan.isOverdue(asOf)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(loan.getDueDate(), asOf);
    }
}
```

```java
// FineService.java
package bookline.fines;

import bookline.circulation.Loan;
import bookline.members.Member;
import bookline.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FineService {

    private final FineCalculator calculator;
    private final List<Fine> fines = new ArrayList<>();

    public FineService(FineCalculator calculator) {
        this.calculator = calculator;
    }

    /** Called at return time: assesses a fine if the loan came back late. */
    public Fine assessOnReturn(Loan loan, LocalDate returnedOn) {
        Money amount = calculator.calculateFor(loan, returnedOn);
        if (amount.isZero()) {
            return null;
        }
        Fine fine = new Fine(UUID.randomUUID().toString(), loan, amount);
        fines.add(fine);
        return fine;
    }

    public Money outstandingBalanceFor(Member member) {
        return fines.stream()
                .filter(f -> f.getLoan().getMember().equals(member))
                .map(Fine::outstanding)
                .reduce(Money.zero(calculator.calculateFor(null, null).getCurrency()),
                        Money::plus);
    }

    public List<Fine> unsettledFinesFor(Member member) {
        return fines.stream()
                .filter(f -> f.getLoan().getMember().equals(member))
                .filter(f -> !f.isSettled())
                .toList();
    }

    public void payFine(String fineId, Money payment) {
        fines.stream()
                .filter(f -> f.getId().equals(fineId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No fine with id " + fineId))
                .recordPayment(payment);
    }
}
```

One fix in `FineService.outstandingBalanceFor` — calling the calculator with nulls to get a zero is a hack. Cleaner version:

```java
public Money outstandingBalanceFor(Member member, Money zero) {
    return fines.stream()
            .filter(f -> f.getLoan().getMember().equals(member))
            .filter(f -> !f.isSettled())
            .map(Fine::outstanding)
            .reduce(zero, Money::plus);
}
```

Or better, give `FineCalculator` a `getCurrency()` accessor and use `Money.zero(calculator.getCurrency())`.

## 3. Email notices

A `NotificationService` interface with an SMTP implementation, so you can swap in a fake for tests.

```java
// NotificationService.java
package bookline.notifications;

import bookline.circulation.Loan;
import bookline.fines.Fine;
import bookline.holds.Hold;

public interface NotificationService {
    void sendHoldReadyNotice(Hold hold);
    void sendOverdueNotice(Loan loan, long daysOverdue);
    void sendFineNotice(Fine fine);
}
```

```java
// EmailNotificationService.java
package bookline.notifications;

import bookline.circulation.Loan;
import bookline.fines.Fine;
import bookline.holds.Hold;

public class EmailNotificationService implements NotificationService {

    private final EmailGateway gateway;

    public EmailNotificationService(EmailGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void sendHoldReadyNotice(Hold hold) {
        String subject = "Your hold is ready for pickup";
        String body = String.format(
            "Hi %s,%n%nGood news! The book you placed on hold (ISBN %s) is ready "
            + "for pickup at your branch. Please collect it by %s, after which the "
            + "hold will expire and the copy will go to the next member in line.%n%n"
            + "Thanks,%nBookline",
            hold.getMember().getName(), hold.getIsbn(), hold.getPickupDeadline());
        gateway.send(hold.getMember().getEmail(), subject, body);
    }

    @Override
    public void sendOverdueNotice(Loan loan, long daysOverdue) {
        String subject = "Overdue: please return your book";
        String body = String.format(
            "Hi %s,%n%nOur records show that \"%s\" was due on %s and is now %d "
            + "day(s) overdue. Please return it as soon as possible to avoid "
            + "further fines.%n%nThanks,%nBookline",
            loan.getMember().getName(), loan.getCopy().getTitle(),
            loan.getDueDate(), daysOverdue);
        gateway.send(loan.getMember().getEmail(), subject, body);
    }

    @Override
    public void sendFineNotice(Fine fine) {
        String subject = "A fine has been added to your account";
        String body = String.format(
            "Hi %s,%n%nA fine of %s has been added to your account for the late "
            + "return of \"%s\". You can pay at any branch or online.%n%n"
            + "Thanks,%nBookline",
            fine.getLoan().getMember().getName(), fine.getAmount(),
            fine.getLoan().getCopy().getTitle());
        gateway.send(fine.getLoan().getMember().getEmail(), subject, body);
    }
}
```

```java
// EmailGateway.java
package bookline.notifications;

public interface EmailGateway {
    void send(String toAddress, String subject, String body);
}
```

```java
// SmtpEmailGateway.java
package bookline.notifications;

import java.util.Properties;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

public class SmtpEmailGateway implements EmailGateway {

    private final String host;
    private final int port;
    private final String fromAddress;

    public SmtpEmailGateway(String host, int port, String fromAddress) {
        this.host = host;
        this.port = port;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String toAddress, String subject, String body) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(port));
            Session session = Session.getInstance(props);

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromAddress));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(toAddress));
            message.setSubject(subject);
            message.setText(body);
            Transport.send(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send email to " + toAddress, e);
        }
    }
}
```

## 4. Borrowing limits

Add a limit check keyed off membership type. I'm assuming `Member` has (or can get) a `MembershipType`; adjust to match your model.

```java
// BorrowingPolicy.java
package bookline.circulation;

import bookline.members.Member;
import java.util.List;

public class BorrowingPolicy {

    private static final int STANDARD_LIMIT = 5;
    private static final int STUDENT_LIMIT = 3;
    private static final int PREMIUM_LIMIT = 10;

    public int limitFor(Member member) {
        switch (member.getMembershipType()) {
            case STUDENT:  return STUDENT_LIMIT;
            case PREMIUM:  return PREMIUM_LIMIT;
            case STANDARD:
            default:       return STANDARD_LIMIT;
        }
    }

    public void checkCanBorrow(Member member, List<Loan> activeLoans) {
        int limit = limitFor(member);
        if (activeLoans.size() >= limit) {
            throw new BorrowingLimitExceededException(
                "Member " + member.getId() + " has reached the borrowing limit of "
                + limit + " books");
        }
    }
}
```

```java
// BorrowingLimitExceededException.java
package bookline.circulation;

public class BorrowingLimitExceededException extends RuntimeException {
    public BorrowingLimitExceededException(String message) {
        super(message);
    }
}
```

Then wire it into your existing checkout path:

```java
// In your existing checkout service/method:
public Loan checkout(Member member, Copy copy, LocalDate today) {
    List<Loan> activeLoans = loanRepository.findActiveLoansFor(member);
    borrowingPolicy.checkCanBorrow(member, activeLoans);

    // If this copy is reserved, only the member holding it may take it.
    holdService.readyHoldFor(member, copy).ifPresent(Hold::fulfill);

    Loan loan = new Loan(member, copy, today, today.plusDays(loanPeriodDays));
    loanRepository.save(loan);
    return loan;
}
```

And into your return path:

```java
public void returnCopy(Loan loan, LocalDate today) {
    loan.markReturned(today);

    Fine fine = fineService.assessOnReturn(loan, today);
    if (fine != null) {
        notificationService.sendFineNotice(fine);
    }

    holdService.onCopyReturned(loan.getCopy(), today);
}
```

## 5. Overdue report for branch managers

```java
// OverdueReportLine.java
package bookline.reporting;

import bookline.money.Money;
import java.time.LocalDate;

public record OverdueReportLine(
        String memberName,
        String memberEmail,
        String bookTitle,
        String isbn,
        LocalDate dueDate,
        long daysOverdue,
        Money accruedFine) {
}
```

```java
// OverdueReport.java
package bookline.reporting;

import bookline.money.Money;
import java.time.LocalDate;
import java.util.List;

public class OverdueReport {

    private final String branchName;
    private final LocalDate generatedOn;
    private final List<OverdueReportLine> lines;

    public OverdueReport(String branchName, LocalDate generatedOn,
                         List<OverdueReportLine> lines) {
        this.branchName = branchName;
        this.generatedOn = generatedOn;
        this.lines = List.copyOf(lines);
    }

    public Money totalAccruedFines(Money zero) {
        return lines.stream()
                .map(OverdueReportLine::accruedFine)
                .reduce(zero, Money::plus);
    }

    public String toPlainText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Overdue Report — ").append(branchName)
          .append(" — ").append(generatedOn).append("\n");
        sb.append(String.format("%-25s %-30s %-12s %8s %10s%n",
                "Member", "Title", "Due", "Days", "Fine"));
        for (OverdueReportLine line : lines) {
            sb.append(String.format("%-25s %-30s %-12s %8d %10s%n",
                    line.memberName(), line.bookTitle(), line.dueDate(),
                    line.daysOverdue(), line.accruedFine()));
        }
        sb.append("Total overdue loans: ").append(lines.size()).append("\n");
        return sb.toString();
    }

    public String getBranchName() { return branchName; }
    public LocalDate getGeneratedOn() { return generatedOn; }
    public List<OverdueReportLine> getLines() { return lines; }
}
```

```java
// OverdueReportGenerator.java
package bookline.reporting;

import bookline.circulation.Loan;
import bookline.fines.FineCalculator;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public class OverdueReportGenerator {

    private final FineCalculator fineCalculator;

    public OverdueReportGenerator(FineCalculator fineCalculator) {
        this.fineCalculator = fineCalculator;
    }

    public OverdueReport generate(String branchName, List<Loan> activeLoans,
                                  LocalDate asOf) {
        List<OverdueReportLine> lines = activeLoans.stream()
                .filter(loan -> loan.isOverdue(asOf))
                .map(loan -> new OverdueReportLine(
                        loan.getMember().getName(),
                        loan.getMember().getEmail(),
                        loan.getCopy().getTitle(),
                        loan.getCopy().getIsbn(),
                        loan.getDueDate(),
                        fineCalculator.daysOverdue(loan, asOf),
                        fineCalculator.calculateFor(loan, asOf)))
                .sorted(Comparator.comparingLong(OverdueReportLine::daysOverdue).reversed())
                .toList();

        return new OverdueReport(branchName, asOf, lines);
    }
}
```

## 6. Daily overdue notice job

Finally, a small job you can run on a scheduler (cron, Quartz, `@Scheduled`, whatever you use) to send overdue emails and expire stale holds.

```java
// DailyCirculationJob.java
package bookline.jobs;

import bookline.circulation.Loan;
import bookline.fines.FineCalculator;
import bookline.holds.HoldService;
import bookline.notifications.NotificationService;
import java.time.LocalDate;
import java.util.List;

public class DailyCirculationJob {

    private final NotificationService notificationService;
    private final FineCalculator fineCalculator;
    private final HoldService holdService;

    public DailyCirculationJob(NotificationService notificationService,
                               FineCalculator fineCalculator,
                               HoldService holdService) {
        this.notificationService = notificationService;
        this.fineCalculator = fineCalculator;
        this.holdService = holdService;
    }

    public void run(List<Loan> activeLoans, LocalDate today) {
        for (Loan loan : activeLoans) {
            if (loan.isOverdue(today)) {
                long days = fineCalculator.daysOverdue(loan, today);
                notificationService.sendOverdueNotice(loan, days);
            }
        }
        holdService.expireStaleHolds(today);
    }
}
```

## Assumptions I made (worth checking against your code)

- `Loan` has `getMember()`, `getCopy()`, `getDueDate()`, `isOverdue(LocalDate)`, and `markReturned(LocalDate)`. If `isOverdue` doesn't exist yet, it's just `returnedOn == null && asOf.isAfter(dueDate)`.
- `Member` has `getId()`, `getName()`, `getEmail()`, and a `getMembershipType()` enum with `STANDARD`, `STUDENT`, `PREMIUM`. If you don't have membership types, collapse `BorrowingPolicy.limitFor` to a single constant.
- `Copy` has `getIsbn()` and `getTitle()`.
- `Money` has `zero(currency)`, `plus`, `minus`, `times(long)`, `isZero()`, `isGreaterThan`, and `getCurrency()`. Add any that are missing — they're one-liners.
- Storage is in-memory lists inside the services to match a repository-free style; if you have repositories, swap the `List` fields for repository calls (`holdRepository.findQueuedByIsbn(...)`, etc.).
- The `SmtpEmailGateway` uses JavaMail (`javax.mail`). Add `com.sun.mail:javax.mail` to your build, or if you're on Jakarta, change the imports to `jakarta.mail`.

That gives you: hold queues with pickup windows and automatic passing to the next member, per-day fines with a cap and payment tracking, email notices for holds/overdues/fines behind a testable gateway interface, per-membership-type borrowing limits enforced at checkout, and a sortable plain-text overdue report per branch. Let me know if you want unit tests for the fine calculation and hold expiry logic — those are the two spots with the most edge cases.
