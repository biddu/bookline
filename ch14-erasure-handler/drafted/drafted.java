@Service
public class MemberErasureService {

    private static final Logger log =
        LoggerFactory.getLogger(MemberErasureService.class);

    private final MemberRepository memberRepository;

    public MemberErasureService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /**
     * Handles a GDPR right-to-erasure request.
     */
    @Transactional
    public void eraseMember(MembershipNumber membershipNumber) {
        Member member = memberRepository.findByNumber(membershipNumber)
            .orElseThrow(() -> new MemberNotFoundException(membershipNumber));
        memberRepository.delete(member);
        log.info("GDPR erasure completed for member {}", membershipNumber);
    }
}
