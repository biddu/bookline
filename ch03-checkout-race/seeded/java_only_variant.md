Here's a checkout implementation. The interesting part of this problem is not the happy path — it's making "a copy that already has an open loan must be refused" hold under concurrent requests, so I'll build that in from the start.

## The core idea

An application-level check ("is there an open loan for this barcode?") is necessary for a good error message, but it is not sufficient: two requests can both pass the check before either commits. The real guarantee has to live in the database. I use a **partial unique index** on open loans, so the database itself refuses a second open loan for the same copy, and I translate the constraint violation into the same domain error.

(If you're on MySQL, which lacks partial indexes, the equivalent trick is a generated column `open_marker = IF(returned_at IS NULL, copy_barcode, NULL)` with a unique index on it — unique indexes ignore NULLs.)

## Entity

```java
@Entity
@Table(name = "loan")
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "copy_barcode", nullable = false)
    private String copyBarcode;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "checked_out_at", nullable = false)
    private Instant checkedOutAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "returned_at")
    private Instant returnedAt;   // null = loan is open

    protected Loan() { }          // for JPA

    public Loan(String copyBarcode, Long memberId, Instant checkedOutAt, Instant dueAt) {
        this.copyBarcode = copyBarcode;
        this.memberId = memberId;
        this.checkedOutAt = checkedOutAt;
        this.dueAt = dueAt;
    }

    public boolean isOpen() {
        return returnedAt == null;
    }

    // getters omitted for brevity
}
```

## Repository

```java
public interface LoanRepository extends JpaRepository<Loan, Long> {

    boolean existsByCopyBarcodeAndReturnedAtIsNull(String copyBarcode);
}
```

## Service

```java
@Service
public class CheckoutService {

    private static final Duration LOAN_PERIOD = Duration.ofDays(21);

    private final LoanRepository loanRepository;
    private final Clock clock;

    public CheckoutService(LoanRepository loanRepository, Clock clock) {
        this.loanRepository = loanRepository;
        this.clock = clock;
    }

    @Transactional
    public Loan checkout(Long memberId, String copyBarcode) {
        // Fast, friendly check — catches the common case with a clear error.
        if (loanRepository.existsByCopyBarcodeAndReturnedAtIsNull(copyBarcode)) {
            throw new CopyAlreadyOnLoanException(copyBarcode);
        }

        Instant now = Instant.now(clock);
        Loan loan = new Loan(copyBarcode, memberId, now, now.plus(LOAN_PERIOD));

        try {
            // saveAndFlush so the unique-index violation surfaces HERE,
            // inside this method, not at some later commit point.
            return loanRepository.saveAndFlush(loan);
        } catch (DataIntegrityViolationException e) {
            // Lost the race: another transaction created the open loan
            // between our check and our insert. Same outcome as the check.
            throw new CopyAlreadyOnLoanException(copyBarcode, e);
        }
    }
}
```

```java
public class CopyAlreadyOnLoanException extends RuntimeException {

    public CopyAlreadyOnLoanException(String barcode) {
        super("Copy " + barcode + " already has an open loan");
    }

    public CopyAlreadyOnLoanException(String barcode, Throwable cause) {
        super("Copy " + barcode + " already has an open loan", cause);
    }
}
```

## Controller

```java
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final CheckoutService checkoutService;

    public LoanController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    public record CheckoutRequest(@NotNull Long memberId, @NotBlank String copyBarcode) { }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Loan checkout(@Valid @RequestBody CheckoutRequest request) {
        return checkoutService.checkout(request.memberId(), request.copyBarcode());
    }

    @ExceptionHandler(CopyAlreadyOnLoanException.class)
    public ResponseEntity<Map<String, String>> conflict(CopyAlreadyOnLoanException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage()));
    }
}
```

(`Clock` is injected — register `Clock.systemUTC()` as a bean — so the service is testable without sleeping or mocking statics.)

## Why check twice?

The `existsBy...` check and the unique index look redundant, but they serve different purposes:

- **The check** gives a clean, cheap refusal in the overwhelmingly common case (someone scans a book that's plainly out) without burning an insert attempt.
- **The index** is the actual invariant. It holds no matter how many app instances you run, no matter what other code path inserts a `Loan`, and no matter how the race between check and insert plays out.

If you removed the check, the code would still be correct — just with uglier failure economics. If you removed the index, the code would be *incorrect*: two concurrent requests could both pass the check and both commit, and you'd have one physical book on loan to two members.

## A test that proves the race is handled

```java
@SpringBootTest
class CheckoutConcurrencyTest {

    @Autowired CheckoutService checkoutService;
    @Autowired LoanRepository loanRepository;

    @Test
    void concurrentCheckoutsOfSameCopy_onlyOneSucceeds() throws Exception {
        String barcode = "COPY-0042";
        int attempts = 8;

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            long memberId = 100L + i;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    checkoutService.checkout(memberId, barcode);
                    return true;
                } catch (CopyAlreadyOnLoanException e) {
                    return false;
                }
            }));
        }

        start.countDown();
        long successes = 0;
        for (Future<Boolean> f : results) {
            if (f.get(10, TimeUnit.SECONDS)) successes++;
        }
        pool.shutdown();

        assertThat(successes).isEqualTo(1);
        assertThat(loanRepository.existsByCopyBarcodeAndReturnedAtIsNull(barcode)).isTrue();
    }
}
```

Run this test against the real database (Testcontainers, not H2) — the guarantee lives in the partial unique index, and a test that doesn't exercise the real index isn't testing the guarantee.

One alternative worth knowing: if you had a `Copy` entity, you could instead take a pessimistic lock on the copy row (`@Lock(PESSIMISTIC_WRITE)`) before checking. That also closes the race, but it serializes on a lock you must remember to take on *every* path that creates loans. The constraint approach makes the invariant unforgettable — the schema enforces it even against code that hasn't been written yet.
