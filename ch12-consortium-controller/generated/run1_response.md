# ch12-consortium-controller — run 1, verbatim (this is Exhibit 12A in the book)

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim): "Build a REST endpoint so consortium partners can search our catalogue and check whether a title is available."
- Tool calls: zero. Edits made: none.

---

Here's how I'd build this. Since it's a partner-facing endpoint, I'm treating it as a versioned, read-only API with pagination, input validation, and DTOs (never expose entities to external consumers). I'm assuming Spring Boot 3.x / Java 17+ and Spring Data JPA — shout if you're on something older and I'll adjust.

**Endpoints:**

- `GET /api/v1/catalogue/search?q=...&author=...&isbn=...&page=0&size=20` — search the catalogue
- `GET /api/v1/catalogue/items/{isbn}/availability` — availability check for a single title

**1. DTOs**

```java
// CatalogueItemDto.java
public record CatalogueItemDto(
        String isbn,
        String title,
        String author,
        String publisher,
        Integer publicationYear,
        boolean available) {
}

// AvailabilityDto.java
public record AvailabilityDto(
        String isbn,
        String title,
        boolean available,
        int totalCopies,
        int copiesAvailable,
        LocalDate earliestReturnDate) {   // null when a copy is on the shelf
}

// SearchResultDto.java — stable page envelope so partners aren't coupled to Spring's Page serialization
public record SearchResultDto<T>(
        List<T> results,
        int page,
        int size,
        long totalResults,
        int totalPages) {

    public static <T> SearchResultDto<T> from(Page<T> page) {
        return new SearchResultDto<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
```

**2. Entity and repository** (adapt field names to your existing schema)

```java
// CatalogueItem.java
@Entity
@Table(name = "catalogue_item")
public class CatalogueItem {

    @Id
    private String isbn;

    private String title;
    private String author;
    private String publisher;
    private Integer publicationYear;
    private int totalCopies;
    private int copiesAvailable;
    private LocalDate earliestReturnDate;

    // getters/setters or use Lombok @Getter/@Setter
}

// CatalogueItemRepository.java
public interface CatalogueItemRepository
        extends JpaRepository<CatalogueItem, String>,
                JpaSpecificationExecutor<CatalogueItem> {
}
```

**3. Search specification**

```java
// CatalogueSpecifications.java
public final class CatalogueSpecifications {

    private CatalogueSpecifications() {
    }

    public static Specification<CatalogueItem> matches(String q, String author, String isbn) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(q)) {
                String like = "%" + q.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("author")), like)));
            }
            if (StringUtils.hasText(author)) {
                predicates.add(cb.like(
                        cb.lower(root.get("author")),
                        "%" + author.trim().toLowerCase() + "%"));
            }
            if (StringUtils.hasText(isbn)) {
                predicates.add(cb.equal(root.get("isbn"), normalizeIsbn(isbn)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    public static String normalizeIsbn(String isbn) {
        return isbn.replaceAll("[\\s-]", "");
    }
}
```

**4. Service**

```java
@Service
@Transactional(readOnly = true)
public class CatalogueService {

    private final CatalogueItemRepository repository;

    public CatalogueService(CatalogueItemRepository repository) {
        this.repository = repository;
    }

    public SearchResultDto<CatalogueItemDto> search(
            String q, String author, String isbn, Pageable pageable) {

        Page<CatalogueItemDto> page = repository
                .findAll(CatalogueSpecifications.matches(q, author, isbn), pageable)
                .map(this::toDto);
        return SearchResultDto.from(page);
    }

    public AvailabilityDto checkAvailability(String isbn) {
        String normalized = CatalogueSpecifications.normalizeIsbn(isbn);
        CatalogueItem item = repository.findById(normalized)
                .orElseThrow(() -> new TitleNotFoundException(normalized));

        return new AvailabilityDto(
                item.getIsbn(),
                item.getTitle(),
                item.getCopiesAvailable() > 0,
                item.getTotalCopies(),
                item.getCopiesAvailable(),
                item.getCopiesAvailable() > 0 ? null : item.getEarliestReturnDate());
    }

    private CatalogueItemDto toDto(CatalogueItem item) {
        return new CatalogueItemDto(
                item.getIsbn(),
                item.getTitle(),
                item.getAuthor(),
                item.getPublisher(),
                item.getPublicationYear(),
                item.getCopiesAvailable() > 0);
    }
}
```

**5. Controller**

```java
@RestController
@RequestMapping("/api/v1/catalogue")
@Validated
public class CatalogueController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CatalogueService catalogueService;

    public CatalogueController(CatalogueService catalogueService) {
        this.catalogueService = catalogueService;
    }

    @GetMapping("/search")
    public SearchResultDto<CatalogueItemDto> search(
            @RequestParam(required = false) @Size(min = 2, max = 200) String q,
            @RequestParam(required = false) @Size(max = 200) String author,
            @RequestParam(required = false)
            @Pattern(regexp = "[0-9Xx\\s-]{10,17}", message = "invalid ISBN format") String isbn,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        if (!StringUtils.hasText(q) && !StringUtils.hasText(author) && !StringUtils.hasText(isbn)) {
            throw new InvalidSearchException(
                    "At least one search parameter (q, author, isbn) is required");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by("title").ascending());
        return catalogueService.search(q, author, isbn, pageable);
    }

    @GetMapping("/items/{isbn}/availability")
    public AvailabilityDto availability(
            @PathVariable @Pattern(regexp = "[0-9Xx\\s-]{10,17}", message = "invalid ISBN format")
            String isbn) {
        return catalogueService.checkAvailability(isbn);
    }
}
```

**6. Exceptions and error handling** (consistent JSON errors so partners can code against them)

```java
public class TitleNotFoundException extends RuntimeException {
    public TitleNotFoundException(String isbn) {
        super("No title found for ISBN " + isbn);
    }
}

public class InvalidSearchException extends RuntimeException {
    public InvalidSearchException(String message) {
        super(message);
    }
}

@RestControllerAdvice
public class CatalogueExceptionHandler {

    @ExceptionHandler(TitleNotFoundException.class)
    public ProblemDetail handleNotFound(TitleNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({InvalidSearchException.class, ConstraintViolationException.class})
    public ProblemDetail handleBadRequest(Exception ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
```

**Example responses**

`GET /api/v1/catalogue/search?q=pragmatic&page=0&size=20`

```json
{
  "results": [
    {
      "isbn": "9780135957059",
      "title": "The Pragmatic Programmer",
      "author": "David Thomas; Andrew Hunt",
      "publisher": "Addison-Wesley",
      "publicationYear": 2019,
      "available": true
    }
  ],
  "page": 0,
  "size": 20,
  "totalResults": 1,
  "totalPages": 1
}
```

`GET /api/v1/catalogue/items/9780135957059/availability`

```json
{
  "isbn": "9780135957059",
  "title": "The Pragmatic Programmer",
  "available": false,
  "totalCopies": 3,
  "copiesAvailable": 0,
  "earliestReturnDate": "2026-08-24"
}
```

**Design notes and things you still need to decide:**

1. **Authentication.** This must not go out unauthenticated. For consortium partners the usual pattern is either per-partner API keys checked in a `OncePerRequestFilter`, or OAuth2 client-credentials if you already run an identity provider. Minimal Spring Security config to lock the path down while you decide:

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/v1/catalogue/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable);
    return http.build();
}
```

2. **Capped page size (100)** so a partner can't request the whole catalogue in one call; fixed sort so pagination is stable.
3. **`LIKE '%...%'` won't use an index.** Fine up to maybe low hundreds of thousands of rows; beyond that, move search to Postgres full-text (`tsvector`) or Elasticsearch/OpenSearch and keep this API contract unchanged — that's why the controller doesn't leak the query mechanism.
4. **Availability semantics.** I modelled `copiesAvailable` as a column, which assumes your circulation module keeps it updated. If holdings live in a separate table, compute it with a count query in the service instead — the DTO stays the same.
5. **Rate limiting** per partner (Bucket4j or your API gateway) is worth adding before go-live; partners' batch jobs love hammering search endpoints at 2 a.m.
6. **Tests.** At minimum: a `@WebMvcTest` for the controller (validation, 400 on empty search, 404 mapping) and a `@DataJpaTest` for the specification. Happy to write those next if you want.

The one thing I'd push back on if anyone suggests it: don't return the JPA entity or Spring's raw `Page` object directly. Both leak internals into a contract external partners will build against, and you'll be stuck with it.
