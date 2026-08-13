Here's the handler. The important design decision is that `reference` from the processor is treated as the idempotency key, and the database — not application logic — is what enforces uniqueness.

### 1. The event payload

```java
package com.example.billing.payments.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PaymentEvent(
        @NotBlank String memberRef,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal amount,
        @NotBlank String reference
) {
}
```

`BigDecimal` rather than `double` — this is money. If the processor sends amounts in minor units (cents/pence), take a `long` instead and convert once at the edge; just don't let a binary float anywhere near it.

### 2. The entity, with a unique constraint on the reference

```java
package com.example.billing.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "payment",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_reference", columnNames = "reference")
)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String memberRef;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(nullable = false, updatable = false)
    private Instant receivedAt;

    protected Payment() {
        // for JPA
    }

    public Payment(String memberRef, BigDecimal amount, String reference, Instant receivedAt) {
        this.memberRef = memberRef;
        this.amount = amount;
        this.reference = reference;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }

    public String getMemberRef() {
        return memberRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReference() {
        return reference;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
```

The matching migration — the annotation alone won't create the constraint on an existing table, and this constraint is the whole safety mechanism, so it belongs in Flyway/Liquibase explicitly:

```sql
ALTER TABLE payment
    ADD CONSTRAINT uk_payment_reference UNIQUE (reference);
```

### 3. Repository

```java
package com.example.billing.payments;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    boolean existsByReference(String reference);
}
```

### 4. The service

Two methods, and the split between them matters.

```java
package com.example.billing.payments;

import com.example.billing.members.MemberAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final PaymentRepository paymentRepository;
    private final MemberAccount memberAccount;
    private final Clock clock;

    public PaymentWebhookService(PaymentRepository paymentRepository,
                                 MemberAccount memberAccount,
                                 Clock clock) {
        this.paymentRepository = paymentRepository;
        this.memberAccount = memberAccount;
        this.clock = clock;
    }

    /**
     * Not transactional on purpose: the unique-constraint violation has to surface
     * to a caller *outside* the transaction, because once a transaction is marked
     * rollback-only you cannot swallow the exception and carry on inside it.
     */
    public PaymentOutcome handle(PaymentEvent event) {
        if (paymentRepository.existsByReference(event.reference())) {
            log.info("Ignoring duplicate payment webhook, reference={}", event.reference());
            return PaymentOutcome.DUPLICATE;
        }

        try {
            apply(event);
            return PaymentOutcome.APPLIED;
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent delivery of the same event.
            // The other thread applied it; this one did nothing and rolled back.
            log.info("Concurrent duplicate payment webhook, reference={}", event.reference());
            return PaymentOutcome.DUPLICATE;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void apply(PaymentEvent event) {
        Payment payment = new Payment(
                event.memberRef(),
                event.amount(),
                event.reference(),
                Instant.now(clock)
        );

        // flush now so the unique-constraint violation is raised here, inside this
        // transaction, and not deferred to commit after the balance has been touched.
        paymentRepository.saveAndFlush(payment);

        memberAccount.reduceBalance(event.memberRef(), event.amount());
    }

    public enum PaymentOutcome {
        APPLIED,
        DUPLICATE
    }
}
```

Two things worth flagging, because both are easy to get wrong:

**`saveAndFlush`, not `save`.** With `save`, Hibernate can defer the INSERT to commit time, which means `reduceBalance` would run *before* the database has told you the reference is a duplicate. Flushing first makes the insert the gate.

**`apply` is `protected` and self-invoked, which Spring's proxying does not intercept.** As written above, calling `this.apply(...)` bypasses the `@Transactional` proxy. Pick one of these fixes:

- Move `apply` into a separate `@Service` bean (`PaymentApplier`) and inject it — cleanest, and what I'd ship.
- Or inject a self-reference (`@Lazy PaymentWebhookService self`) and call `self.apply(event)`.
- Or use `TransactionTemplate` explicitly.

Here's the separate-bean version, which I'd prefer:

```java
@Service
public class PaymentApplier {

    private final PaymentRepository paymentRepository;
    private final MemberAccount memberAccount;
    private final Clock clock;

    public PaymentApplier(PaymentRepository paymentRepository,
                          MemberAccount memberAccount,
                          Clock clock) {
        this.paymentRepository = paymentRepository;
        this.memberAccount = memberAccount;
        this.clock = clock;
    }

    @Transactional
    public void apply(PaymentEvent event) {
        paymentRepository.saveAndFlush(new Payment(
                event.memberRef(), event.amount(), event.reference(), Instant.now(clock)));
        memberAccount.reduceBalance(event.memberRef(), event.amount());
    }
}
```

…and `PaymentWebhookService.handle` then injects `PaymentApplier` and calls `applier.apply(event)` inside the try block. Everything else stays the same.

### 5. The controller

```java
package com.example.billing.payments.web;

import com.example.billing.payments.PaymentWebhookService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/payments")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final PaymentWebhookService paymentWebhookService;

    public PaymentWebhookController(PaymentWebhookService paymentWebhookService) {
        this.paymentWebhookService = paymentWebhookService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@Valid @RequestBody PaymentEvent event) {
        PaymentWebhookService.PaymentOutcome outcome = paymentWebhookService.handle(event);
        log.info("Payment webhook reference={} outcome={}", event.reference(), outcome);
        return ResponseEntity.ok().build();
    }
}
```

Both outcomes return `200`. A duplicate is not an error from the processor's point of view — it did the right thing by retrying, and telling it anything other than 2xx just makes it retry again.

Deliberately *not* caught here: anything else. If the database is down or `reduceBalance` throws, let it bubble up to a `500` so the processor retries. Swallowing failures into a `200` is how payments silently go missing.

### 6. Two things I'd add before this goes live

**Verify the signature.** Right now this endpoint applies a balance reduction to anyone who can POST JSON to it. Every card processor signs its callbacks (HMAC over the raw body, usually in a header). Add a filter or an interceptor that verifies it against the raw bytes — note that you need the *raw* body, before Jackson touches it, so wrap the request with `ContentCachingRequestWrapper` or read it in a filter. Reject with `401` if it doesn't match, and exclude the path from any session-based security while you're in `SecurityFilterChain`.

**Check the amount actually matches what's owed.** `reduceBalance` as specified will take whatever number it's handed. Worth confirming with whoever owns `MemberAccount` whether it validates against the outstanding fine, or whether a `-500.00` in the payload would happily credit someone's account.

### 7. Test that the duplicate case actually holds

```java
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentWebhookServiceTest {

    @Autowired PaymentWebhookService service;
    @Autowired PaymentRepository paymentRepository;
    @MockBean MemberAccount memberAccount;

    @Test
    void appliesPaymentOnce_whenSameEventDeliveredTwice() {
        PaymentEvent event = new PaymentEvent("M-1001", new BigDecimal("12.50"), "PSP-REF-9931");

        assertThat(service.handle(event)).isEqualTo(PaymentOutcome.APPLIED);
        assertThat(service.handle(event)).isEqualTo(PaymentOutcome.DUPLICATE);

        verify(memberAccount, times(1)).reduceBalance("M-1001", new BigDecimal("12.50"));
        assertThat(paymentRepository.count()).isEqualTo(1);
    }
}
```

Worth adding a concurrent version too — two threads calling `handle` with the same event, asserting `reduceBalance` was invoked exactly once. That's the case the `existsByReference` check alone would miss, and it's the one that actually happens in production when a processor fires a retry while the first delivery is still in flight.

---

Yes, it's safe on duplicate delivery: the processor's `reference` is stored under a unique database constraint and inserted before the balance is touched, so a repeat event either short-circuits on the `existsByReference` check or loses the insert race and rolls back — `reduceBalance` runs exactly once either way.
