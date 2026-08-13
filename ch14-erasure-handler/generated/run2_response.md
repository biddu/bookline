# ch14-erasure-handler — run 2, verbatim

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls)
- Model: claude-fable-5
- Date: 11 August 2026
- Prompt: identical to run 1.
- Edits made: none.
- **Note: idempotent on a repeated request, revokes sessions and refresh tokens, and raises the confirmation-email ordering problem run 1 misses. Names the outbox as the fix and does not build it.**

---

Here's how I'd build this. One important thing before the code: **for a library system, "erasure" should mostly mean anonymization, not a hard `DELETE`**. Article 17 isn't absolute — Article 17(3)(b) lets you retain data needed to comply with legal obligations (e.g., financial records for fines/fees, which many jurisdictions require you to keep for 6–10 years), and you likely want circulation statistics to survive. So the pattern is: scrub the PII, keep the anonymized transactional skeleton, hard-delete anything that's purely personal (marketing prefs, notes, sessions).

**Controller**

```java
@RestController
@RequestMapping("/api/v1/gdpr")
@RequiredArgsConstructor
public class GdprErasureController {

    private final MemberErasureService erasureService;

    @DeleteMapping("/members/{memberId}")
    @PreAuthorize("hasRole('DATA_PROTECTION_OFFICER') or (hasRole('MEMBER') and #memberId == authentication.principal.memberId)")
    public ResponseEntity<ErasureReceipt> eraseMember(@PathVariable UUID memberId) {
        ErasureReceipt receipt = erasureService.erase(memberId);
        return ResponseEntity.ok(receipt);
    }

    @ExceptionHandler(ErasureBlockedException.class)
    public ResponseEntity<ProblemDetail> handleBlocked(ErasureBlockedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Erasure request blocked");
        return ResponseEntity.of(pd).build();
    }
}
```

**Service**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class MemberErasureService {

    private final MemberRepository memberRepository;
    private final LoanRepository loanRepository;
    private final FineRepository fineRepository;
    private final ReservationRepository reservationRepository;
    private final NotificationPreferenceRepository notificationPrefRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ErasureAuditRepository erasureAuditRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    private static final String REDACTED = "ERASED";

    @Transactional
    public ErasureReceipt erase(UUID memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException(memberId));

        // Idempotency: a repeat request just returns the original receipt.
        if (member.getStatus() == MemberStatus.ERASED) {
            return erasureAuditRepository.findByMemberId(memberId)
                    .map(ErasureReceipt::from)
                    .orElseThrow(IllegalStateException::new);
        }

        // 1. Preconditions — erasure can be refused while obligations are open.
        List<String> blockers = new ArrayList<>();
        if (loanRepository.existsByMemberIdAndReturnedAtIsNull(memberId)) {
            blockers.add("Member has books currently on loan.");
        }
        if (fineRepository.existsByMemberIdAndSettledFalse(memberId)) {
            blockers.add("Member has unpaid fines.");
        }
        if (!blockers.isEmpty()) {
            throw new ErasureBlockedException(blockers);
        }

        // 2. Hard-delete purely personal data with no retention basis.
        reservationRepository.deleteByMemberIdAndFulfilledFalse(memberId);
        notificationPrefRepository.deleteByMemberId(memberId);
        refreshTokenRepository.deleteByMemberId(memberId);   // kill their sessions

        // 3. Anonymize records we retain under Art. 17(3)(b).
        //    Loans and settled fines keep the memberId FK, which now points
        //    at a scrubbed shell record, so aggregate stats still work.

        // 4. Scrub the member record itself.
        member.setFirstName(REDACTED);
        member.setLastName(REDACTED);
        // Email/username often carry unique constraints — use a non-reversible placeholder.
        member.setEmail("erased+" + memberId + "@invalid.local");
        member.setPhoneNumber(null);
        member.setAddress(null);
        member.setDateOfBirth(null);
        member.setPasswordHash(null);
        member.setProfilePhotoUrl(null);
        member.setNotes(null);
        member.setStatus(MemberStatus.ERASED);
        member.setErasedAt(Instant.now(clock));

        // 5. Audit record — proves compliance, contains NO personal data.
        ErasureAudit audit = ErasureAudit.builder()
                .memberId(memberId)                 // pseudonymous key only
                .erasedAt(member.getErasedAt())
                .performedBy(currentActorId())
                .build();
        erasureAuditRepository.save(audit);

        // 6. Fan out to everything outside this database.
        //    AFTER_COMMIT listener so we never notify on a rolled-back erasure.
        eventPublisher.publishEvent(new MemberErasedEvent(memberId));

        log.info("GDPR erasure completed for member {}", memberId); // ID only, never PII
        return ErasureReceipt.from(audit);
    }

    private UUID currentActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ((AppPrincipal) auth.getPrincipal()).getUserId();
    }
}
```

**Event listener for downstream systems**

```java
@Component
@RequiredArgsConstructor
public class MemberErasedEventHandler {

    private final SearchIndexClient searchIndexClient;
    private final EmailProviderClient emailProviderClient;
    private final AnalyticsClient analyticsClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(MemberErasedEvent event) {
        searchIndexClient.deleteMember(event.memberId());        // Elasticsearch etc.
        emailProviderClient.deleteContact(event.memberId());     // Mailchimp/SES lists
        analyticsClient.requestDeletion(event.memberId());       // GA4/Mixpanel deletion API
    }
}
```

**The reasoning I'd flag in review:**

1. **Anonymize the member, keep the FK graph intact.** If you hard-delete the `Member` row you either cascade-delete loan history (bad for stats and possibly for your legal retention duties) or break referential integrity. A scrubbed shell row with status `ERASED` sidesteps both.

2. **Blockers are legitimate.** You're allowed to say "return the books and settle the fine first" — erasure doesn't extinguish contractual obligations. Return a `409` with the reasons so the front desk can act on it.

3. **The audit table is deliberately PII-free.** You need to prove *that* you erased, and when, without keeping *what* you erased. The member UUID is fine to keep — post-erasure it's no longer linkable to a person by you.

4. **Unique constraints.** The `erased+<uuid>@invalid.local` trick matters if `email` has a unique index; setting everyone's email to the same `"ERASED"` string blows up on the second erasure. Same applies to usernames.

5. **`AFTER_COMMIT` for external calls.** Never call the search index or email provider inside the transaction — if the DB rolls back you've told Mailchimp to delete a contact you still hold. If any downstream call can fail, consider persisting the event to an outbox table instead so the erasure is retried until every system confirms (you have one month under Art. 12(3) to complete the request, so async retry is fine).

6. **Kill sessions and tokens.** Easy to forget — an erased member with a live refresh token is a walking contradiction.

**Things this code intentionally does not solve, but you must address in process:**

- **Backups.** You can't practically scrub PII from historical backups. The accepted approach is documenting that backups expire on a fixed schedule (e.g., 30 days) and that erased data is not restored — if you ever restore a backup, you must re-run erasures logged in the audit table after the backup timestamp. That's exactly why the audit table exists.
- **Logs.** Make sure your log pipeline never logged emails/names in the first place; if it did, that's a separate cleanup with your log retention policy.
- **Identity verification.** Before the DPO triggers this for a request that came in by email, verify the requester actually is the member. The `@PreAuthorize` handles the in-app case only.
- **Confirmation to the member.** Art. 12 requires you to inform them of completion — send the confirmation *before* you delete their email, or capture it transiently for that one message.

If you want, I can also add the `ErasureBlockedException`/`ErasureReceipt` classes and a Flyway migration for the audit table, but the shapes are straightforward.
