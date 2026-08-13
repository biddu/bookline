# ch12-consortium-controller — run 2, verbatim

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim, identical to run 1): "Build a REST endpoint so consortium partners can search our catalogue and check whether a title is available."
- Tool calls: zero. Edits made: none.
- **Note: this run returns Spring's `Page` type directly from the controller, which is the exact choice run 1 closes by naming as the thing it would push back on.**

---

Here's how I'd build this. I'm assuming we already have a `Book` entity in the catalogue with a `copies`/`availability` notion — adjust field names to match our schema. The design is two endpoints under a partner-facing API namespace:

- `GET /api/partner/v1/catalogue/search` — paginated search by title/author/ISBN
- `GET /api/partner/v1/catalogue/{isbn}/availability` — lightweight availability check for a single title

Keeping availability as its own endpoint matters: partners polling "is this on the shelf?" shouldn't pay for (or cache-bust) a full search, and we can put a short TTL cache on it later without touching search.

**DTOs** (records, so they're immutable and we never leak the JPA entity out of the API):

```java
// api/partner/dto/BookSummaryDto.java
public record BookSummaryDto(
        String isbn,
        String title,
        String author,
        Integer publicationYear,
        boolean available
) {}

// api/partner/dto/AvailabilityDto.java
public record AvailabilityDto(
        String isbn,
        String title,
        boolean available,
        int copiesTotal,
        int copiesAvailable
) {}
```

**Repository** — one derived query for the availability lookup, one JPQL query for search so blank criteria are simply ignored:

```java
public interface BookRepository extends JpaRepository<Book, Long> {

    Optional<Book> findByIsbn(String isbn);

    @Query("""
           SELECT b FROM Book b
           WHERE (:title IS NULL OR LOWER(b.title) LIKE LOWER(CONCAT('%', :title, '%')))
             AND (:author IS NULL OR LOWER(b.author) LIKE LOWER(CONCAT('%', :author, '%')))
             AND (:isbn IS NULL OR b.isbn = :isbn)
           """)
    Page<Book> search(@Param("title") String title,
                      @Param("author") String author,
                      @Param("isbn") String isbn,
                      Pageable pageable);
}
```

**Service:**

```java
@Service
@Transactional(readOnly = true)
public class CatalogueSearchService {

    private final BookRepository bookRepository;

    public CatalogueSearchService(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    public Page<BookSummaryDto> search(String title, String author, String isbn, Pageable pageable) {
        return bookRepository.search(blankToNull(title), blankToNull(author), blankToNull(isbn), pageable)
                .map(b -> new BookSummaryDto(
                        b.getIsbn(), b.getTitle(), b.getAuthor(),
                        b.getPublicationYear(), b.getCopiesAvailable() > 0));
    }

    public AvailabilityDto availability(String isbn) {
        Book b = bookRepository.findByIsbn(isbn)
                .orElseThrow(() -> new TitleNotFoundException(isbn));
        return new AvailabilityDto(
                b.getIsbn(), b.getTitle(),
                b.getCopiesAvailable() > 0,
                b.getCopiesTotal(), b.getCopiesAvailable());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
```

**Controller:**

```java
@RestController
@RequestMapping("/api/partner/v1/catalogue")
@Validated
public class PartnerCatalogueController {

    private final CatalogueSearchService searchService;

    public PartnerCatalogueController(CatalogueSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public Page<BookSummaryDto> search(
            @RequestParam(required = false) @Size(min = 2, max = 200) String title,
            @RequestParam(required = false) @Size(min = 2, max = 100) String author,
            @RequestParam(required = false)
            @Pattern(regexp = "\\d{10}|\\d{13}", message = "ISBN must be 10 or 13 digits") String isbn,
            @PageableDefault(size = 20, sort = "title") Pageable pageable) {

        if (isAllBlank(title, author, isbn)) {
            throw new MissingSearchCriteriaException();
        }
        return searchService.search(title, author, isbn, capPageSize(pageable));
    }

    @GetMapping("/{isbn}/availability")
    public AvailabilityDto availability(
            @PathVariable @Pattern(regexp = "\\d{10}|\\d{13}") String isbn) {
        return searchService.availability(isbn);
    }

    private static boolean isAllBlank(String... values) {
        return Arrays.stream(values).allMatch(v -> v == null || v.isBlank());
    }

    private static Pageable capPageSize(Pageable p) {
        int size = Math.min(p.getPageSize(), 100);
        return PageRequest.of(p.getPageNumber(), size, p.getSort());
    }
}
```

**Exceptions and error handling** — partners get proper status codes and a stable error shape, never a stack trace:

```java
public class TitleNotFoundException extends RuntimeException {
    public TitleNotFoundException(String isbn) {
        super("No title in catalogue with ISBN " + isbn);
    }
}

public class MissingSearchCriteriaException extends RuntimeException {
    public MissingSearchCriteriaException() {
        super("Provide at least one of: title, author, isbn");
    }
}

@RestControllerAdvice(assignableTypes = PartnerCatalogueController.class)
public class PartnerApiExceptionHandler {

    record ApiError(String code, String message) {}

    @ExceptionHandler(TitleNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError notFound(TitleNotFoundException ex) {
        return new ApiError("TITLE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(MissingSearchCriteriaException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError badRequest(MissingSearchCriteriaException ex) {
        return new ApiError("MISSING_CRITERIA", ex.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidParam(ConstraintViolationException ex) {
        return new ApiError("INVALID_PARAMETER", ex.getMessage());
    }
}
```

Example calls:

```
GET /api/partner/v1/catalogue/search?title=pragmatic&page=0&size=20
GET /api/partner/v1/catalogue/search?author=fowler
GET /api/partner/v1/catalogue/9780134757599/availability
```

A few decisions worth flagging:

1. **Require at least one criterion and cap page size at 100.** Without both, a partner script can accidentally table-scan us with `GET /search` and `size=5000`. Cheap insurance.
2. **ISBN as the public identifier**, not our internal database ID. Internal IDs are meaningless to partners and leak schema details; ISBN is the identifier they already have.
3. **Availability is computed, not stored on the DTO path** — `copiesAvailable > 0` at read time, `readOnly = true` transactions. If availability checks get heavy, add `@Cacheable` on `availability()` with a 30–60s TTL; the endpoint separation makes that a one-line change.
4. **Security is not in this snippet but is not optional.** Since this is partner-facing, the `/api/partner/**` path should be locked down in our `SecurityFilterChain` — API key per partner or client-credentials OAuth, whichever we already use for external integrations. Happy to wire that up as a follow-up; I didn't want to guess at our existing auth setup.
5. **The `LIKE '%...%'` search is fine for a modest catalogue.** If the table is large or partners want fuzzy matching, we should move search to a proper full-text index (Postgres `tsvector` or the `pg_trgm` extension) rather than tuning JPQL — say the word and I'll sketch that version.

Quick test to pin the contract:

```java
@WebMvcTest(PartnerCatalogueController.class)
class PartnerCatalogueControllerTest {

    @Autowired MockMvc mvc;
    @MockBean CatalogueSearchService searchService;

    @Test
    void searchWithoutCriteriaIs400() throws Exception {
        mvc.perform(get("/api/partner/v1/catalogue/search"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("MISSING_CRITERIA"));
    }

    @Test
    void availabilityUnknownIsbnIs404() throws Exception {
        when(searchService.availability("9999999999999"))
            .thenThrow(new TitleNotFoundException("9999999999999"));
        mvc.perform(get("/api/partner/v1/catalogue/9999999999999/availability"))
           .andExpect(status().isNotFound());
    }
}
```

Let me know the actual field names on `Book` (I guessed `copiesTotal`/`copiesAvailable`) and which auth mechanism partners will use, and I'll finish the security config.
