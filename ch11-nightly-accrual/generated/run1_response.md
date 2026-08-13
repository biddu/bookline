# ch11-nightly-accrual — run 1, verbatim

- Tool: Claude (Cowork; clean context — fresh agent, no book files, no defect contract)
- Model: claude-fable-5
- Date: 10 August 2026
- Prompt (verbatim): "Write a Spring scheduled job that runs nightly at 2am and charges overdue fines: 30 cents per day for each overdue loan."
- Edits made: none.

---

Here's how I'd build this. The scheduling part is the easy bit — the parts that actually matter are idempotency (what happens if the job runs twice, or misses a night?) and money handling. I'll walk through those after the code.

**1. Enable scheduling**

```java
package com.example.library.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class SchedulingConfig {

    // Inject Clock everywhere instead of calling LocalDate.now() directly,
    // so tests can pin "today" to a fixed date.
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
```

**2. Loan entity (relevant fields only)**

```java
package com.example.library.loan;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDate;

@Entity
@Table(name = "loans")
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate dueDate;

    /** null while the item is still out */
    private LocalDate returnDate;

    /** Fines are accrued up to and including this date. Null = never fined yet. */
    private LocalDate fineAccruedThrough;

    /** Money in integer cents — never double/float for currency. */
    private long fineCents;

    @Version
    private long version; // optimistic locking, cheap insurance

    public Long getId() { return id; }
    public LocalDate getDueDate() { return dueDate; }
    public LocalDate getReturnDate() { return returnDate; }
    public LocalDate getFineAccruedThrough() { return fineAccruedThrough; }
    public long getFineCents() { return fineCents; }

    public void addFine(long cents, LocalDate accruedThrough) {
        this.fineCents += cents;
        this.fineAccruedThrough = accruedThrough;
    }
}
```

**3. Repository**

```java
package com.example.library.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    /**
     * Loans that are still out, past due, and haven't yet been fined up to today.
     * Fetch only the IDs so the scheduler can process each loan in its own
     * short transaction instead of holding one giant one.
     */
    @Query("""
           select l.id from Loan l
           where l.returnDate is null
             and l.dueDate < :today
             and (l.fineAccruedThrough is null or l.fineAccruedThrough < :today)
           """)
    List<Long> findLoanIdsNeedingFineAccrual(@Param("today") LocalDate today);
}
```

**4. The fine service (one transaction per loan)**

```java
package com.example.library.loan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class OverdueFineService {

    private static final Logger log = LoggerFactory.getLogger(OverdueFineService.class);

    static final long DAILY_FINE_CENTS = 30;

    private final LoanRepository loanRepository;

    public OverdueFineService(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    /**
     * Accrues the fine for a single loan up to {@code today}.
     * Idempotent: charges only the days between the last accrual point
     * (or the due date) and today, then records the new accrual point.
     */
    @Transactional
    public void accrueFine(Long loanId, LocalDate today) {
        Loan loan = loanRepository.findById(loanId).orElse(null);
        if (loan == null || loan.getReturnDate() != null) {
            return; // returned (or deleted) since we built the ID list
        }

        LocalDate accruedFrom = loan.getFineAccruedThrough() != null
                ? loan.getFineAccruedThrough()
                : loan.getDueDate();

        long daysToCharge = ChronoUnit.DAYS.between(accruedFrom, today);
        if (daysToCharge <= 0) {
            return; // already up to date — safe re-run
        }

        long amount = daysToCharge * DAILY_FINE_CENTS;
        loan.addFine(amount, today);

        log.info("Loan {}: charged {} day(s) overdue = {} cents (total fine now {} cents)",
                loanId, daysToCharge, amount, loan.getFineCents());
    }
}
```

**5. The scheduled job**

```java
package com.example.library.loan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Component
public class OverdueFineScheduler {

    private static final Logger log = LoggerFactory.getLogger(OverdueFineScheduler.class);

    private final LoanRepository loanRepository;
    private final OverdueFineService fineService;
    private final Clock clock;

    public OverdueFineScheduler(LoanRepository loanRepository,
                                OverdueFineService fineService,
                                Clock clock) {
        this.loanRepository = loanRepository;
        this.fineService = fineService;
        this.clock = clock;
    }

    /** Spring cron has 6 fields (seconds first): 2:00:00 AM every day. */
    @Scheduled(cron = "0 0 2 * * *", zone = "America/New_York")
    public void chargeOverdueFines() {
        LocalDate today = LocalDate.now(clock);
        List<Long> loanIds = loanRepository.findLoanIdsNeedingFineAccrual(today);
        log.info("Overdue fine job starting: {} loan(s) to process", loanIds.size());

        int failures = 0;
        for (Long loanId : loanIds) {
            try {
                fineService.accrueFine(loanId, today);
            } catch (Exception e) {
                failures++;
                log.error("Failed to accrue fine for loan {}", loanId, e);
                // keep going — one bad row shouldn't block the whole batch
            }
        }
        log.info("Overdue fine job finished: {} processed, {} failed",
                loanIds.size() - failures, failures);
    }
}
```

**Why it's shaped this way**

- **Cron gotcha:** Spring's cron has *six* fields (seconds first), unlike Unix cron. `0 0 2 * * *` is 2:00 AM daily. Always set `zone` explicitly — otherwise you get the server's default TZ, and "2am" quietly becomes something else when ops moves you to a UTC container. Also note DST: in most zones 2am is fine, but if your zone skips 2:00–3:00 on spring-forward night, Spring runs the job at the adjusted time; consider 2:30 or 3:00 if that matters to you.

- **Idempotency via `fineAccruedThrough`:** instead of blindly adding 30 cents per run, the service charges the gap between the last accrual date and today. So if the job runs twice in one night, the second run charges 0. If the app was down for three nights, the next run charges 90 cents and catches up. This also means the day count comes from the calendar, not from "number of times the job happened to run" — much more defensible when a patron disputes a fine.

- **Money as `long` cents:** never `double` for currency; `0.30` isn't representable in binary floating point and the errors compound over thousands of loans.

- **Per-loan transactions:** `@Scheduled` methods should be thin. The scheduler fetches IDs, then calls the `@Transactional` service method per loan (the call crosses a bean boundary, so the proxy actually applies the transaction — a `@Transactional` on a private/self-called method would be silently ignored). One poisoned row logs and moves on instead of rolling back the entire night's batch. If you have hundreds of thousands of loans, switch the ID fetch to pagination/streaming.

**One important caveat: multiple instances.** `@Scheduled` fires on *every* node. The `fineAccruedThrough` guard makes double-charging unlikely (and the `@Version` column catches the race), but the clean fix if you run more than one instance is [ShedLock](https://github.com/lukas-krecan/ShedLock):

```java
@Scheduled(cron = "0 0 2 * * *", zone = "America/New_York")
@SchedulerLock(name = "chargeOverdueFines", lockAtMostFor = "PT30M")
public void chargeOverdueFines() { ... }
```

**Testing tip:** because `Clock` is injected, a test can do `Clock.fixed(...)`, create a loan due 5 days ago, call `chargeOverdueFines()` directly (no need to wait for 2am), and assert `fineCents == 150`. Then call it a second time and assert it's *still* 150 — that's your idempotency test, and it's the one that matters most here.

One product question worth confirming with whoever owns the requirements: should fines cap at some maximum (e.g., the item's replacement cost)? Most library systems do, and it's a one-line guard in `accrueFine` if so.
