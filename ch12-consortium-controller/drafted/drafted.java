@RestController
@RequestMapping("/api/consortium")
public class ConsortiumApiController {

    @Autowired
    private CatalogueSearchService catalogueSearchService;
    @Autowired
    private InterlendingService interlendingService;

    @PostMapping("/searchCatalogue")
    public ResponseEntity<Map<String, Object>> searchCatalogue(
            @RequestBody Map<String, String> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<TitleSummary> results =
                catalogueSearchService.search(body.get("query"));
            response.put("success", true);
            response.put("data", results);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/getTitleAvailability")
    public ResponseEntity<Map<String, Object>> getTitleAvailability(
            @RequestParam Long titleId) {
        Map<String, Object> response = new HashMap<>();
        Title title = catalogueSearchService.findById(titleId);
        if (title == null) {
            response.put("success", false);
            response.put("message", "Title not found");
            return ResponseEntity.ok(response);
        }
        response.put("success", true);
        response.put("titleId", title.getId());
        response.put("availableCopies",
            interlendingService.countAvailable(title.getId()));
        return ResponseEntity.ok(response);
    }

    @PutMapping("/renewInterlendingLoan")
    public ResponseEntity<Map<String, Object>> renewInterlendingLoan(
            @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        Long loanId = Long.valueOf(body.get("loanId").toString());
        InterlendingLoan loan = interlendingService.findById(loanId);
        loan.setDueDate(loan.getDueDate().plusDays(21));
        loan.setRenewalCount(loan.getRenewalCount() + 1);
        interlendingService.save(loan);
        response.put("success", true);
        response.put("newDueDate", loan.getDueDate());
        return ResponseEntity.ok(response);
    }
}
