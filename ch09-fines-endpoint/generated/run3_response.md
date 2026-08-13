Here's a straightforward Spring Boot 3 (Java 17) implementation. Three read endpoints, JPA entities, DTOs so you're not leaking entities over the wire, and a single service holding the logic.

One thing I want to flag up front rather than bury at the bottom: because we're deferring auth, the member ID comes from the URL path and nothing verifies the caller *is* that member. That's fine for local dev, but it means the API is wide open to enumeration — `GET /api/members/2/fines` works just as well as `/api/members/1/fines`. I've structured the code so there's one obvious place to close that later (see `MemberAccessGuard` at the end), and I'd suggest not letting this reach any shared environment before it's wired up.

## Dependencies

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    runtimeOnly 'com.h2database:h2'            // swap for your real driver
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

## Entities

```java
package com.library.portal.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "members")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false, unique = true)
    private String email;

    private String phone;

    @Column(nullable = false)
    private LocalDate joinedOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipStatus status = MembershipStatus.ACTIVE;

    protected Member() { }   // JPA

    // getters / setters omitted for brevity — generate with your IDE or Lombok

    public Long getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public LocalDate getJoinedOn() { return joinedOn; }
    public MembershipStatus getStatus() { return status; }
}
```

```java
package com.library.portal.domain;

public enum MembershipStatus { ACTIVE, SUSPENDED, EXPIRED }
```

```java
package com.library.portal.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "loans")
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(nullable = false)
    private String itemTitle;

    @Column(nullable = false)
    private String itemBarcode;

    @Column(nullable = false)
    private LocalDate borrowedOn;

    @Column(nullable = false)
    private LocalDate dueOn;

    private LocalDate returnedOn;

    @Column(nullable = false)
    private int renewalCount;

    protected Loan() { }

    public Long getId() { return id; }
    public Member getMember() { return member; }
    public String getItemTitle() { return itemTitle; }
    public String getItemBarcode() { return itemBarcode; }
    public LocalDate getBorrowedOn() { return borrowedOn; }
    public LocalDate getDueOn() { return dueOn; }
    public LocalDate getReturnedOn() { return returnedOn; }
    public int getRenewalCount() { return renewalCount; }

    public boolean isOutstanding() { return returnedOn == null; }

    public boolean isOverdueAsOf(LocalDate today) {
        return isOutstanding() && dueOn.isBefore(today);
    }
}
```

```java
package com.library.portal.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "fines")
public class Fine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_id")
    private Loan loan;             // nullable: not every fine comes from a loan

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FineReason reason;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amountAssessed;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDate assessedOn;

    protected Fine() { }

    public Long getId() { return id; }
    public Member getMember() { return member; }
    public Loan getLoan() { return loan; }
    public FineReason getReason() { return reason; }
    public BigDecimal getAmountAssessed() { return amountAssessed; }
    public BigDecimal getAmountPaid() { return amountPaid; }
    public LocalDate getAssessedOn() { return assessedOn; }

    public BigDecimal getBalance() {
        return amountAssessed.subtract(amountPaid);
    }

    public boolean isOutstanding() {
        return getBalance().compareTo(BigDecimal.ZERO) > 0;
    }
}
```

```java
package com.library.portal.domain;

public enum FineReason { OVERDUE, DAMAGE, LOSS, PROCESSING }
```

## Repositories

```java
package com.library.portal.repository;

import com.library.portal.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> { }
```

```java
package com.library.portal.repository;

import com.library.portal.domain.Loan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    @Query("""
           select l from Loan l
           where l.member.id = :memberId
             and l.returnedOn is null
           order by l.dueOn asc
           """)
    List<Loan> findCurrentByMemberId(@Param("memberId") Long memberId);
}
```

```java
package com.library.portal.repository;

import com.library.portal.domain.Fine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FineRepository extends JpaRepository<Fine, Long> {

    @Query("""
           select f from Fine f
           left join fetch f.loan
           where f.member.id = :memberId
             and f.amountAssessed > f.amountPaid
           order by f.assessedOn asc
           """)
    List<Fine> findOutstandingByMemberId(@Param("memberId") Long memberId);
}
```

## DTOs

```java
package com.library.portal.api.dto;

import com.library.portal.domain.Member;
import com.library.portal.domain.MembershipStatus;

import java.time.LocalDate;

public record MemberProfileResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone,
        LocalDate joinedOn,
        MembershipStatus status
) {
    public static MemberProfileResponse from(Member m) {
        return new MemberProfileResponse(
                m.getId(), m.getFirstName(), m.getLastName(),
                m.getEmail(), m.getPhone(), m.getJoinedOn(), m.getStatus());
    }
}
```

```java
package com.library.portal.api.dto;

import com.library.portal.domain.Loan;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public record LoanResponse(
        Long id,
        String itemTitle,
        String itemBarcode,
        LocalDate borrowedOn,
        LocalDate dueOn,
        int renewalCount,
        boolean overdue,
        long daysUntilDue
) {
    public static LoanResponse from(Loan l, LocalDate today) {
        return new LoanResponse(
                l.getId(), l.getItemTitle(), l.getItemBarcode(),
                l.getBorrowedOn(), l.getDueOn(), l.getRenewalCount(),
                l.isOverdueAsOf(today),
                ChronoUnit.DAYS.between(today, l.getDueOn()));
    }
}
```

```java
package com.library.portal.api.dto;

import com.library.portal.domain.Fine;
import com.library.portal.domain.FineReason;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FineResponse(
        Long id,
        FineReason reason,
        String itemTitle,        // null when the fine isn't tied to a loan
        BigDecimal amountAssessed,
        BigDecimal amountPaid,
        BigDecimal balance,
        LocalDate assessedOn
) {
    public static FineResponse from(Fine f) {
        return new FineResponse(
                f.getId(), f.getReason(),
                f.getLoan() == null ? null : f.getLoan().getItemTitle(),
                f.getAmountAssessed(), f.getAmountPaid(),
                f.getBalance(), f.getAssessedOn());
    }
}
```

```java
package com.library.portal.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record OutstandingFinesResponse(
        BigDecimal totalOutstanding,
        List<FineResponse> fines
) { }
```

## Service

```java
package com.library.portal.service;

import com.library.portal.api.dto.*;
import com.library.portal.domain.Fine;
import com.library.portal.repository.FineRepository;
import com.library.portal.repository.LoanRepository;
import com.library.portal.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class MemberPortalService {

    private final MemberRepository members;
    private final LoanRepository loans;
    private final FineRepository fines;
    private final Clock clock;

    public MemberPortalService(MemberRepository members,
                               LoanRepository loans,
                               FineRepository fines,
                               Clock clock) {
        this.members = members;
        this.loans = loans;
        this.fines = fines;
        this.clock = clock;
    }

    public MemberProfileResponse getProfile(Long memberId) {
        return members.findById(memberId)
                .map(MemberProfileResponse::from)
                .orElseThrow(() -> new MemberNotFoundException(memberId));
    }

    public List<LoanResponse> getCurrentLoans(Long memberId) {
        requireMemberExists(memberId);
        LocalDate today = LocalDate.now(clock);
        return loans.findCurrentByMemberId(memberId).stream()
                .map(l -> LoanResponse.from(l, today))
                .toList();
    }

    public OutstandingFinesResponse getOutstandingFines(Long memberId) {
        requireMemberExists(memberId);
        List<Fine> outstanding = fines.findOutstandingByMemberId(memberId);

        BigDecimal total = outstanding.stream()
                .map(Fine::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new OutstandingFinesResponse(
                total,
                outstanding.stream().map(FineResponse::from).toList());
    }

    private void requireMemberExists(Long memberId) {
        if (!members.existsById(memberId)) {
            throw new MemberNotFoundException(memberId);
        }
    }
}
```

```java
package com.library.portal.service;

public class MemberNotFoundException extends RuntimeException {
    public MemberNotFoundException(Long id) {
        super("No member with id " + id);
    }
}
```

`Clock` as a bean keeps the overdue logic testable:

```java
package com.library.portal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {
    @Bean
    Clock clock() { return Clock.systemDefaultZone(); }
}
```

## Controller

```java
package com.library.portal.api;

import com.library.portal.api.dto.*;
import com.library.portal.service.MemberPortalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/members")
public class MemberPortalController {

    private final MemberPortalService service;

    public MemberPortalController(MemberPortalService service) {
        this.service = service;
    }

    @GetMapping("/{memberId}")
    public ResponseEntity<MemberProfileResponse> getProfile(@PathVariable Long memberId) {
        return ResponseEntity.ok(service.getProfile(memberId));
    }

    @GetMapping("/{memberId}/loans")
    public ResponseEntity<List<LoanResponse>> getCurrentLoans(@PathVariable Long memberId) {
        return ResponseEntity.ok(service.getCurrentLoans(memberId));
    }

    @GetMapping("/{memberId}/fines")
    public ResponseEntity<OutstandingFinesResponse> getOutstandingFines(@PathVariable Long memberId) {
        return ResponseEntity.ok(service.getOutstandingFines(memberId));
    }
}
```

## Error handling

```java
package com.library.portal.api;

import com.library.portal.service.MemberNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MemberNotFoundException.class)
    ProblemDetail handleNotFound(MemberNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Member not found");
        pd.setDetail(ex.getMessage());
        return pd;
    }
}
```

## Sample responses

```
GET /api/members/42
{
  "id": 42, "firstName": "Ada", "lastName": "Okonkwo",
  "email": "ada@example.com", "phone": "+44 7700 900123",
  "joinedOn": "2021-03-14", "status": "ACTIVE"
}

GET /api/members/42/loans
[
  { "id": 900, "itemTitle": "The Making of the Atomic Bomb",
    "itemBarcode": "31234000567890", "borrowedOn": "2026-07-20",
    "dueOn": "2026-08-03", "renewalCount": 1,
    "overdue": true, "daysUntilDue": -7 }
]

GET /api/members/42/fines
{
  "totalOutstanding": 4.50,
  "fines": [
    { "id": 55, "reason": "OVERDUE", "itemTitle": "The Making of the Atomic Bomb",
      "amountAssessed": 6.00, "amountPaid": 1.50,
      "balance": 4.50, "assessedOn": "2026-08-04" }
  ]
}
```

## The auth-shaped hole

To restate the thing I flagged at the top, concretely: right now `memberId` is attacker-controlled input. There is no check anywhere in this code that the caller has any relationship to the member whose data is returned — the service trusts the path variable completely. Sequential IDs make it trivial to walk the whole membership table, and the profile endpoint returns email and phone. That's a straight IDOR, and it's the kind of thing that tends to survive to production because the endpoints "work" perfectly in testing.

Two options for closing it when you get to auth, both small:

**Preferred — don't take the ID from the client at all.** Add `/api/members/me/...` variants that resolve the member from the authenticated principal. The client then has no way to express "someone else's data":

```java
@GetMapping("/me/fines")
public ResponseEntity<OutstandingFinesResponse> getMyFines(
        @AuthenticationPrincipal PortalUser user) {
    return ResponseEntity.ok(service.getOutstandingFines(user.memberId()));
}
```

**If you need the ID in the path** (staff views, deep links), put one guard in front of every lookup so it can't be forgotten per-endpoint:

```java
@Component
public class MemberAccessGuard {
    public void assertCanView(PortalUser caller, Long memberId) {
        if (caller.isStaff()) return;
        if (!caller.memberId().equals(memberId)) {
            throw new AccessDeniedException("Not your account");
        }
    }
}
```

…called as the first line of each `MemberPortalService` method. I've deliberately left the service methods shaped so that dropping `assertCanView(caller, memberId)` in is a one-line change per method rather than a restructure.

Either way, I'd add a test that asserts member A gets 403 on member B's fines before this goes anywhere real — that's the test that stops the hole reopening later.
