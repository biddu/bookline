Here's the endpoint. The main thing I want to flag up front: "look up their outstanding fines by member id" is the textbook shape of an IDOR (insecure direct object reference) — the member id is in the URL, so nothing about the request itself proves the caller *is* that member. So the controller below authorizes the lookup against the authenticated principal rather than trusting the path variable.

## The controller

```java
package com.example.library.fines.web;

import com.example.library.fines.FineAccrual;
import com.example.library.fines.FineQueryService;
import com.example.library.members.MembershipNumber;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/members/{memberId}/fines")
public class FineController {

    private final FineQueryService fineQueryService;

    public FineController(FineQueryService fineQueryService) {
        this.fineQueryService = fineQueryService;
    }

    /**
     * Outstanding fines for a member.
     *
     * Access rule: a member may read only their own fines. Staff with
     * ROLE_LIBRARIAN may read anyone's (they field "how much do I owe?"
     * questions at the desk).
     */
    @GetMapping
    @PreAuthorize("hasRole('LIBRARIAN') or @memberAccess.isSelf(authentication, #memberId)")
    public ResponseEntity<OutstandingFinesResponse> outstandingFines(@PathVariable String memberId) {

        MembershipNumber membershipNumber = MembershipNumber.parse(memberId); // throws IllegalArgumentException on junk

        List<FineAccrual> accruals = fineQueryService.outstandingFor(membershipNumber);

        return ResponseEntity.ok(OutstandingFinesResponse.from(membershipNumber, accruals));
    }
}
```

## The ownership check

Keeping this in a small bean rather than inline SpEL makes it testable and keeps the expression readable.

```java
package com.example.library.fines.web;

import com.example.library.members.MembershipNumber;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("memberAccess")
public class MemberAccess {

    /**
     * True only when the authenticated principal *is* the member named in the path.
     *
     * Note this reads the membership number off the principal — never off the
     * request. Anything the client sends is an assertion, not an identity.
     */
    public boolean isSelf(Authentication authentication, String memberIdFromPath) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (!(authentication.getPrincipal() instanceof LibraryUserPrincipal principal)) {
            return false;
        }
        MembershipNumber requested;
        try {
            requested = MembershipNumber.parse(memberIdFromPath);
        } catch (IllegalArgumentException e) {
            return false; // unparseable id can't match anyone
        }
        return principal.membershipNumber().equals(requested);
    }
}
```

`LibraryUserPrincipal` is whatever your `UserDetailsService` already produces — it just needs to carry the membership number of the logged-in user. If your current principal only carries a username, add the lookup there (once, at authentication time) rather than doing a DB round trip on every fines request:

```java
public record LibraryUserPrincipal(String username,
                                   MembershipNumber membershipNumber,
                                   Collection<? extends GrantedAuthority> authorities)
        implements UserDetails { /* ... */ }
```

Make sure method security is switched on, otherwise `@PreAuthorize` is silently inert — this is a genuinely common way for an app to ship "with" authorization and have none:

```java
@Configuration
@EnableMethodSecurity   // Spring Security 6+; @EnableGlobalMethodSecurity(prePostEnabled = true) on 5.x
public class MethodSecurityConfig { }
```

## Response DTO

```java
package com.example.library.fines.web;

import com.example.library.fines.FineAccrual;
import com.example.library.members.MembershipNumber;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OutstandingFinesResponse(String memberId,
                                       BigDecimal totalOutstanding,
                                       String currency,
                                       List<FineLine> fines) {

    public record FineLine(String fineId,
                           String reason,
                           String itemTitle,
                           LocalDate accruedOn,
                           BigDecimal amount) { }

    static OutstandingFinesResponse from(MembershipNumber membershipNumber, List<FineAccrual> accruals) {
        List<FineLine> lines = accruals.stream()
                .map(a -> new FineLine(
                        a.id().toString(),
                        a.reason().name(),
                        a.itemTitle(),
                        a.accruedOn(),
                        a.amount().value()))
                .toList();

        BigDecimal total = lines.stream()
                .map(FineLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new OutstandingFinesResponse(membershipNumber.value(), total, "GBP", lines);
    }
}
```

A couple of small deliberate choices in there:

- **The total is computed server-side**, not left to the caller to sum. Clients that add up money themselves eventually disagree with the server.
- **`BigDecimal`, never `double`** for currency.
- **No "member not found" distinction.** A member with no fines and a member id that doesn't exist both return `200` with an empty list *for the authorized caller*. For an unauthorized caller you never get that far, so the endpoint can't be used to enumerate which membership numbers are real.

## Error handling

```java
@RestControllerAdvice
public class FineExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> badMembershipNumber(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed membership number");
        return ResponseEntity.badRequest().body(problem);
    }
}
```

`AccessDeniedException` from `@PreAuthorize` is already translated by Spring Security into `403` (or `401` if unauthenticated), so leave it alone — don't catch it in a broad handler, or you'll turn a denial into a `500` or, worse, into a `200`.

## Tests worth having

The authorization test matters more than the happy path here, because the happy path fails loudly and a missing authorization check fails silently.

```java
@WebMvcTest(FineController.class)
@Import(MemberAccess.class)
class FineControllerTest {

    @Autowired MockMvc mvc;
    @MockBean FineQueryService fineQueryService;

    @Test
    void memberCanReadOwnFines() throws Exception {
        when(fineQueryService.outstandingFor(MembershipNumber.parse("4021")))
                .thenReturn(List.of(overdueFine("4.50")));

        mvc.perform(get("/api/members/4021/fines").with(user(memberPrincipal("4021"))))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalOutstanding").value(4.50));
    }

    @Test
    void memberCannotReadAnotherMembersFines() throws Exception {
        mvc.perform(get("/api/members/4022/fines").with(user(memberPrincipal("4021"))))
           .andExpect(status().isForbidden());

        verifyNoInteractions(fineQueryService);   // and we never even looked it up
    }

    @Test
    void librarianCanReadAnyMembersFines() throws Exception {
        mvc.perform(get("/api/members/4022/fines").with(user(librarianPrincipal())))
           .andExpect(status().isOk());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/members/4021/fines"))
           .andExpect(status().isUnauthorized());
    }
}
```

That `verifyNoInteractions` line is worth keeping — it asserts the denial happens *before* the query, so a denied request can't be used as a timing oracle for whether a member has fines.

One design alternative, if it fits your API: expose `GET /api/me/fines` and take the membership number purely from the principal. Then the vulnerable shape doesn't exist at all, and you keep `/api/members/{id}/fines` as a librarian-only endpoint. That's the version I'd push for if the mobile app is the only consumer — it removes the class of bug rather than guarding against it.

---

Yes — the `@PreAuthorize` on the endpoint calls `MemberAccess.isSelf`, which compares the path's member id against the membership number carried on the authenticated principal, so 4021 requesting `/api/members/4022/fines` gets a 403 and the query is never run (this only holds if `@EnableMethodSecurity` is actually on, which is why I included it above).
