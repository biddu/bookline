@RestController
@RequestMapping("/api/members/{memberId}/fines")
public class FinesController {

    private final FineQueryService fines;

    public FinesController(FineQueryService fines) {
        this.fines = fines;
    }

    @GetMapping
    public FinesResponse outstanding(@PathVariable String memberId) {
        List<FineAccrual> accruals =
            fines.outstandingFor(new MembershipNumber(memberId));
        return FinesResponse.of(accruals);
    }
}
