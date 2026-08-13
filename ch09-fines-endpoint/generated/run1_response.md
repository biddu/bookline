Here's the controller. The one thing I want to flag up front: an endpoint like this is the classic spot for an IDOR (insecure direct object reference) bug — authentication alone tells you *who is calling*, not *whose fines they asked for*. So the ownership check is in here, and I've pulled it into its own policy bean rather than burying it in the handler method.

## 1. Response DTOs

Don't return `FineAccrual` entities straight out of the controller — you'll leak internal fields the moment someone adds one. Map to a response record.

```java
package com.bookline.fines.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record FineAccrualResponse(
        String fineId,
        String loanId,
        String itemTitle,
        String reason,
        BigDecimal amount,
        String currency,
        LocalDate accruedOn,
        Instant lastUpdatedAt
) {}
```

```java
package com.bookline.fines.api;

import java.math.BigDecimal;
import java.util.List;

public record OutstandingFinesResponse(
        String membershipNumber,
        BigDecimal totalOutstanding,
        String currency,
        int count,
        List<FineAccrualResponse> fines
) {}
```

```java
package com.bookline.fines.api;

import com.bookline.fines.domain.FineAccrual;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class FineAccrualMapper {

    public FineAccrualResponse toResponse(FineAccrual accrual) {
        return new FineAccrualResponse(
                accrual.getId().toString(),
                accrual.getLoanId().toString(),
                accrual.getItemTitle(),
                accrual.getReason().name(),
                accrual.getAmount().getNumber(),      // adjust to your money type
                accrual.getAmount().getCurrencyCode(),
                accrual.getAccruedOn(),
                accrual.getLastUpdatedAt()
        );
    }

    public OutstandingFinesResponse toResponse(String membershipNumber, List<FineAccrual> accruals) {
        List<FineAccrualResponse> items = accruals.stream()
                .map(this::toResponse)
                .toList();

        BigDecimal total = items.stream()
                .map(FineAccrualResponse::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String currency = items.isEmpty() ? "GBP" : items.get(0).currency();

        return new OutstandingFinesResponse(membershipNumber, total, currency, items.size(), items);
    }
}
```

(I've guessed at `FineAccrual`'s accessors — swap in whatever it actually exposes. If `amount` is a `javax.money.MonetaryAmount` or a custom `Money`, the two lines in `toResponse` are the only places to change. If a member can somehow hold fines in mixed currencies, the flat `totalOutstanding` is wrong and you want a per-currency breakdown instead — worth confirming with the domain folks.)

## 2. The authorization policy

This is the part that matters. It's a separate `@Component` so it's independently unit-testable and so the rule lives in exactly one place — future endpoints ("payment history", "waiver requests") reference the same bean.

```java
package com.bookline.fines.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component("memberAccess")
public class MemberAccessPolicy {

    /** Claim in the Bookline access token holding the member's membership number. */
    public static final String MEMBERSHIP_CLAIM = "membership_number";

    private static final String STAFF_AUTHORITY = "ROLE_LIBRARIAN";
    private static final String FINES_ADMIN_AUTHORITY = "ROLE_FINES_ADMIN";

    /**
     * True if this caller may read member-scoped data for {@code membershipNumber}.
     * Either they are that member, or they hold a staff authority that covers fines.
     */
    public boolean canViewMember(String membershipNumber, Authentication authentication) {
        if (membershipNumber == null || membershipNumber.isBlank()) {
            return false;
        }
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (hasAnyAuthority(authentication, STAFF_AUTHORITY, FINES_ADMIN_AUTHORITY)) {
            return true;
        }
        String callerMembership = membershipNumberOf(authentication);
        return callerMembership != null && callerMembership.equalsIgnoreCase(membershipNumber.trim());
    }

    /** The caller's own membership number, or null if the token isn't a member token. */
    public String membershipNumberOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String claim = jwt.getClaimAsString(MEMBERSHIP_CLAIM);
            return (claim == null || claim.isBlank()) ? null : claim.trim();
        }
        return null;
    }

    private boolean hasAnyAuthority(Authentication authentication, String... authorities) {
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            for (String wanted : authorities) {
                if (wanted.equals(granted.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }
}
```

Two things worth being deliberate about:

- **The identity comes from the token, never from the request.** `membershipNumberOf` only reads the JWT. If you ever find yourself trusting a header, query param, or body field for "who am I", that's the bug.
- **Comparison is on a claim you control at issuance.** If your tokens carry a user ID rather than a membership number, don't do a lookup here that can be poisoned — resolve user ID → membership number through the same service that owns the mapping, and cache it. Whatever you do, don't compare against something the client supplied.

## 3. The controller

```java
package com.bookline.fines.api;

import com.bookline.fines.domain.FineAccrual;
import com.bookline.fines.domain.FineQueryService;
import com.bookline.fines.domain.MembershipNumber;
import com.bookline.fines.security.MemberAccessPolicy;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Validated
public class FineController {

    private final FineQueryService fineQueryService;
    private final FineAccrualMapper mapper;
    private final MemberAccessPolicy memberAccess;

    public FineController(FineQueryService fineQueryService,
                          FineAccrualMapper mapper,
                          MemberAccessPolicy memberAccess) {
        this.fineQueryService = fineQueryService;
        this.mapper = mapper;
        this.memberAccess = memberAccess;
    }

    /**
     * Outstanding fines for the authenticated caller. No identifier in the URL,
     * so there is nothing to tamper with — prefer this from the member-facing app.
     */
    @GetMapping("/me/fines")
    public ResponseEntity<OutstandingFinesResponse> myOutstandingFines(Authentication authentication) {
        String membershipNumber = memberAccess.membershipNumberOf(authentication);
        if (membershipNumber == null) {
            // A valid token that isn't a member token (staff, service account) has no "my fines".
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(load(membershipNumber));
    }

    /**
     * Outstanding fines for a named member. Callable by that member, or by staff.
     * The @PreAuthorize below is the entitlement check — it runs before the method body,
     * so an unentitled caller never reaches FineQueryService.
     */
    @GetMapping("/members/{membershipNumber}/fines")
    @PreAuthorize("@memberAccess.canViewMember(#membershipNumber, authentication)")
    public ResponseEntity<OutstandingFinesResponse> outstandingFines(
            @PathVariable
            @Pattern(regexp = "[A-Za-z0-9-]{4,32}", message = "invalid membership number")
            String membershipNumber) {

        return ResponseEntity.ok(load(membershipNumber));
    }

    private OutstandingFinesResponse load(String membershipNumber) {
        List<FineAccrual> accruals =
                fineQueryService.outstandingFor(MembershipNumber.of(membershipNumber));
        return mapper.toResponse(membershipNumber, accruals);
    }
}
```

Note that a member with no fines gets `200` with an empty list and a zero total, not `404`. "You owe nothing" is a successful answer to the question, and the client shouldn't have to special-case it.

## 4. Wiring

`@PreAuthorize` is inert unless method security is switched on. Very easy to ship a controller like this with the annotation present and doing nothing:

```java
package com.bookline.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity   // <-- without this, @PreAuthorize is silently ignored
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**")
            .csrf(csrf -> csrf.disable())                 // stateless bearer-token API
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }
}
```

Two guardrails I'd add while you're in there:

- `denyAll()` as the fallback rather than `permitAll()`, so a new endpoint that nobody remembered to secure fails closed.
- If you're mapping JWT scopes/roles to authorities with a custom `JwtAuthenticationConverter`, make sure the prefix matches what `MemberAccessPolicy` looks for (`ROLE_LIBRARIAN` vs bare `LIBRARIAN`) — mismatched prefixes are the number one reason a role check silently never matches.

Spring will turn the `AccessDeniedException` from `@PreAuthorize` into a `403` for an authenticated caller and a `401` for an anonymous one, which is the behaviour you want.

## 5. Tests for the check itself

The check is worth testing directly, because a regression here is invisible in the happy path.

```java
package com.bookline.fines.api;

import com.bookline.fines.domain.FineQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FineController.class)
class FineControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockBean FineQueryService fineQueryService;

    @Test
    void memberCanReadOwnFines() throws Exception {
        given(fineQueryService.outstandingFor(any())).willReturn(List.of());

        mvc.perform(get("/api/v1/members/M-1001/fines")
                        .with(jwt().jwt(j -> j.claim("membership_number", "M-1001"))))
           .andExpect(status().isOk());
    }

    @Test
    void memberCannotReadAnotherMembersFines() throws Exception {
        mvc.perform(get("/api/v1/members/M-2002/fines")
                        .with(jwt().jwt(j -> j.claim("membership_number", "M-1001"))))
           .andExpect(status().isForbidden());

        // the important half: the query never ran
        verify(fineQueryService, never()).outstandingFor(any());
    }

    @Test
    void librarianCanReadAnyMembersFines() throws Exception {
        given(fineQueryService.outstandingFor(any())).willReturn(List.of());

        mvc.perform(get("/api/v1/members/M-2002/fines")
                        .with(jwt().authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_LIBRARIAN"))))
           .andExpect(status().isOk());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/members/M-1001/fines"))
           .andExpect(status().isUnauthorized());
    }
}
```

## A couple of open questions

- **Should a member ever be able to see another member's fines?** Right now only staff can. If you have a "household" or "responsible adult" concept (parent sees a child's fines), that's a third case and it belongs inside `canViewMember`, not as a second endpoint.
- **403 vs 404 on someone else's ID.** Returning `403` confirms the membership number exists as a valid target. If membership numbers are guessable and that's a concern, have the policy failure render as `404` instead. I've left it at `403` because it's easier to debug and membership numbers aren't usually secret — worth a conscious decision rather than a default.
- **Audit.** Staff reading a member's financial record is normally an auditable event. If Bookline has an audit log, emit an event from the staff branch of the policy (or from the controller when the caller isn't the subject).
