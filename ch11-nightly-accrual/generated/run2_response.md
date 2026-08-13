# ch11-nightly-accrual — run 2, verbatim

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim, identical to run 1): "Write a Spring scheduled job that runs nightly at 2am and charges overdue fines: 30 cents per day for each overdue loan."
- Edits made: none.

---

Here's how I'd build this. The one design decision worth getting right up front: **don't increment the fine by 30 cents each night** — instead, recompute the fine from scratch as `daysOverdue × $0.30` on every run. Incremental charging silently corrupts data if the job misses a night (deploy, outage) or runs twice (retry, second app instance). Recomputing is idempotent: you can run it five times in a row and the numbers stay correct.

**1. Enable scheduling**

```java
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

**2. Entities (relevant parts)**

```java
@Entity
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Member member;

    @Column(nullable = false)
    private LocalDate dueDate;

    /** null while the book is still out */
    private LocalDate returnDate;

    /** accrued overdue fine; recomputed nightly */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fineAmount = BigDecimal.ZERO;

    // getters/setters ...
}
```

Note `BigDecimal` for money — never `double`.

**3. Repository**

```java
public interface LoanRepository extends JpaRepository<Loan, Long> {

    Page<Loan> findByReturnDateIsNullAndDueDateBefore(LocalDate date, Pageable pageable);
}
```

**4. The service that does the actual work**

```java
@Service
public class OverdueFineService {

    private static final Logger log = LoggerFactory.getLogger(OverdueFineService.class);
    private static final BigDecimal DAILY_RATE = new BigDecimal("0.30");
    private static final int PAGE_SIZE = 500;

    private final LoanRepository loanRepository;
    private final Clock clock;

    public OverdueFineService(LoanRepository loanRepository, Clock clock) {
        this.loanRepository = loanRepository;
        this.clock = clock;
    }

    public void assessOverdueFines() {
        LocalDate today = LocalDate.now(clock);
        int updated = 0;

        Pageable page = PageRequest.of(0, PAGE_SIZE, Sort.by("id"));
        Page<Loan> batch;
        do {
            batch = processPage(today, page);
            updated += batch.getNumberOfElements();
            page = page.next();
        } while (batch.hasNext());

        log.info("Overdue fine assessment complete: {} loans updated for {}", updated, today);
    }

    @Transactional
    protected Page<Loan> processPage(LocalDate today, Pageable page) {
        Page<Loan> overdueLoans = loanRepository.findByReturnDateIsNullAndDueDateBefore(today, page);
        for (Loan loan : overdueLoans) {
            long daysOverdue = ChronoUnit.DAYS.between(loan.getDueDate(), today);
            loan.setFineAmount(DAILY_RATE.multiply(BigDecimal.valueOf(daysOverdue)));
        }
        return overdueLoans; // dirty checking flushes the updates on commit
    }
}
```

**5. The scheduler itself** — kept as a thin trigger, separate from the business logic so the logic is unit-testable and reusable (e.g., an admin "recalculate now" endpoint):

```java
@Component
public class OverdueFineScheduler {

    private static final Logger log = LoggerFactory.getLogger(OverdueFineScheduler.class);

    private final OverdueFineService overdueFineService;

    public OverdueFineScheduler(OverdueFineService overdueFineService) {
        this.overdueFineService = overdueFineService;
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "America/New_York")
    public void chargeOverdueFines() {
        try {
            overdueFineService.assessOverdueFines();
        } catch (Exception e) {
            // don't let one bad night kill the scheduler thread silently
            log.error("Nightly overdue fine assessment failed", e);
        }
    }
}
```

**6. Clock bean** (makes the date logic testable — you can pin the clock in tests instead of sleeping until 2am):

```java
@Configuration
public class TimeConfig {
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
```

A few things to flag:

- **Cron format:** Spring's cron has six fields (seconds first), so `0 0 2 * * *` = 02:00:00 daily. Set `zone` explicitly — otherwise it uses server-default timezone, which on a UTC container means 2am UTC, probably not what the library intends. Also be aware 2am is inside the DST "spring forward" gap one night a year; Spring handles it (the run shifts), but if that matters to you, 3am avoids it entirely.
- **Multiple instances:** `@Scheduled` fires on *every* app instance. If you run more than one node, add [ShedLock](https://github.com/lukas-krecan/ShedLock) (`@SchedulerLock(name = "chargeOverdueFines")`) so only one instance runs it. Because the job is idempotent, a double run wouldn't corrupt data — but there's no reason to do the work twice.
- **Day-count semantics:** `DAYS.between(dueDate, today)` means a book due yesterday carries a $0.30 fine today. If your policy is "fine starts accruing *on* the due date" or you cap fines at the book's replacement cost, adjust the formula in one place — `processPage`.
- **Paging:** processing 500 loans per transaction keeps memory flat and transactions short on large tables. If you're at the scale where even that's slow, the whole thing can become a single bulk `UPDATE ... SET fine_amount = datediff(...) * 0.30 WHERE return_date IS NULL AND due_date < :today` — but the entity-based version is easier to extend when someone inevitably asks for "email the member when the fine changes."

Quick sanity test for the core logic:

```java
@Test
void finesAreRecomputedFromDaysOverdue() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-10T07:00:00Z"), ZoneOffset.UTC);
    Loan loan = loanDue(LocalDate.of(2026, 8, 5)); // 5 days overdue
    when(loanRepository.findByReturnDateIsNullAndDueDateBefore(any(), any()))
        .thenReturn(new PageImpl<>(List.of(loan)));

    new OverdueFineService(loanRepository, fixed).assessOverdueFines();

    assertThat(loan.getFineAmount()).isEqualByComparingTo("1.50");
}
```

That's the whole thing — scheduler triggers, service recomputes fines idempotently in pages, and the money math stays in `BigDecimal` end to end.
