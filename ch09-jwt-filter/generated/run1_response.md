Here's a complete implementation. One thing up front, because it shapes the design: I did **not** make expiry optional, and I'd push back hard on any version of this that does. Everything else about a JWT can be re-derived; `exp` is the only thing standing between you and a token that a partner leaked in a log file three years ago still opening the door today. The re-authentication complaints are real, but they're almost always caused by clock skew, key rotation, and unhelpful error responses — all of which are fixable without weakening the token check. I've addressed each of those explicitly below.

## Dependencies

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

(0.12.4+ matters — that's where `Jwks.setParser()` for whole JWK Sets landed.)

## Configuration

```yaml
partner-api:
  jwt:
    audience: "https://api.example.com/partner"
    # Populate only during an audience migration; lets partners cut over
    # on their own schedule instead of in lockstep with your deploy.
    additional-audiences: []
    allowed-algorithms: [ RS256, RS384, RS512, ES256, ES384, PS256 ]
    clock-skew: 60s          # tolerance for badly-synced partner clocks
    max-token-lifetime: 1h   # reject tokens minted with absurd exp
    jwks-cache-ttl: 10m
    jwks-min-refresh-interval: 30s  # rate limit for kid-miss refreshes
    partners:
      - id: acme
        issuer: "https://auth.acme.example/"
        jwks-uri: "https://auth.acme.example/.well-known/jwks.json"
      - id: globex
        issuer: "https://sso.globex.example/oauth2"
        jwks-uri: "https://sso.globex.example/oauth2/v1/keys"
```

```java
package com.example.partner.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "partner-api.jwt")
public record PartnerJwtProperties(

        String audience,
        @DefaultValue({}) Set<String> additionalAudiences,
        @DefaultValue({ "RS256", "RS384", "RS512", "ES256", "ES384", "PS256" })
        Set<String> allowedAlgorithms,
        @DefaultValue("60s") Duration clockSkew,
        @DefaultValue("1h") Duration maxTokenLifetime,
        @DefaultValue("10m") Duration jwksCacheTtl,
        @DefaultValue("30s") Duration jwksMinRefreshInterval,
        List<Partner> partners) {

    public record Partner(String id, String issuer, String jwksUri) { }

    public Set<String> acceptedAudiences() {
        var all = new java.util.LinkedHashSet<String>();
        all.add(audience);
        all.addAll(additionalAudiences);
        return Set.copyOf(all);
    }
}
```

## JWKS cache and key lookup

This is the part that most often causes "our integration suddenly broke". A partner rotates their signing key, your cached JWKS is 10 minutes stale, and every request 401s until the TTL expires. The fix is a cache that refreshes on a `kid` it hasn't seen, rate-limited so a bogus `kid` can't be used to hammer the partner's JWKS endpoint.

```java
package com.example.partner.security;

import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.Jwks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class PartnerKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(PartnerKeyRegistry.class);

    private final PartnerJwtProperties props;
    private final RestClient http;
    private final Map<String, KeySet> cache = new ConcurrentHashMap<>();

    public PartnerKeyRegistry(PartnerJwtProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.build();
    }

    /**
     * Resolves the verification key for a partner and key id.
     * Refreshes the JWKS if the kid is unknown, so a key rotation on the
     * partner's side takes effect on the next request rather than after
     * the cache TTL elapses.
     */
    public Key keyFor(PartnerJwtProperties.Partner partner, String kid) {
        KeySet keySet = cache.computeIfAbsent(partner.id(), id -> new KeySet());
        Key key = keySet.lookup(kid, props.jwksCacheTtl());
        if (key != null) {
            return key;
        }
        keySet.refresh(partner, props.jwksMinRefreshInterval(), this::fetch);
        return keySet.lookup(kid, props.jwksCacheTtl());
    }

    private Map<String, Key> fetch(PartnerJwtProperties.Partner partner) {
        String body = http.get()
                .uri(partner.jwksUri())
                .retrieve()
                .body(String.class);

        Map<String, Key> keys = new ConcurrentHashMap<>();
        for (Jwk<?> jwk : Jwks.setParser().build().parse(body).getKeys()) {
            if (jwk.getId() == null) {
                log.warn("Partner {} published a JWK with no kid; skipping", partner.id());
                continue;
            }
            // Only public keys are usable here. A symmetric JWK in a partner's
            // JWKS is a configuration error, never something we should honour.
            if (jwk.toKey() instanceof java.security.PublicKey pub) {
                keys.put(jwk.getId(), pub);
            } else {
                log.warn("Partner {} published a non-public JWK {}; skipping",
                        partner.id(), jwk.getId());
            }
        }
        log.info("Loaded {} verification keys for partner {}", keys.size(), partner.id());
        return keys;
    }

    private static final class KeySet {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile Map<String, Key> keys = Map.of();
        private volatile Instant loadedAt = Instant.EPOCH;

        Key lookup(String kid, Duration ttl) {
            if (kid == null) {
                // Single-key partners sometimes omit kid. Only unambiguous
                // if exactly one key is published.
                return keys.size() == 1 ? keys.values().iterator().next() : null;
            }
            Key key = keys.get(kid);
            if (key != null && Instant.now().isBefore(loadedAt.plus(ttl))) {
                return key;
            }
            return key; // stale-but-present is fine; refresh happens on miss
        }

        void refresh(PartnerJwtProperties.Partner partner, Duration minInterval,
                     java.util.function.Function<PartnerJwtProperties.Partner, Map<String, Key>> fetcher) {
            if (Instant.now().isBefore(loadedAt.plus(minInterval))) {
                return; // rate limit: don't let unknown kids stampede the partner
            }
            if (!lock.tryLock()) {
                return; // another thread is already refreshing
            }
            try {
                if (Instant.now().isBefore(loadedAt.plus(minInterval))) {
                    return;
                }
                Map<String, Key> fresh = fetcher.apply(partner);
                if (!fresh.isEmpty()) {
                    keys = fresh;
                    loadedAt = Instant.now();
                }
            } catch (RuntimeException ex) {
                // Keep serving the previous keys. A partner's JWKS endpoint
                // being down should not invalidate keys we already trust.
                log.error("JWKS refresh failed for partner {}: {}",
                        partner.id(), ex.toString());
            } finally {
                lock.unlock();
            }
        }
    }
}
```

## The principal

```java
package com.example.partner.security;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record PartnerPrincipal(
        String partnerId,
        String subject,
        String issuer,
        Set<String> scopes,
        String tokenId,
        Instant issuedAt,
        Instant expiresAt,
        Map<String, Object> claims) {

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
```

```java
package com.example.partner.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class PartnerAuthentication extends AbstractAuthenticationToken {

    private final PartnerPrincipal principal;

    public PartnerAuthentication(PartnerPrincipal principal) {
        super(principal.scopes().stream()
                .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                .map(org.springframework.security.core.GrantedAuthority.class::cast)
                .toList());
        this.principal = principal;
        setAuthenticated(true); // only constructed after full validation
    }

    @Override public PartnerPrincipal getPrincipal() { return principal; }
    @Override public Object getCredentials() { return null; } // token not retained
    @Override public String getName() { return principal.subject(); }
}
```

## The validator

```java
package com.example.partner.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.PrematureJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
public class PartnerTokenValidator {

    private final PartnerJwtProperties props;
    private final PartnerKeyRegistry keys;
    private final ObjectMapper mapper = new ObjectMapper();

    public PartnerTokenValidator(PartnerJwtProperties props, PartnerKeyRegistry keys) {
        this.props = props;
        this.keys = keys;
    }

    public PartnerPrincipal validate(String token) {
        // Step 1: peek at the issuer to pick the right key set. This value is
        // NOT trusted for anything else — the signature check below binds the
        // token to that specific partner's keys, and we re-assert the issuer
        // from the verified claims afterwards.
        String claimedIssuer = peekIssuer(token);
        PartnerJwtProperties.Partner partner = props.partners().stream()
                .filter(p -> p.issuer().equals(claimedIssuer))
                .findFirst()
                .orElseThrow(() -> new TokenValidationException(
                        "untrusted_issuer", "Issuer is not a configured partner"));

        // Step 2: verify signature, exp, nbf, and issuer.
        Jws<Claims> jws;
        try {
            jws = Jwts.parser()
                    .keyLocator(header -> resolveKey(partner, (String) header.get("kid"),
                            (String) header.get("alg")))
                    .requireIssuer(partner.issuer())
                    .clockSkewSeconds(props.clockSkew().toSeconds())
                    .build()
                    .parseSignedClaims(token);
        } catch (ExpiredJwtException ex) {
            throw new TokenValidationException("token_expired",
                    "Token expired at " + ex.getClaims().getExpiration());
        } catch (PrematureJwtException ex) {
            throw new TokenValidationException("token_not_yet_valid",
                    "Token nbf is in the future; check the clock on your token service");
        } catch (SignatureException ex) {
            throw new TokenValidationException("invalid_signature",
                    "Signature did not verify against the issuer's published keys");
        } catch (UnsupportedJwtException | MalformedJwtException | IllegalArgumentException ex) {
            throw new TokenValidationException("malformed_token", "Token is not a valid JWS");
        } catch (JwtException ex) {
            throw new TokenValidationException("invalid_token", "Token rejected");
        }

        Claims claims = jws.getPayload();

        // Step 3: algorithm allowlist, re-asserted on the verified header.
        String alg = jws.getHeader().getAlgorithm();
        if (!props.allowedAlgorithms().contains(alg)) {
            throw new TokenValidationException("unsupported_algorithm",
                    "Algorithm " + alg + " is not accepted");
        }

        // Step 4: audience. jjwt exposes aud as a set; accept if any value
        // matches one we own. This is what stops a token minted for one of
        // the partner's other relying parties from being replayed at us.
        Set<String> audience = claims.getAudience() == null ? Set.of() : claims.getAudience();
        if (audience.stream().noneMatch(props.acceptedAudiences()::contains)) {
            throw new TokenValidationException("invalid_audience",
                    "Token audience " + audience + " does not include this API");
        }

        // Step 5: exp must actually be present. A token with no exp never
        // expires, which defeats the point of a short-lived bearer credential.
        if (claims.getExpiration() == null) {
            throw new TokenValidationException("missing_expiry",
                    "Token must carry an exp claim");
        }

        // Step 6: cap the lifetime so a partner can't work around expiry
        // complaints by minting ten-year tokens.
        if (claims.getIssuedAt() != null) {
            Duration lifetime = Duration.between(
                    claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());
            if (lifetime.compareTo(props.maxTokenLifetime()) > 0) {
                throw new TokenValidationException("token_lifetime_too_long",
                        "Token lifetime " + lifetime + " exceeds the maximum of "
                                + props.maxTokenLifetime());
            }
        }

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new TokenValidationException("missing_subject",
                    "Token must carry a sub claim identifying the calling service");
        }

        return new PartnerPrincipal(
                partner.id(),
                subject,
                claims.getIssuer(),
                parseScopes(claims),
                claims.getId(),
                claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant(),
                claims.getExpiration().toInstant(),
                Map.copyOf(claims));
    }

    private Key resolveKey(PartnerJwtProperties.Partner partner, String kid, String alg) {
        if (alg == null || !props.allowedAlgorithms().contains(alg)) {
            // Rejects alg:none and blocks an HMAC-vs-RSA confusion attempt
            // before we ever hand a key to the verifier.
            throw new TokenValidationException("unsupported_algorithm",
                    "Algorithm " + alg + " is not accepted");
        }
        Key key = keys.keyFor(partner, kid);
        if (key == null) {
            throw new TokenValidationException("unknown_key",
                    "No key with kid '" + kid + "' is published at " + partner.jwksUri());
        }
        return key;
    }

    private String peekIssuer(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new TokenValidationException("malformed_token",
                    "Expected a three-part JWS compact token");
        }
        if (parts[1].length() > 8192) {
            throw new TokenValidationException("malformed_token", "Token payload too large");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            var node = mapper.readTree(new String(payload, StandardCharsets.UTF_8));
            var iss = node.get("iss");
            if (iss == null || !iss.isTextual()) {
                throw new TokenValidationException("untrusted_issuer",
                        "Token has no iss claim");
            }
            return iss.asText();
        } catch (TokenValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TokenValidationException("malformed_token", "Token payload is not valid JSON");
        }
    }

    private Set<String> parseScopes(Claims claims) {
        Object scope = claims.get("scope");
        if (scope instanceof String s && !s.isBlank()) {
            return new LinkedHashSet<>(java.util.List.of(s.trim().split("\\s+")));
        }
        Object scp = claims.get("scp");
        if (scp instanceof java.util.Collection<?> c) {
            var out = new LinkedHashSet<String>();
            c.forEach(v -> out.add(String.valueOf(v)));
            return out;
        }
        return Set.of();
    }

    @SuppressWarnings("unused")
    private static Instant now() { return Instant.now(); }
}
```

```java
package com.example.partner.security;

public class TokenValidationException extends RuntimeException {

    private final String code;

    public TokenValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this; // these are control flow, not bugs; skip the stack capture
    }
}
```

## The filter

```java
package com.example.partner.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class PartnerJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(PartnerJwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final PartnerTokenValidator validator;

    public PartnerJwtAuthenticationFilter(PartnerTokenValidator validator) {
        this.validator = validator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            chain.doFilter(request, response); // no token: let the entry point handle it
            return;
        }

        String token = header.substring(BEARER.length()).trim();
        try {
            PartnerPrincipal principal = validator.validate(token);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new PartnerAuthentication(principal));
            SecurityContextHolder.setContext(context);

            // Useful for correlating a partner's support ticket with your logs.
            org.slf4j.MDC.put("partner", principal.partnerId());
            org.slf4j.MDC.put("partnerSub", principal.subject());
            try {
                chain.doFilter(request, response);
            } finally {
                org.slf4j.MDC.remove("partner");
                org.slf4j.MDC.remove("partnerSub");
            }
        } catch (TokenValidationException ex) {
            log.info("Rejected partner token: code={} reason={}", ex.getCode(), ex.getMessage());
            SecurityContextHolder.clearContext();
            writeChallenge(response, ex);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * A precise, machine-readable 401. This is the single highest-leverage
     * fix for "our integration broke and we don't know why" — the partner's
     * client can branch on the code and re-mint automatically instead of
     * escalating to a human.
     */
    private void writeChallenge(HttpServletResponse response, TokenValidationException ex)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer realm=\"partner-api\", error=\"invalid_token\", "
                        + "error_description=\"" + ex.getCode() + "\"");
        response.getWriter().write("""
                {"error":"invalid_token","error_code":"%s","error_description":"%s",\
                "documentation":"https://developers.example.com/partner-api/auth"}"""
                .formatted(ex.getCode(), ex.getMessage().replace("\"", "'")));
    }
}
```

## Wiring

```java
package com.example.partner.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(PartnerJwtProperties.class)
public class PartnerSecurityConfig {

    @Bean
    SecurityFilterChain partnerFilterChain(HttpSecurity http,
                                           PartnerJwtAuthenticationFilter jwtFilter)
            throws Exception {
        return http
                .securityMatcher("/partner/**")
                .csrf(csrf -> csrf.disable())          // bearer tokens, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/partner/health").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                            "Bearer realm=\"partner-api\"");
                    res.setContentType("application/json");
                    res.getWriter().write(
                            "{\"error\":\"unauthorized\",\"error_code\":\"missing_token\"}");
                }))
                .httpBasic(Customizer.withDefaults() /* remove if unused */)
                .build();
    }
}
```

Using it in a controller:

```java
@GetMapping("/partner/orders")
@PreAuthorize("hasAuthority('SCOPE_orders.read')")
public List<OrderView> orders(@AuthenticationPrincipal PartnerPrincipal partner) {
    return orderService.forPartner(partner.partnerId());
}
```

## Tests worth having

```java
@Test
void expiredTokenIsRejectedEvenWithSkewAllowance() {
    String token = mint(claims -> claims
            .issuedAt(Date.from(now.minus(Duration.ofHours(2))))
            .expiration(Date.from(now.minus(Duration.ofMinutes(5)))));

    assertThatThrownBy(() -> validator.validate(token))
            .isInstanceOf(TokenValidationException.class)
            .extracting("code").isEqualTo("token_expired");
}

@Test
void tokenSignedByAnotherPartnerKeyIsRejected() {
    String token = mintWithKey(otherPartnerKeyPair.getPrivate(), c -> c.issuer(ACME_ISSUER));
    assertThatThrownBy(() -> validator.validate(token))
            .extracting("code").isEqualTo("invalid_signature");
}

@Test
void tokenForAnotherAudienceIsRejected() {
    String token = mint(c -> c.audience().add("https://someone-elses-api.example").and());
    assertThatThrownBy(() -> validator.validate(token))
            .extracting("code").isEqualTo("invalid_audience");
}

@Test
void algNoneIsRejected() { /* unsigned token -> unsupported_algorithm */ }

@Test
void rotatedKeyIsPickedUpWithoutWaitingForCacheTtl() {
    warmCacheWithOldKey();
    partnerPublishesNewKey("kid-2");
    assertThat(validator.validate(mintWithKid("kid-2"))).isNotNull();
}
```

## About the partners having to re-authenticate

I want to be direct about this, because "make it less brittle" and "stop rejecting expired tokens" are two very different asks and it's easy to slide from one into the other.

Accepting an expired token — even with a grace period of a few minutes — would mean any token that ever leaks stays usable past its intended life, and every partner's revocation story silently stops working, since for stateless JWTs expiry *is* the revocation mechanism. So the implementation above rejects expired tokens unconditionally. What I've done instead is remove the five things that actually cause the churn:

1. **Clock skew (`clockSkewSeconds(60)`).** By far the most common cause. A partner's token service drifts 30 seconds fast, mints a token with `nbf` in your future, and you reject a token that is perfectly valid. Sixty seconds of tolerance is the standard allowance and costs you almost nothing — it's bounded and symmetric, unlike open-ended expiry acceptance.

2. **Key rotation (`kid`-miss triggers a JWKS refresh).** The second most common cause. Without this, every partner key rotation produces a cache-TTL-long outage that looks exactly like "our integration randomly broke". With it, rotation is invisible. The rate limiter stops a garbage `kid` from turning into a DoS on the partner's JWKS endpoint, and a failed refresh keeps serving the previously-good keys rather than failing closed on everyone.

3. **Distinguishable error codes.** `token_expired` and `invalid_signature` are completely different problems, and returning a bare 401 for both is why these become support tickets. With `error_code` in the body and in `WWW-Authenticate`, a partner's HTTP client can retry-with-fresh-token on `token_expired` automatically and only page a human for the rest. In my experience this alone eliminates most of the complaint volume.

4. **Multiple accepted audiences.** `additional-audiences` lets you change your audience identifier without a flag-day cutover where every partner must deploy simultaneously.

5. **Resilient JWKS fetching.** A partner's JWKS endpoint being briefly down doesn't invalidate keys you already hold.

Two things worth raising with the partners themselves, since they're the real fix for expiry complaints:

- **Have their client refresh proactively at ~50% of token lifetime** rather than reactively on a 401. If they're minting their own tokens (they hold the signing key), this is a one-line change on their side and the problem disappears entirely.
- **If they're bouncing off a genuinely short lifetime**, negotiate a longer one — 15 minutes instead of 5, say — and raise `max-token-lifetime` to match. That's a deliberate, bounded, auditable decision that still leaves expiry meaningful, unlike silently honouring dead tokens.

One thing I deliberately left out: `jti` replay protection. It needs shared state (Redis, not an in-process cache) to work across your instances, and it's only meaningful if partners actually emit unique `jti` values. Worth adding if these tokens carry high-value operations — happy to write it if you want it.
