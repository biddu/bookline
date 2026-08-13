# ch14-erasure-handler — run 1, verbatim (this is Exhibit 14B in the book)

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls)
- Model: claude-fable-5
- Date: 11 August 2026
- Prompt (verbatim): "We've had a GDPR erasure request from a member. Write the handler that deletes a member's personal data when they exercise their right to erasure."
- Edits made: none.

---

Here's how I'd approach this. The key thing to understand before writing any code: **GDPR erasure is almost never a naive `DELETE FROM members WHERE id = ?`**. Article 17 has carve-outs (Art. 17(3)) — you can retain data needed for legal obligations, and you have a legitimate interest in keeping non-identifying loan statistics. The standard pattern is **anonymize what you must keep, hard-delete what you don't**, and keep a minimal record proving you performed the erasure.

Also, two business rules worth enforcing: don't erase a member who still has books checked out or unpaid fines — resolve those first (this is a legitimate reason to delay under Art. 17, and practically you'd lose the ability to recover the books).

```java
// --- DTO / result types ---------------------------------------------------

public record ErasureResult(UUID erasureReference, Instant erasedAt) {}

// --- Exceptions -----------------------------------------------------------

@ResponseStatus(HttpStatus.CONFLICT)
public class ErasureBlockedException extends RuntimeException {
    public ErasureBlockedException(String message) {
        super(message);
    }
}

// --- Service --------------------------------------------------------------

@Service
public class MemberErasureService {

    private static final Logger log = LoggerFactory.getLogger(MemberErasureService.class);

    private final MemberRepository memberRepository;
    private final LoanRepository loanRepository;
    private final ReservationRepository reservationRepository;
    private final FineRepository fineRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final ErasureRecordRepository erasureRecordRepository;
    private final ApplicationEventPublisher eventPublisher;

    public MemberErasureService(MemberRepository memberRepository,
                                LoanRepository loanRepository,
                                ReservationRepository reservationRepository,
                                FineRepository fineRepository,
                                NotificationPreferenceRepository notificationPreferenceRepository,
                                ErasureRecordRepository erasureRecordRepository,
                                ApplicationEventPublisher eventPublisher) {
        this.memberRepository = memberRepository;
        this.loanRepository = loanRepository;
        this.reservationRepository = reservationRepository;
        this.fineRepository = fineRepository;
        this.notificationPreferenceRepository = notificationPreferenceRepository;
        this.erasureRecordRepository = erasureRecordRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ErasureResult eraseMember(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("Member not found: " + memberId));

        // 1. Preconditions — Art. 17 allows refusing/delaying erasure where we
        //    have an overriding obligation. Books out and money owed qualify.
        if (loanRepository.existsByMemberIdAndReturnedAtIsNull(memberId)) {
            throw new ErasureBlockedException(
                    "Member has active loans. Books must be returned before erasure.");
        }
        if (fineRepository.existsByMemberIdAndSettledFalse(memberId)) {
            throw new ErasureBlockedException(
                    "Member has unsettled fines. These must be resolved before erasure.");
        }

        // 2. Hard-delete data with no retention justification.
        reservationRepository.deleteByMemberId(memberId);
        notificationPreferenceRepository.deleteByMemberId(memberId);

        // 3. Anonymize loan history — we keep it for circulation statistics,
        //    but sever the link to any identifiable person.
        loanRepository.detachMember(memberId);   // UPDATE loans SET member_id = NULL WHERE member_id = :id
        fineRepository.detachMember(memberId);   // settled fines kept for financial records, unlinked

        // 4. Scrub the member row itself. We keep the row (FK stability, ID
        //    non-reuse) but overwrite every PII field — "crypto-shredding
        //    lite". If your schema allows, a hard delete of the row works too.
        UUID erasureRef = UUID.randomUUID();
        member.setName("ERASED");
        member.setEmail("erased+" + erasureRef + "@invalid.local"); // keeps unique constraint happy
        member.setPhoneNumber(null);
        member.setAddress(null);
        member.setDateOfBirth(null);
        member.setBarcodeNumber(null);
        member.setStatus(MemberStatus.ERASED);
        member.setErasedAt(Instant.now());

        // 5. Record that erasure happened — WITHOUT storing the erased data.
        //    This is your proof of compliance if the regulator or the member asks.
        ErasureRecord record = new ErasureRecord(erasureRef, memberId, Instant.now());
        erasureRecordRepository.save(record);

        // 6. Tell the rest of the system (search index, email provider,
        //    analytics, backups pipeline) so PII is purged there too.
        //    AFTER_COMMIT so we never announce an erasure that rolled back.
        eventPublisher.publishEvent(new MemberErasedEvent(memberId, erasureRef));

        log.info("GDPR erasure completed for member ref={}", erasureRef); // note: no PII in logs
        return new ErasureResult(erasureRef, member.getErasedAt());
    }
}

// --- Event + listener boundary --------------------------------------------

public record MemberErasedEvent(Long memberId, UUID erasureReference) {}

@Component
public class MemberErasedEventHandler {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberErased(MemberErasedEvent event) {
        // e.g. remove from Elasticsearch, call Mailchimp/SES suppression API,
        // enqueue purge job for data warehouse. Make these idempotent and retried.
    }
}

// --- Controller -----------------------------------------------------------

@RestController
@RequestMapping("/api/members")
public class MemberErasureController {

    private final MemberErasureService erasureService;

    public MemberErasureController(MemberErasureService erasureService) {
        this.erasureService = erasureService;
    }

    @DeleteMapping("/{memberId}/personal-data")
    @PreAuthorize("hasRole('ADMIN') or @memberSecurity.isSelf(#memberId, authentication)")
    public ResponseEntity<ErasureResult> eraseMemberData(@PathVariable Long memberId) {
        ErasureResult result = erasureService.eraseMember(memberId);
        return ResponseEntity.ok(result);
    }
}
```

And the erasure record entity — deliberately minimal:

```java
@Entity
@Table(name = "erasure_records")
public class ErasureRecord {
    @Id
    private UUID reference;
    private Long formerMemberId;   // internal surrogate key only — not PII by itself
    private Instant erasedAt;
    // no name, no email, no snapshot of what was deleted
    ...
}
```

A few things I want to flag, because they're where GDPR implementations usually go wrong:

**1. Why anonymize instead of delete the member row?** Loan history has FK references to the member. If you cascade-delete, you destroy circulation stats and financial history you're likely entitled (or required) to keep. Nulling the FK and scrubbing the PII achieves erasure — once no field can identify the person, the remaining data is no longer "personal data" under GDPR. If your `loans` table has denormalized PII (borrower name column, etc.), scrub those columns too — check every table, including audit tables.

**2. The dummy email.** Setting email to a unique tombstone value rather than `null` avoids breaking a `NOT NULL`/unique constraint and prevents the old address being re-registered as a collision. Make sure it can never receive mail.

**3. Verify identity before honoring the request.** The `@PreAuthorize` covers the authenticated case, but if requests come in by email you need an identity verification step upstream — erasing the wrong person's account (or erasing on an attacker's request) is itself a breach.

**4. Downstream systems and backups.** The database is rarely the only place PII lives: search indexes, email marketing lists, log aggregation, data warehouse, backups. The event listener is where you fan that out. For backups, the accepted practice is documented policy: you don't rewrite old backups, but you ensure erasure is re-applied if a backup is restored (keep the `ErasureRecord` around precisely for this).

**5. Watch your logs.** Note the service logs only the erasure reference. If your request logging or JPA SQL logging captures the old values, you've just copied PII into another retention system.

**6. Timing.** GDPR gives you one month to comply. If you want to be safer against accidental requests, a common pattern is a soft-delete with a short grace period (e.g. mark `PENDING_ERASURE`, run the real scrub via a scheduled job after 7 days) — easy to add on top of this by moving the body of `eraseMember` into the scheduled job.

One thing to decide with your DPO: whether `formerMemberId` in the erasure record is acceptable, or whether you should store only a salted hash of it. Keeping the raw internal ID is defensible (it identifies nothing once the member row is scrubbed), but some auditors prefer the hash.
