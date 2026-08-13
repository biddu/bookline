# Transcript: fixing `runningAccrualTwiceForTheSameDayChargesOnce`

Repository: `/home/claude/book/ch07_exp3/repo`
Task: make the failing test `FineAccrualIdempotencyTest.runningAccrualTwiceForTheSameDayChargesOnce` pass.

---

## 1. Orientation

First command run, to see what the repository contains:

```
find /home/claude/book/ch07_exp3/repo -type f -name "*.java" -o -name "pom.xml"
```

Result — the repository is tiny, three source files plus the POM:

```
/home/claude/book/ch07_exp3/repo/src/test/java/ie/ardaralibraries/bookline/billing/FineAccrualIdempotencyTest.java
/home/claude/book/ch07_exp3/repo/src/main/java/ie/ardaralibraries/bookline/circulation/Core.java
/home/claude/book/ch07_exp3/repo/src/main/java/ie/ardaralibraries/bookline/billing/Billing.java
/home/claude/book/ch07_exp3/repo/pom.xml
```

Then:

```
ls -la && wc -l <the four files above>
```

Result:

```
total 24
-rw-r--r-- 1 root root  107 Aug 10 13:19 mutation_results.txt
-rw-r--r-- 1 root root 1641 Aug 10 13:19 pom.xml
drwxr-xr-x 4 root root 4096 Aug 10 13:19 src
drwxr-xr-x 9 root root 4096 Aug 10 13:19 target

   85 src/test/java/ie/ardaralibraries/bookline/billing/FineAccrualIdempotencyTest.java
  207 src/main/java/ie/ardaralibraries/bookline/circulation/Core.java
   79 src/main/java/ie/ardaralibraries/bookline/billing/Billing.java
   29 pom.xml
  400 total
```

## 2. Files read

In order:

1. `/home/claude/book/ch07_exp3/repo/src/test/java/ie/ardaralibraries/bookline/billing/FineAccrualIdempotencyTest.java` (the failing test)
2. `/home/claude/book/ch07_exp3/repo/src/main/java/ie/ardaralibraries/bookline/billing/Billing.java` (the production code under test)
3. `/home/claude/book/ch07_exp3/repo/src/main/java/ie/ardaralibraries/bookline/circulation/Core.java` (read for context; turned out to be unrelated to the failure — circulation/loans/holds, no fine accrual)
4. `/home/claude/book/ch07_exp3/repo/mutation_results.txt` and `/home/claude/book/ch07_exp3/repo/pom.xml`

`mutation_results.txt` contains no results — just a leftover error line from a previous experiment:

```
python3: can't open file '/home/claude/book/ch07_exp2/proj/mutate.py': [Errno 2] No such file or directory
```

`pom.xml`: JUnit 5.10.2, Mockito 5.11.0, Java 21, surefire 3.2.5, JaCoCo 0.8.11. Nothing relevant to the failure.

## 3. What the test asserts

`FineAccrualIdempotencyTest` documents invariant INV-5 and specifically its re-run clause: *"A fine accrues once per overdue day, never twice for the same day."* The scenario models the 03:40 retry of a failed nightly accrual run.

Setup (test lines 55–71):

- `InMemoryAccrualRepository` — a list-backed store with **no unique key**, deliberately mirroring the production table ("no unique key, exactly like the production table today"). So the database will not deduplicate for us; the service must.
- Calendar is `day -> true` (every branch open), so the closed-day path is not involved.
- The loan is due `FEB_3.minusDays(3)` = 2026-01-31, not returned, rate 0.30, replacement cost 25.00 — nowhere near the cap.
- `nightOf(FEB_3)` = 2026-02-04T00:00 Europe/Dublin.

The test calls `accrueUpTo(...)` twice with the identical instant and asserts exactly one accrual row exists for 2026-02-03.

## 4. Conclusion reached before editing

`FineAccrualService.accrueUpTo` in `Billing.java` walks the overdue days and, for every open day, unconditionally constructs a `FineAccrual` and calls `accrualRepository.save(...)`. It never asks whether that day has already been charged.

The decisive evidence that this is a production defect and not a test defect:

- `FineAccrualRepository` declares `boolean existsForLoanAndDate(String loanId, LocalDate date)` (Billing.java line 14). Nothing in `Billing.java` ever calls it. The interface exposes exactly the idempotency query the service forgot to use — a dangling capability.
- The test's in-memory repository faithfully implements `existsForLoanAndDate`, so the test is written expecting the service to consult it.
- The comment on the fake repository states the production table has no unique key, so nothing else in the system would catch the duplicate.
- Walking the loop by hand: `day` starts at 2026-02-01 and runs while `day.isBefore(2026-02-04)`, i.e. Feb 1, Feb 2, Feb 3. Each of the two runs writes all three days, so Feb 3 ends with two rows. The `2` in the failure message is exactly what that predicts.

So: real bug, fix the production code, leave the test alone. The correct fix is to skip any day already present in the accrual store. The cap arithmetic (`accrued` seeded from `Fines.totalFor`) already accounts for previously written rows, so skipping a day does not distort the replacement-cost ceiling.

## 5. Baseline test run (before any change)

Command:

```
cd /home/claude/book/ch07_exp3/repo && mvn -o test
```

Result (trimmed to the relevant portion):

```
[INFO] Running ie.ardaralibraries.bookline.billing.FineAccrualIdempotencyTest
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.132 s <<< FAILURE! -- in ie.ardaralibraries.bookline.billing.FineAccrualIdempotencyTest
[ERROR] ie.ardaralibraries.bookline.billing.FineAccrualIdempotencyTest.runningAccrualTwiceForTheSameDayChargesOnce -- Time elapsed: 0.059 s <<< FAILURE!
org.opentest4j.AssertionFailedError: INV-5: a fine accrues once per overdue day, never twice for the same day ==> expected: <1> but was: <2>
	at ie.ardaralibraries.bookline.billing.FineAccrualIdempotencyTest.runningAccrualTwiceForTheSameDayChargesOnce(FineAccrualIdempotencyTest.java:82)

[INFO] Results:
[ERROR] Failures:
[ERROR]   FineAccrualIdempotencyTest.runningAccrualTwiceForTheSameDayChargesOnce:82 INV-5: a fine accrues once per overdue day, never twice for the same day ==> expected: <1> but was: <2>
[ERROR] Tests run: 1, Failures: 0 -> Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[INFO] Total time:  3.489 s
```

`expected: <1> but was: <2>` — one duplicate row for Feb 3, exactly as predicted.

## 6. The change

One edit, to production code only, in
`/home/claude/book/ch07_exp3/repo/src/main/java/ie/ardaralibraries/bookline/billing/Billing.java`,
inside `FineAccrualService.accrueUpTo`.

Diff (unified, verbatim, generated by `diff -u` against a copy of the pre-edit file):

```diff
--- a/src/main/java/ie/ardaralibraries/bookline/billing/Billing.java
+++ b/src/main/java/ie/ardaralibraries/bookline/billing/Billing.java
@@ -58,6 +58,9 @@
             if (!calendar.isOpenOn(day)) {
                 continue;
             }
+            if (accrualRepository.existsForLoanAndDate(facts.loanId(), day)) {
+                continue;   // already charged for this day: a re-run must not charge twice
+            }
             BigDecimal amount = calculator.dailyIncrement(facts, accrued);
             if (amount.signum() <= 0) {
                 break;
```

That is the complete set of edits. No other file was touched. `FineAccrualIdempotencyTest.java` was **not** modified. `Core.java` and `pom.xml` were **not** modified.

Placement notes:

- The guard sits after the closed-day check and before the amount calculation, so an already-charged day costs one existence query and nothing else.
- `continue`, not `break`: a gap in previously written days (e.g. a partially completed run that died mid-loop) must still be filled in on the retry.
- The cap check (`amount.signum() <= 0 -> break`) is untouched, so the "never exceeds the item's replacement cost" clause of INV-5 still holds; `accrued` is seeded from the already-persisted rows via `Fines.totalFor`, so skipped days are already reflected in the running total.

For reference, the method after the edit reads:

```java
    List<FineAccrual> accrueUpTo(OverdueLoanFacts facts, Instant now) {
        LocalDate today = now.atZone(LIBRARY_ZONE).toLocalDate();
        LocalDate end = facts.returnedDate().orElse(today);
        List<FineAccrual> written = new ArrayList<>();
        BigDecimal accrued = Fines.totalFor(accrualRepository, facts.loanId());
        for (LocalDate day = facts.dueDate().plusDays(1); day.isBefore(end); day = day.plusDays(1)) {
            if (!calendar.isOpenOn(day)) {
                continue;
            }
            if (accrualRepository.existsForLoanAndDate(facts.loanId(), day)) {
                continue;   // already charged for this day: a re-run must not charge twice
            }
            BigDecimal amount = calculator.dailyIncrement(facts, accrued);
            if (amount.signum() <= 0) {
                break;
            }
            FineAccrual accrual = new FineAccrual(facts.loanId(), day, amount);
            written.add(accrualRepository.save(accrual));
            accrued = accrued.add(amount);
        }
        return written;
    }
```

## 7. Verification run (after the change)

Command:

```
cd /home/claude/book/ch07_exp3/repo && mvn -o test
```

Result (filtered to test and build lines):

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.127 s -- in ie.ardaralibraries.bookline.billing.FineAccrualIdempotencyTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The suite contains exactly one test class and one test; it is green.

## 8. Diff-generation command (for completeness)

The unified diff above was produced by reconstructing the pre-edit file and diffing:

```
cd /tmp && cp /home/claude/book/ch07_exp3/repo/src/main/java/ie/ardaralibraries/bookline/billing/Billing.java /tmp/Billing_new.java \
  && sed '/existsForLoanAndDate(facts.loanId(), day)/,+2d' /tmp/Billing_new.java > /tmp/Billing_orig.java \
  && diff -u /tmp/Billing_orig.java /tmp/Billing_new.java
```

Output:

```
--- /tmp/Billing_orig.java	2026-08-10 13:20:46.445249750 +0000
+++ /tmp/Billing_new.java	2026-08-10 13:20:46.442780633 +0000
@@ -58,6 +58,9 @@
             if (!calendar.isOpenOn(day)) {
                 continue;
             }
+            if (accrualRepository.existsForLoanAndDate(facts.loanId(), day)) {
+                continue;   // already charged for this day: a re-run must not charge twice
+            }
             BigDecimal amount = calculator.dailyIncrement(facts, accrued);
             if (amount.signum() <= 0) {
                 break;
```

(The repository is not a git working tree — `git` was unavailable as a diff source, hence the reconstruction above.)

## 9. Note on residual risk

The fix makes the retry safe against sequential re-runs, which is what INV-5's re-run clause and this test require. It does not make accrual safe against two runs executing *concurrently*: check-then-save is not atomic, so two overlapping nightly jobs could both pass the existence check and both insert. The durable remedy is the unique key the test comment says the production table lacks — a unique constraint on `(loan_id, accrual_date)`. That is a schema change outside the scope of this task, and it is not what the failing test exercises, so I did not attempt it here.
