Happy to. Before the code, two things worth saying out loud, because they shape everything below.

**First, what's wrong with this class in SOLID terms.** `LibraryManager` has at least nine reasons to change: circulation rules, hold lifecycle, membership, fines, notices, reporting, stock, catalogue search, and branch opening hours — plus it owns its own JDBC plumbing, SMTP delivery, audit logging, barcode scheme, and an availability cache. That's the SRP violation. It also constructs its own `Connection` and SMTP session inline (DIP violation), dispatches on `instanceof Periodical` for due dates and fine rates (an OCP problem — adding a DVD type means editing checkout, fines, and receipts), and exposes one enormous implicit interface to every caller (ISP).

**Second, and more important: this class has no tests, and it is full of load-bearing inconsistencies.** The comment at the top of the file says as much ("the live rates are still written inline below"). A refactoring must not silently "fix" any of these, because members are currently receiving these exact numbers and messages. So my ground rule is: **behaviour-preserving, bug-for-bug**, with every quirk moved somewhere visible and commented. Here is the quirk inventory I found and deliberately preserved:

1. **The loan limit is three different numbers.** `checkoutBook` refuses only when the member *already has more than 10* open loans — so a member with exactly 10 gets an 11th. `renewLoan` refuses at `>= 10`. `getMemberSummary` tells members the limit is 10. All three preserved as-is.
2. **Fines quoted to members disagree with fines charged.** `calculateFine` (the amount actually charged on return) is EUR 0.25/day for books, capped at 60 days, and EUR 0.50/day *uncapped* for periodicals. But `sendOverdueNotices` and both reports use a flat, uncapped EUR 0.25/day for everything. Preserved, with warning comments at both sites.
3. **The checkout receipt contradicts itself for periodicals** — it quotes the correct 0.50 rate in one sentence and then says "Fines accrue at EUR 0.25 per open day" in the next. Preserved verbatim.
4. **Renewals add 21 days even to 7-day periodical loans.** Preserved.
5. **"Reference" is detected via copy *status*, not copy *type*** — and checkout *throws* `RuntimeException` for reference stock while transfer politely returns a message. Both preserved.
6. **`expireHolds` never offers the copy to the next queued hold** (the original comment acknowledges this). Preserved.
7. **The branch stats "Holds queued" count joins on `hold.copy_id`, which is only populated when a hold becomes READY** — so for QUEUED holds it almost certainly always reports 0. Preserved (worth a ticket).
8. **"Active members (30 days)" in the *branch* stats report is not filtered by branch.** Preserved.
9. **`markCopyLost` emails the member that "the replacement cost will be added to your account", but no code ever adds it.** Preserved.
10. **The CSV export does no comma escaping** (the original NB comment is kept), and the overdue *report* is ordered by due date while the CSV export is unordered. Both preserved.
11. **`generateBarcode` ignores its `branchCode` parameter, and `validateBarcode` never checks that position 8 is actually a hyphen.** Both preserved.
12. **The availability cache has no TTL** ("restart the app if the numbers drift"). Preserved, but the cache is now an injectable object so tests and ops tooling can clear it.
13. Miscellaneous preserved behaviours: `updateMemberEmail` returns "OK" even for a nonexistent member; `placeHold` doesn't check member status or that the ISBN exists; `waiveFine` records a waiver even when there's no fine account; suspension cancels the member's active holds; audit logging is best-effort and swallows failures.

**The one class of behaviour I did deliberately change:** every string-concatenated SQL statement is now a `PreparedStatement`. This closes the SQL injection hole and, observably, means names and titles containing apostrophes (O'Brien, *The Player's Handbook*) now work instead of throwing `SQLException` (or worse). I consider that a bug fix you'd want, but it *is* a behaviour change — flag it in your release notes. Secondarily: connections are now opened per operation and closed with try-with-resources (the original leaked a connection per call and stored it in a shared mutable field), and the availability cache is a `ConcurrentHashMap` instead of a bare `HashMap`. Neither changes any message, rate, limit, or ordering.

## The shape of the refactoring

The 912 lines are genuinely restructured — logic moves out into focused classes — but the public API survives: `LibraryManager` remains as a thin facade with every original method signature (including `main`), delegating to the new services, so existing callers compile unchanged.

```
bookline/
  infra/         ConnectionProvider, DriverManagerConnectionProvider,
                 EmailSender, SmtpEmailSender, AuditLog, DatabaseAuditLog
  domain/        CopyTypePolicy, BookPolicy, PeriodicalPolicy, CopyTypePolicies,
                 BarcodeScheme, AvailabilityCache
  repository/    MemberRepository, LoanRepository, HoldRepository,
                 CopyRepository, FineAccountRepository, BranchRepository
  service/       CirculationService, HoldService, MembershipService,
                 FineService, NoticeService, ReportService,
                 StockService, CatalogueService, BranchService
  LibraryManager (facade + composition root, unchanged public API)
```

How this maps to SOLID:

- **SRP** — nine services, each with one reason to change; six repositories isolating persistence; infrastructure (SMTP, JDBC, audit) split out entirely.
- **OCP** — due-date periods and fine rules live behind `CopyTypePolicy`. Adding a DVD type is one new policy class plus one registry entry; no service changes.
- **LSP** — the `instanceof Periodical` dispatch is gone; behaviour varies by policy lookup on `copy_type`, so the `Copy`/`Periodical` hierarchy is no longer used for control flow (the classes remain for any other callers).
- **ISP** — `EmailSender`, `AuditLog`, and `ConnectionProvider` are one-to-three-method interfaces; each service depends only on the repositories it actually uses.
- **DIP** — no service touches `DriverManager` or `Transport`. Everything is injected; `java.time.Clock` is injected too, so fines and opening hours are finally testable.

Package and import statements are omitted below for brevity; each class goes in its own file.

## Infrastructure

```java
public interface ConnectionProvider {
    Connection open() throws SQLException;
}
```

```java
public final class DriverManagerConnectionProvider implements ConnectionProvider {
    private final String url;
    private final String user;
    private final String password;

    public DriverManagerConnectionProvider(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
```

```java
public interface EmailSender {
    void send(String to, String subject, String body) throws Exception;
}
```

```java
public final class SmtpEmailSender implements EmailSender {
    private final String host;
    private final int port;
    private final String fromAddress;

    public SmtpEmailSender(String host, int port, String fromAddress) {
        this.host = host;
        this.port = port;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String to, String subject, String body) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        Session session = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromAddress));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSubject(subject);
        msg.setText(body);
        msg.setHeader("X-Mailer", "Bookline");
        msg.setSentDate(new java.util.Date());
        Transport.send(msg);
    }
}
```

```java
public interface AuditLog {
    /** Best-effort; implementations must never throw. */
    void record(String action, String detail);
}
```

```java
public final class DatabaseAuditLog implements AuditLog {
    private final ConnectionProvider db;

    public DatabaseAuditLog(ConnectionProvider db) {
        this.db = db;
    }

    @Override
    public void record(String action, String detail) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO audit_log (action, detail, logged_at) VALUES (?, ?, NOW())")) {
            ps.setString(1, action);
            ps.setString(2, detail);
            ps.executeUpdate();
        } catch (Exception e) {
            // Preserved: audit must never break the operation.
            System.err.println("audit failed: " + e.getMessage());
        }
    }
}
```

## Domain

```java
/**
 * Per-copy-type circulation and fine rules. Adding a new copy type
 * (DVD, audiobook, ...) means adding a policy class and registering it —
 * no service code changes (OCP).
 */
public interface CopyTypePolicy {
    int loanPeriodDays();
    /** The daily rate quoted on the checkout receipt. */
    double dailyRate();
    double fineFor(long daysOverdue);
}
```

```java
public final class BookPolicy implements CopyTypePolicy {
    @Override public int loanPeriodDays() { return 21; }
    @Override public double dailyRate() { return 0.25; }

    @Override
    public double fineFor(long daysOverdue) {
        if (daysOverdue > 60) {
            return 60 * 0.25;   // capped after 60 days (preserved)
        }
        return daysOverdue * 0.25;
    }
}
```

```java
public final class PeriodicalPolicy implements CopyTypePolicy {
    @Override public int loanPeriodDays() { return 7; }
    @Override public double dailyRate() { return 0.50; }

    @Override
    public double fineFor(long daysOverdue) {
        // Preserved from the original: periodical fines have no 60-day cap.
        return daysOverdue * 0.50;
    }
}
```

```java
public final class CopyTypePolicies {
    private final Map<String, CopyTypePolicy> byType = new HashMap<>();
    private final CopyTypePolicy fallback = new BookPolicy();

    public CopyTypePolicies() {
        byType.put("PERIODICAL", new PeriodicalPolicy());
    }

    /** Preserved: the original treated every non-periodical type as a book. */
    public CopyTypePolicy forType(String copyType) {
        return byType.getOrDefault(copyType, fallback);
    }
}
```

```java
public final class BarcodeScheme {

    /**
     * Validates the Ardara barcode format. Preserved exactly, including
     * the fact that position 8 (documented as the hyphen) is never checked.
     */
    public boolean validate(String barcode) {
        if (barcode == null || barcode.length() != 10) {
            return false;
        }
        for (int i = 0; i < 8; i++) {
            if (!Character.isDigit(barcode.charAt(i))) {
                return false;
            }
        }
        int check = Character.getNumericValue(barcode.charAt(9));
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += Character.getNumericValue(barcode.charAt(i)) * (i + 1);
        }
        return check == sum % 10;
    }

    /** Preserved: branchCode is accepted but not used by the original scheme. */
    public String generate(String branchCode) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append((int) (Math.random() * 10));
        }
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += Character.getNumericValue(sb.charAt(i)) * (i + 1);
        }
        sb.append("-").append(sum % 10);
        return sb.toString();
    }
}
```

```java
/**
 * Per-ISBN available-copy counts. Preserved: no TTL — entries live until
 * explicitly invalidated (see ops runbook re: restarting on drift).
 */
public final class AvailabilityCache {
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();

    public Integer get(String isbn) { return cache.get(isbn); }
    public void put(String isbn, int available) { cache.put(isbn, available); }
    public void invalidate(String isbn) { cache.remove(isbn); }
    public void clear() { cache.clear(); }
}
```

## Repositories

```java
public final class MemberRepository {
    private final ConnectionProvider db;

    public MemberRepository(ConnectionProvider db) { this.db = db; }

    public record MemberRow(String name, String tier) {}

    public Optional<MemberRow> findById(String memberId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT name, tier FROM member WHERE id = ?")) {
            ps.setString(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new MemberRow(rs.getString("name"), rs.getString("tier")));
            }
        }
    }

    public Optional<String> statusOf(String memberId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT status FROM member WHERE id = ?")) {
            ps.setString(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("status")) : Optional.empty();
            }
        }
    }

    /** Preserved contract: throws if the member does not exist. */
    public String emailOf(String memberId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT email FROM member WHERE id = ?")) {
            ps.setString(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new RuntimeException("No member with id " + memberId);
                }
                return rs.getString("email");
            }
        }
    }

    public boolean emailExists(String email) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM member WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    public void insert(String id, String name, String email, String tier) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO member (id, name, email, tier, status, joined_at)"
                 + " VALUES (?, ?, ?, ?, 'ACTIVE', NOW())")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, email);
            ps.setString(4, tier);
            ps.executeUpdate();
        }
    }

    public void updateEmail(String memberId, String newEmail) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE member SET email = ? WHERE id = ?")) {
            ps.setString(1, newEmail);
            ps.setString(2, memberId);
            ps.executeUpdate();
        }
    }

    public void updateStatus(String memberId, String status) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE member SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, memberId);
            ps.executeUpdate();
        }
    }
}
```

```java
public final class LoanRepository {
    private final ConnectionProvider db;

    public LoanRepository(ConnectionProvider db) { this.db = db; }

    public record OpenLoan(String id, String memberId, LocalDate dueAt, int renewalCount) {}
    public record FineBasis(LocalDate dueAt, String copyType) {}
    public record OverdueLoan(String memberId, LocalDate dueAt) {}
    public record BranchOverdueLoan(String memberName, String memberEmail,
                                    String title, LocalDate dueAt) {}
    public record MemberLoanLine(String title, LocalDate dueAt) {}

    public int countOpen(String memberId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM loan WHERE member_id = ? AND returned_at IS NULL")) {
            ps.setString(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public Optional<OpenLoan> findOpenByCopyId(String copyId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, member_id, due_at, renewal_count FROM loan"
                 + " WHERE copy_id = ? AND returned_at IS NULL")) {
            ps.setString(1, copyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new OpenLoan(rs.getString("id"), rs.getString("member_id"),
                    rs.getDate("due_at").toLocalDate(), rs.getInt("renewal_count")));
            }
        }
    }

    public Optional<OpenLoan> findOpenById(String loanId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, member_id, due_at, renewal_count FROM loan"
                 + " WHERE id = ? AND returned_at IS NULL")) {
            ps.setString(1, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new OpenLoan(rs.getString("id"), rs.getString("member_id"),
                    rs.getDate("due_at").toLocalDate(), rs.getInt("renewal_count")));
            }
        }
    }

    public boolean copyOnLoan(String copyId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM loan WHERE copy_id = ? AND returned_at IS NULL")) {
            ps.setString(1, copyId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    public void insert(String copyId, String memberId, LocalDate dueAt) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO loan (copy_id, member_id, due_at) VALUES (?, ?, ?)")) {
            ps.setString(1, copyId);
            ps.setString(2, memberId);
            ps.setDate(3, java.sql.Date.valueOf(dueAt));
            ps.executeUpdate();
        }
    }

    public void markReturned(String loanId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE loan SET returned_at = NOW() WHERE id = ?")) {
            ps.setString(1, loanId);
            ps.executeUpdate();
        }
    }

    public void renew(String loanId, LocalDate newDue, int newRenewalCount) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE loan SET due_at = ?, renewal_count = ? WHERE id = ?")) {
            ps.setDate(1, java.sql.Date.valueOf(newDue));
            ps.setInt(2, newRenewalCount);
            ps.setString(3, loanId);
            ps.executeUpdate();
        }
    }

    /** Note: deliberately does not filter on returned_at, matching the original. */
    public Optional<FineBasis> fineBasis(String loanId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT l.due_at, c.copy_type FROM loan l JOIN copy c"
                 + " ON l.copy_id = c.id WHERE l.id = ?")) {
            ps.setString(1, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new FineBasis(
                    rs.getDate("due_at").toLocalDate(), rs.getString("copy_type")));
            }
        }
    }

    public List<MemberLoanLine> openLoansFor(String memberId) throws SQLException {
        List<MemberLoanLine> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT c.title, l.due_at FROM loan l JOIN copy c ON l.copy_id = c.id"
                 + " WHERE l.member_id = ? AND l.returned_at IS NULL ORDER BY l.due_at")) {
            ps.setString(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MemberLoanLine(rs.getString("title"),
                        rs.getDate("due_at").toLocalDate()));
                }
            }
        }
        return out;
    }

    public List<OverdueLoan> findAllOverdue() throws SQLException {
        List<OverdueLoan> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT l.member_id, l.due_at FROM loan l"
                 + " WHERE l.returned_at IS NULL AND l.due_at < NOW()");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new OverdueLoan(rs.getString("member_id"),
                    rs.getDate("due_at").toLocalDate()));
            }
        }
        return out;
    }

    /** Ordered by due date — used by the printed report (preserved ordering). */
    public List<BranchOverdueLoan> findOverdueForBranchOrdered(String branchCode)
            throws SQLException {
        return overdueForBranch(branchCode, true);
    }

    /** Unordered — used by the CSV export (the original had no ORDER BY there). */
    public List<BranchOverdueLoan> findOverdueForBranchUnordered(String branchCode)
            throws SQLException {
        return overdueForBranch(branchCode, false);
    }

    private List<BranchOverdueLoan> overdueForBranch(String branchCode, boolean ordered)
            throws SQLException {
        String sql = "SELECT m.name, m.email, c.title, l.due_at FROM loan l"
            + " JOIN member m ON l.member_id = m.id"
            + " JOIN copy c ON l.copy_id = c.id"
            + " WHERE l.returned_at IS NULL AND l.due_at < NOW()"
            + " AND c.branch_code = ?" + (ordered ? " ORDER BY l.due_at" : "");
        List<BranchOverdueLoan> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, branchCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BranchOverdueLoan(rs.getString("name"), rs.getString("email"),
                        rs.getString("title"), rs.getDate("due_at").toLocalDate()));
                }
            }
        }
        return out;
    }

    public int countCheckoutsLast30Days(String branchCode) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM loan l JOIN copy c ON l.copy_id = c.id"
                 + " WHERE c.branch_code = ?"
                 + " AND l.checked_out_at > NOW() - INTERVAL '30 days'")) {
            ps.setString(1, branchCode);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Preserved: system-wide, NOT branch-scoped, despite living in the branch report. */
    public int countDistinctActiveMembersLast30Days() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(DISTINCT member_id) FROM loan"
                 + " WHERE checked_out_at > NOW() - INTERVAL '30 days'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public int countOverdueForBranch(String branchCode) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM loan l JOIN copy c ON l.copy_id = c.id"
                 + " WHERE c.branch_code = ? AND l.returned_at IS NULL"
                 + " AND l.due_at < NOW()")) {
            ps.setString(1, branchCode);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
```

```java
public final class HoldRepository {
    private final ConnectionProvider db;

    public HoldRepository(ConnectionProvider db) { this.db = db; }

    public record QueuedHold(String id, String memberId) {}
    public record ReadyHold(String id, String memberId, String title) {}

    public boolean hasActiveHold(String memberId, String isbn) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM hold WHERE member_id = ? AND isbn = ?"
                 + " AND status IN ('QUEUED','READY')")) {
            ps.setString(1, memberId);
            ps.setString(2, isbn);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    public void insert(String holdId, String memberId, String isbn) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO hold (id, member_id, isbn, status, placed_at)"
                 + " VALUES (?, ?, ?, 'QUEUED', NOW())")) {
            ps.setString(1, holdId);
            ps.setString(2, memberId);
            ps.setString(3, isbn);
            ps.executeUpdate();
        }
    }

    /** Returns the number of rows cancelled (0 = not found or already fulfilled). */
    public int cancel(String holdId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE hold SET status = 'CANCELLED' WHERE id = ?"
                 + " AND status IN ('QUEUED','READY')")) {
            ps.setString(1, holdId);
            return ps.executeUpdate();
        }
    }

    public void cancelAllActiveFor(String memberId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE hold SET status = 'CANCELLED' WHERE member_id = ?"
                 + " AND status IN ('QUEUED','READY')")) {
            ps.setString(1, memberId);
            ps.executeUpdate();
        }
    }

    public Optional<String> readyHoldMemberForCopy(String copyId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT member_id FROM hold WHERE copy_id = ? AND status = 'READY'")) {
            ps.setString(1, copyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("member_id")) : Optional.empty();
            }
        }
    }

    public Optional<QueuedHold> oldestQueuedFor(String isbn) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, member_id FROM hold WHERE isbn = ? AND status = 'QUEUED'"
                 + " ORDER BY placed_at LIMIT 1")) {
            ps.setString(1, isbn);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new QueuedHold(rs.getString("id"), rs.getString("member_id")));
            }
        }
    }

    public void markReady(String holdId, String copyId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE hold SET status = 'READY', copy_id = ? WHERE id = ?")) {
            ps.setString(1, copyId);
            ps.setString(2, holdId);
            ps.executeUpdate();
        }
    }

    public List<ReadyHold> findReadyUnnotified() throws SQLException {
        List<ReadyHold> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT h.id, h.member_id, c.title FROM hold h JOIN copy c"
                 + " ON h.copy_id = c.id WHERE h.status = 'READY'"
                 + " AND h.notified_at IS NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new ReadyHold(rs.getString("id"), rs.getString("member_id"),
                    rs.getString("title")));
            }
        }
        return out;
    }

    public void markNotified(String holdId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE hold SET notified_at = NOW() WHERE id = ?")) {
            ps.setString(1, holdId);
            ps.executeUpdate();
        }
    }

    public List<QueuedHold> findReadyOverdueForCollection() throws SQLException {
        List<QueuedHold> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, member_id FROM hold WHERE status = 'READY'"
                 + " AND notified_at < NOW() - INTERVAL '7 days'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new QueuedHold(rs.getString("id"), rs.getString("member_id")));
            }
        }
        return out;
    }

    public void markExpired(String holdId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE hold SET status = 'EXPIRED' WHERE id = ?")) {
            ps.setString(1, holdId);
            ps.executeUpdate();
        }
    }

    /**
     * Preserved as-is, including the original's join on hold.copy_id, which is
     * only set when a hold becomes READY — so this almost certainly always
     * returns 0 for QUEUED holds. Worth a ticket, but not silently fixed here.
     */
    public int countQueuedForBranch(String branchCode) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM hold h JOIN copy c ON h.copy_id = c.id"
                 + " WHERE c.branch_code = ? AND h.status = 'QUEUED'")) {
            ps.setString(1, branchCode);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
```

```java
public final class CopyRepository {
    private final ConnectionProvider db;

    public CopyRepository(ConnectionProvider db) { this.db = db; }

    public record CopyRow(String id, String isbn, String title, String copyType, String status) {}
    public record SearchHit(String title, String isbn) {}

    /** Preserved contract: throws if no copy has this barcode. */
    public CopyRow findByBarcode(String barcode) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, isbn, title, copy_type, status FROM copy WHERE barcode = ?")) {
            ps.setString(1, barcode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new RuntimeException("No copy with barcode " + barcode);
                }
                return new CopyRow(rs.getString("id"), rs.getString("isbn"),
                    rs.getString("title"), rs.getString("copy_type"), rs.getString("status"));
            }
        }
    }

    public void insert(String copyId, String isbn, String title, String copyType,
            String branchCode, String barcode) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO copy (id, isbn, title, copy_type, branch_code, barcode, status)"
                 + " VALUES (?, ?, ?, ?, ?, ?, 'AVAILABLE')")) {
            ps.setString(1, copyId);
            ps.setString(2, isbn);
            ps.setString(3, title);
            ps.setString(4, copyType);
            ps.setString(5, branchCode);
            ps.setString(6, barcode);
            ps.executeUpdate();
        }
    }

    public void updateStatus(String copyId, String status) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE copy SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, copyId);
            ps.executeUpdate();
        }
    }

    public void updateBranch(String copyId, String branchCode) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE copy SET branch_code = ? WHERE id = ?")) {
            ps.setString(1, branchCode);
            ps.setString(2, copyId);
            ps.executeUpdate();
        }
    }

    public int countAvailable(String isbn) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM copy c WHERE c.isbn = ?"
                 + " AND c.status = 'AVAILABLE' AND NOT EXISTS"
                 + " (SELECT 1 FROM loan l WHERE l.copy_id = c.id"
                 + " AND l.returned_at IS NULL)")) {
            ps.setString(1, isbn);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Preserved semantics: % and _ in the query still act as wildcards inside
     * the LIKE pattern, exactly as before. Quote handling is now done by the
     * driver instead of the old escapeSql helper.
     */
    public List<SearchHit> searchAvailableByTitle(String query) throws SQLException {
        List<SearchHit> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT DISTINCT title, isbn FROM copy WHERE LOWER(title)"
                 + " LIKE LOWER(?) AND status = 'AVAILABLE'"
                 + " ORDER BY title LIMIT 50")) {
            ps.setString(1, "%" + (query == null ? "" : query) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SearchHit(rs.getString("title"), rs.getString("isbn")));
                }
            }
        }
        return out;
    }

    public int countLost(String branchCode) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM copy WHERE branch_code = ? AND status = 'LOST'")) {
            ps.setString(1, branchCode);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
```

```java
public final class FineAccountRepository {
    private final ConnectionProvider db;

    public FineAccountRepository(ConnectionProvider db) { this.db = db; }

    public void ensureExists(String memberId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement check = c.prepareStatement(
                 "SELECT COUNT(*) FROM fine_account WHERE member_id = ?")) {
            check.setString(1, memberId);
            try (ResultSet rs = check.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) return;
            }
            try (PreparedStatement ins = c.prepareStatement(
                     "INSERT INTO fine_account (member_id, balance) VALUES (?, 0)")) {
                ins.setString(1, memberId);
                ins.executeUpdate();
            }
        }
    }

    public OptionalDouble balanceOf(String memberId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT balance FROM fine_account WHERE member_id = ?")) {
            ps.setString(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? OptionalDouble.of(rs.getDouble("balance"))
                                 : OptionalDouble.empty();
            }
        }
    }

    public void setBalance(String memberId, double balance) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE fine_account SET balance = ? WHERE member_id = ?")) {
            ps.setDouble(1, balance);
            ps.setString(2, memberId);
            ps.executeUpdate();
        }
    }

    public void clearBalance(String memberId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE fine_account SET balance = 0 WHERE member_id = ?")) {
            ps.setString(1, memberId);
            ps.executeUpdate();
        }
    }

    public void recordWaiver(String memberId, String reason) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO fine_waiver (member_id, reason, waived_at)"
                 + " VALUES (?, ?, NOW())")) {
            ps.setString(1, memberId);
            ps.setString(2, reason);
            ps.executeUpdate();
        }
    }
}
```

```java
public final class BranchRepository {
    private final ConnectionProvider db;

    public BranchRepository(ConnectionProvider db) { this.db = db; }

    public record Hours(LocalTime opensAt, LocalTime closesAt) {}

    public Optional<Hours> hoursFor(String branchCode, DayOfWeek day) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT opens_at, closes_at FROM branch_hours"
                 + " WHERE branch_code = ? AND day_of_week = ?")) {
            ps.setString(1, branchCode);
            ps.setString(2, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Hours(rs.getTime("opens_at").toLocalTime(),
                    rs.getTime("closes_at").toLocalTime()));
            }
        }
    }

    public boolean isHoliday(String branchCode, LocalDate date) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM branch_holiday WHERE branch_code = ?"
                 + " AND holiday_date = ?")) {
            ps.setString(1, branchCode);
            ps.setDate(2, java.sql.Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
```

## Services

```java
public final class CirculationService {
    private final MemberRepository members;
    private final LoanRepository loans;
    private final CopyRepository copies;
    private final HoldRepository holds;
    private final FineService fines;
    private final CopyTypePolicies policies;
    private final EmailSender email;
    private final AuditLog audit;
    private final AvailabilityCache cache;
    private final Clock clock;

    public CirculationService(MemberRepository members, LoanRepository loans,
            CopyRepository copies, HoldRepository holds, FineService fines,
            CopyTypePolicies policies, EmailSender email, AuditLog audit,
            AvailabilityCache cache, Clock clock) {
        this.members = members;
        this.loans = loans;
        this.copies = copies;
        this.holds = holds;
        this.fines = fines;
        this.policies = policies;
        this.email = email;
        this.audit = audit;
        this.cache = cache;
        this.clock = clock;
    }

    public String checkoutBook(String memberId, String barcode) throws Exception {
        // Preserved off-by-one: refuses only when the member ALREADY has more
        // than 10 open loans, so a member at exactly 10 gets an 11th.
        if (loans.countOpen(memberId) > 10) {
            return "Checkout refused: member already has 10 loans.";
        }
        Optional<String> status = members.statusOf(memberId);
        if (status.isPresent() && "SUSPENDED".equals(status.get())) {
            return "Checkout refused: membership is suspended.";
        }
        CopyRepository.CopyRow copy = copies.findByBarcode(barcode);
        if ("REFERENCE".equals(copy.status())) {
            // Preserved: checkout throws for reference stock (transfer returns
            // a message); note this reads the copy STATUS, not the copy type.
            throw new RuntimeException("Reference stock cannot be borrowed");
        }
        Optional<String> reservedFor = holds.readyHoldMemberForCopy(copy.id());
        if (reservedFor.isPresent() && !reservedFor.get().equals(memberId)) {
            return "Checkout refused: copy is reserved for another member.";
        }
        CopyTypePolicy policy = policies.forType(copy.copyType());
        LocalDate due = LocalDate.now(clock).plusDays(policy.loanPeriodDays());
        loans.insert(copy.id(), memberId, due);
        // Receipt text preserved verbatim, including the second sentence that
        // quotes the book rate even when the item is a periodical.
        email.send(members.emailOf(memberId), "Your checkout receipt",
            "Due " + due + ". Overdue items are fined at EUR "
            + policy.dailyRate() + " per day."
            + " Fines accrue at EUR 0.25 per open day until the item"
            + " is returned to any branch.");
        cache.invalidate(copy.isbn());
        audit.record("CHECKOUT", "copy " + copy.id() + " member " + memberId);
        return "OK";
    }

    public String returnBook(String barcode) throws Exception {
        CopyRepository.CopyRow copy = copies.findByBarcode(barcode);
        Optional<LoanRepository.OpenLoan> open = loans.findOpenByCopyId(copy.id());
        if (open.isEmpty()) {
            return "No open loan found for barcode " + barcode;
        }
        LoanRepository.OpenLoan loan = open.get();
        loans.markReturned(loan.id());
        if (LocalDate.now(clock).isAfter(loan.dueAt())) {
            double fine = fines.calculateFine(loan.id());
            fines.addToBalance(loan.memberId(), fine);
            email.send(members.emailOf(loan.memberId()), "Item returned late",
                "Thank you for returning your item. A fine of EUR " + fine
                + " has been added to your account.");
        }
        // wake the oldest hold on this title, if there is one
        Optional<HoldRepository.QueuedHold> next = holds.oldestQueuedFor(copy.isbn());
        if (next.isPresent()) {
            holds.markReady(next.get().id(), copy.id());
            email.send(members.emailOf(next.get().memberId()), "Your hold is ready",
                "The item you reserved is ready for collection.");
        }
        cache.invalidate(copy.isbn());
        audit.record("RETURN", "copy " + copy.id() + " loan " + loan.id());
        return "OK";
    }

    public String renewLoan(String loanId) throws Exception {
        Optional<LoanRepository.OpenLoan> open = loans.findOpenById(loanId);
        if (open.isEmpty()) {
            return "No open loan with id " + loanId;
        }
        LoanRepository.OpenLoan loan = open.get();
        if (loan.renewalCount() >= 2) {
            return "Renewal refused: limit reached.";
        }
        // Preserved inconsistency: renewal refuses at >= 10 open loans while
        // checkout refuses at > 10.
        if (loans.countOpen(loan.memberId()) >= 10) {
            return "Renewal refused: member is at the loan limit.";
        }
        // Preserved: renewals always add 21 days, even for 7-day periodicals.
        LocalDate newDue = loan.dueAt().plusDays(21);
        loans.renew(loanId, newDue, loan.renewalCount() + 1);
        email.send(members.emailOf(loan.memberId()), "Loan renewed",
            "Your loan has been renewed. The new due date is " + newDue + ".");
        audit.record("RENEW", "loan " + loanId + " new due " + newDue);
        return "OK";
    }
}
```

```java
public final class HoldService {
    private final HoldRepository holds;
    private final MemberRepository members;
    private final EmailSender email;
    private final AuditLog audit;

    public HoldService(HoldRepository holds, MemberRepository members,
            EmailSender email, AuditLog audit) {
        this.holds = holds;
        this.members = members;
        this.email = email;
        this.audit = audit;
    }

    public String placeHold(String memberId, String isbn) throws Exception {
        // Preserved: no check that the member exists/is active, nor that the
        // ISBN exists in stock.
        if (holds.hasActiveHold(memberId, isbn)) {
            return "Member already has an active hold on this title.";
        }
        String holdId = UUID.randomUUID().toString();
        holds.insert(holdId, memberId, isbn);
        audit.record("HOLD_PLACED", "member " + memberId + " isbn " + isbn);
        return holdId;
    }

    public String cancelHold(String holdId) throws Exception {
        if (holds.cancel(holdId) == 0) {
            return "Hold not found or already fulfilled.";
        }
        return "OK";
    }

    public int expireHolds() throws Exception {
        int expired = 0;
        for (HoldRepository.QueuedHold h : holds.findReadyOverdueForCollection()) {
            holds.markExpired(h.id());
            email.send(members.emailOf(h.memberId()), "Your hold has expired",
                "The item you reserved was not collected in time and has"
                + " been passed to the next member.");
            expired++;
        }
        // Preserved: the copy is not automatically offered to the next hold in
        // the queue; the next return of the copy will pick it up.
        audit.record("HOLDS_EXPIRED", String.valueOf(expired));
        return expired;
    }
}
```

```java
public final class MembershipService {
    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final MemberRepository members;
    private final LoanRepository loans;
    private final HoldRepository holds;
    private final NoticeService notices;
    private final EmailSender email;
    private final AuditLog audit;

    public MembershipService(MemberRepository members, LoanRepository loans,
            HoldRepository holds, NoticeService notices, EmailSender email,
            AuditLog audit) {
        this.members = members;
        this.loans = loans;
        this.holds = holds;
        this.notices = notices;
        this.email = email;
        this.audit = audit;
    }

    public String registerMember(String name, String email, String tier) throws Exception {
        if (name == null || name.trim().isEmpty()) {
            return "Name is required.";
        }
        if (email == null || !email.contains("@")) {
            return "Invalid email address.";
        }
        if (tier == null || tier.trim().isEmpty()) {
            tier = "ADULT";  // sensible default (preserved)
        }
        if (members.emailExists(email)) {
            return "A member with this email already exists.";
        }
        String memberId = UUID.randomUUID().toString();
        members.insert(memberId, name, email, tier);
        notices.sendWelcomeEmail(memberId);
        return memberId;
    }

    public String updateMemberEmail(String memberId, String newEmail) throws Exception {
        if (newEmail == null || !newEmail.contains("@")) {
            return "Invalid email address.";
        }
        // Preserved: returns "OK" even if no such member exists.
        members.updateEmail(memberId, newEmail);
        return "OK";
    }

    public String suspendMember(String memberId, String reason) throws Exception {
        members.updateStatus(memberId, "SUSPENDED");
        // a suspended member should not sit on ready holds (preserved)
        holds.cancelAllActiveFor(memberId);
        audit.record("SUSPEND", "member " + memberId);
        email.send(members.emailOf(memberId), "Your membership has been suspended",
            "Your membership has been suspended. Reason: " + reason
            + ". Please contact your branch.");
        return "OK";
    }

    public String reinstateMember(String memberId) throws Exception {
        members.updateStatus(memberId, "ACTIVE");
        email.send(members.emailOf(memberId), "Welcome back",
            "Your membership has been reinstated.");
        return "OK";
    }

    public String getMemberSummary(String memberId) throws Exception {
        Optional<MemberRepository.MemberRow> m = members.findById(memberId);
        if (m.isEmpty()) {
            return "Member not found.";
        }
        int loanCount = loans.countOpen(memberId);
        StringBuilder sb = new StringBuilder();
        sb.append("Member: ").append(m.get().name()).append("\n");
        sb.append("Tier: ").append(m.get().tier()).append("\n");
        sb.append("Current loans: ").append(loanCount).append("\n");
        // Preserved: this text implies a limit of 10, while checkout actually
        // allows an 11th loan (see CirculationService.checkoutBook).
        if (loanCount < 10) {
            sb.append("You can borrow ").append(10 - loanCount)
              .append(" more items.\n");
        } else {
            sb.append("You cannot borrow more items right now.\n");
        }
        return sb.toString();
    }

    public List<String> listMemberLoans(String memberId) throws Exception {
        List<String> out = new ArrayList<>();
        for (LoanRepository.MemberLoanLine line : loans.openLoansFor(memberId)) {
            out.add(line.title() + " (due " + line.dueAt().format(DATE_FMT) + ")");
        }
        return out;
    }
}
```

```java
public final class FineService {
    private final LoanRepository loans;
    private final FineAccountRepository fineAccounts;
    private final MemberRepository members;
    private final CopyTypePolicies policies;
    private final EmailSender email;
    private final AuditLog audit;
    private final Clock clock;

    public FineService(LoanRepository loans, FineAccountRepository fineAccounts,
            MemberRepository members, CopyTypePolicies policies, EmailSender email,
            AuditLog audit, Clock clock) {
        this.loans = loans;
        this.fineAccounts = fineAccounts;
        this.members = members;
        this.policies = policies;
        this.email = email;
        this.audit = audit;
        this.clock = clock;
    }

    public double calculateFine(String loanId) throws Exception {
        Optional<LoanRepository.FineBasis> basis = loans.fineBasis(loanId);
        if (basis.isEmpty()) {
            return 0.0;
        }
        LocalDate dueAt = basis.get().dueAt();
        LocalDate today = LocalDate.now(clock);
        if (!today.isAfter(dueAt)) {
            return 0.0;
        }
        long daysOverdue = ChronoUnit.DAYS.between(dueAt, today);
        return policies.forType(basis.get().copyType()).fineFor(daysOverdue);
    }

    /** Used by CirculationService when a late item is returned. */
    void addToBalance(String memberId, double fine) throws SQLException {
        fineAccounts.ensureExists(memberId);
        double balance = fineAccounts.balanceOf(memberId).orElse(0.0);
        fineAccounts.setBalance(memberId, balance + fine);
    }

    public String payFine(String memberId, double amount) throws Exception {
        OptionalDouble balanceOpt = fineAccounts.balanceOf(memberId);
        // Preserved check order: missing account first, then non-positive amount.
        if (balanceOpt.isEmpty()) {
            return "No fine account for member " + memberId;
        }
        double balance = balanceOpt.getAsDouble();
        if (amount <= 0) {
            return "Payment must be greater than zero.";
        }
        if (amount > balance) {
            return "Payment exceeds balance of EUR " + balance;
        }
        fineAccounts.setBalance(memberId, balance - amount);
        email.send(members.emailOf(memberId), "Payment received",
            "We received your payment of EUR " + amount + ". Your new"
            + " balance is EUR " + (balance - amount) + ".");
        audit.record("FINE_PAID", "member " + memberId + " amount " + amount);
        return "OK";
    }

    public String waiveFine(String memberId, String reason) throws Exception {
        // Preserved: waives (and records a waiver) even if no fine account exists.
        fineAccounts.clearBalance(memberId);
        fineAccounts.recordWaiver(memberId, reason);
        audit.record("FINE_WAIVED", "member " + memberId + " reason " + reason);
        return "OK";
    }
}
```

```java
public final class NoticeService {
    /**
     * WARNING (preserved from the original): notices quote a flat EUR 0.25/day
     * for EVERY item type with no cap. This disagrees with FineService, which
     * charges 0.50/day for periodicals and caps book fines at 60 days.
     * Members can therefore be told they owe an amount different from what
     * they are actually charged. Fixing this is a policy decision, not a
     * refactoring, so it is deliberately left in place.
     */
    private static final double FLAT_NOTICE_RATE = 0.25;

    private final LoanRepository loans;
    private final HoldRepository holds;
    private final MemberRepository members;
    private final EmailSender email;
    private final Clock clock;

    public NoticeService(LoanRepository loans, HoldRepository holds,
            MemberRepository members, EmailSender email, Clock clock) {
        this.loans = loans;
        this.holds = holds;
        this.members = members;
        this.email = email;
        this.clock = clock;
    }

    public void sendOverdueNotices() throws Exception {
        for (LoanRepository.OverdueLoan loan : loans.findAllOverdue()) {
            long daysLate = ChronoUnit.DAYS.between(loan.dueAt(), LocalDate.now(clock));
            double owed = daysLate * FLAT_NOTICE_RATE;
            email.send(members.emailOf(loan.memberId()), "Overdue notice",
                "You owe EUR " + owed + ". The daily rate is EUR 0.25.");
        }
    }

    public void sendHoldReadyNotices() throws Exception {
        for (HoldRepository.ReadyHold h : holds.findReadyUnnotified()) {
            email.send(members.emailOf(h.memberId()),
                "Reminder: your hold is waiting",
                "\"" + h.title() + "\" is being held for you."
                + " Please collect it within 7 days.");
            holds.markNotified(h.id());
        }
    }

    public void sendWelcomeEmail(String memberId) throws Exception {
        email.send(members.emailOf(memberId), "Welcome to Ardara Libraries",
            "Welcome! Your membership is now active. You can borrow"
            + " items at any of our eleven branches.");
    }
}
```

```java
public final class ReportService {
    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Preserved: same flat-rate discrepancy as NoticeService — see there. */
    private static final double FLAT_REPORT_RATE = 0.25;

    private final LoanRepository loans;
    private final HoldRepository holds;
    private final CopyRepository copies;
    private final Clock clock;

    public ReportService(LoanRepository loans, HoldRepository holds,
            CopyRepository copies, Clock clock) {
        this.loans = loans;
        this.holds = holds;
        this.copies = copies;
        this.clock = clock;
    }

    public String generateOverdueReport(String branchCode) throws Exception {
        StringBuilder sb = new StringBuilder("OVERDUE REPORT\n");
        sb.append("Branch: ").append(branchCode).append("\n");
        sb.append("Generated: ").append(LocalDate.now(clock).format(DATE_FMT))
          .append("\n\n");
        double total = 0.0;
        int count = 0;
        for (LoanRepository.BranchOverdueLoan row
                : loans.findOverdueForBranchOrdered(branchCode)) {
            long daysLate = ChronoUnit.DAYS.between(row.dueAt(), LocalDate.now(clock));
            double owed = daysLate * FLAT_REPORT_RATE;
            sb.append(row.memberName()).append(" | ")
              .append(row.title()).append(" | ")
              .append(daysLate).append(" days | EUR ")
              .append(owed).append("\n");
            total += owed;
            count++;
        }
        sb.append("\nTotal overdue: ").append(count).append("\n");
        sb.append("Total owed: EUR ").append(total).append("\n");
        return sb.toString();
    }

    public String generateBranchStatsReport(String branchCode) throws Exception {
        StringBuilder sb = new StringBuilder("BRANCH STATISTICS\n");
        sb.append("Checkouts (30 days): ")
          .append(loans.countCheckoutsLast30Days(branchCode)).append("\n");
        sb.append("Holds queued: ")
          .append(holds.countQueuedForBranch(branchCode)).append("\n");
        sb.append("Active members (30 days): ")
          .append(loans.countDistinctActiveMembersLast30Days()).append("\n");
        sb.append("Currently overdue: ")
          .append(loans.countOverdueForBranch(branchCode)).append("\n");
        sb.append("Lost items: ")
          .append(copies.countLost(branchCode)).append("\n");
        return sb.toString();
    }

    public String exportOverdueCsv(String branchCode, String filePath) throws Exception {
        List<LoanRepository.BranchOverdueLoan> rows =
            loans.findOverdueForBranchUnordered(branchCode);
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath))) {
            // NB (preserved): no escaping of commas in titles; Finance opens
            // this in Excel.
            pw.println("branch,name,email,title,due_at,days_overdue");
            for (LoanRepository.BranchOverdueLoan row : rows) {
                long daysLate = ChronoUnit.DAYS.between(row.dueAt(), LocalDate.now(clock));
                pw.println(branchCode + "," + row.memberName() + ","
                    + row.memberEmail() + "," + row.title()
                    + "," + row.dueAt() + "," + daysLate);
            }
        }
        return filePath;
    }
}
```

```java
public final class StockService {
    private final CopyRepository copies;
    private final LoanRepository loans;
    private final MemberRepository members;
    private final BarcodeScheme barcodes;
    private final EmailSender email;
    private final AuditLog audit;
    private final AvailabilityCache cache;

    public StockService(CopyRepository copies, LoanRepository loans,
            MemberRepository members, BarcodeScheme barcodes, EmailSender email,
            AuditLog audit, AvailabilityCache cache) {
        this.copies = copies;
        this.loans = loans;
        this.members = members;
        this.barcodes = barcodes;
        this.email = email;
        this.audit = audit;
        this.cache = cache;
    }

    public String transferCopy(String barcode, String toBranchCode) throws Exception {
        CopyRepository.CopyRow copy = copies.findByBarcode(barcode);
        if ("REFERENCE".equals(copy.status())) {
            return "Reference stock cannot be transferred.";
        }
        if (loans.copyOnLoan(copy.id())) {
            return "Cannot transfer a copy that is out on loan.";
        }
        copies.updateBranch(copy.id(), toBranchCode);
        cache.invalidate(copy.isbn());
        return "OK";
    }

    public String markCopyLost(String barcode) throws Exception {
        CopyRepository.CopyRow copy = copies.findByBarcode(barcode);
        copies.updateStatus(copy.id(), "LOST");
        Optional<LoanRepository.OpenLoan> open = loans.findOpenByCopyId(copy.id());
        if (open.isPresent()) {
            // Preserved: the email promises a replacement charge that no code
            // anywhere actually applies.
            email.send(members.emailOf(open.get().memberId()), "Item marked as lost",
                "An item on your account has been marked lost. The"
                + " replacement cost will be added to your account.");
        }
        cache.invalidate(copy.isbn());
        return "OK";
    }

    public String markCopyRepaired(String barcode) throws Exception {
        CopyRepository.CopyRow copy = copies.findByBarcode(barcode);
        copies.updateStatus(copy.id(), "AVAILABLE");
        cache.invalidate(copy.isbn());
        return "OK";
    }

    public String addCopy(String isbn, String title, String copyType,
            String branchCode) throws Exception {
        String copyId = UUID.randomUUID().toString();
        String barcode = barcodes.generate(branchCode);
        copies.insert(copyId, isbn, title, copyType, branchCode, barcode);
        cache.invalidate(isbn);
        audit.record("COPY_ADDED", "isbn " + isbn + " barcode " + barcode);
        return barcode;
    }

    public String removeCopy(String barcode) throws Exception {
        CopyRepository.CopyRow copy = copies.findByBarcode(barcode);
        if (loans.copyOnLoan(copy.id())) {
            return "Cannot remove a copy that is out on loan.";
        }
        copies.updateStatus(copy.id(), "WITHDRAWN");
        cache.invalidate(copy.isbn());
        return "OK";
    }
}
```

```java
public final class CatalogueService {
    private final CopyRepository copies;
    private final AvailabilityCache cache;

    public CatalogueService(CopyRepository copies, AvailabilityCache cache) {
        this.copies = copies;
        this.cache = cache;
    }

    public List<String> searchCatalogue(String query) throws Exception {
        List<String> out = new ArrayList<>();
        for (CopyRepository.SearchHit hit : copies.searchAvailableByTitle(query)) {
            out.add(hit.title() + " [" + hit.isbn() + "] ("
                + getAvailability(hit.isbn()) + " available)");
        }
        return out;
    }

    public int getAvailability(String isbn) throws Exception {
        Integer cached = cache.get(isbn);
        if (cached != null) {
            return cached;
        }
        int available = copies.countAvailable(isbn);
        cache.put(isbn, available);
        return available;
    }
}
```

```java
public final class BranchService {
    private final BranchRepository branches;
    private final Clock clock;

    public BranchService(BranchRepository branches, Clock clock) {
        this.branches = branches;
        this.clock = clock;
    }

    public boolean isBranchOpen(String branchCode) throws Exception {
        DayOfWeek day = LocalDate.now(clock).getDayOfWeek();
        if (day == DayOfWeek.SUNDAY) {
            // Preserved: hard-coded Sunday closure regardless of branch_hours.
            return false;
        }
        Optional<BranchRepository.Hours> hours = branches.hoursFor(branchCode, day);
        if (hours.isEmpty()) {
            return false;
        }
        if (branches.isHoliday(branchCode, LocalDate.now(clock))) {
            return false;
        }
        LocalTime now = LocalTime.now(clock);
        return !now.isBefore(hours.get().opensAt())
            && now.isBefore(hours.get().closesAt());
    }
}
```

## The facade (unchanged public API)

```java
/**
 * Facade over the Bookline services. Every method signature from the old
 * god class is preserved, so existing callers compile and behave unchanged.
 * New code should depend on the individual services instead.
 */
public class LibraryManager {

    private final CirculationService circulation;
    private final HoldService holdService;
    private final MembershipService membership;
    private final FineService fines;
    private final NoticeService notices;
    private final ReportService reports;
    private final StockService stock;
    private final CatalogueService catalogue;
    private final BranchService branch;
    private final BarcodeScheme barcodes;

    /** Production wiring. TODO: move credentials to configuration. */
    public LibraryManager() {
        this(new DriverManagerConnectionProvider(
                "jdbc:postgresql://localhost:5432/bookline", "bookline", "changeme"),
             new SmtpEmailSender("mail.ardaralibraries.ie", 25,
                "noreply@ardaralibraries.ie"),
             Clock.systemDefaultZone());
    }

    /** Composition root; inject fakes here for testing. */
    public LibraryManager(ConnectionProvider db, EmailSender email, Clock clock) {
        MemberRepository members = new MemberRepository(db);
        LoanRepository loans = new LoanRepository(db);
        HoldRepository holds = new HoldRepository(db);
        CopyRepository copies = new CopyRepository(db);
        FineAccountRepository fineAccounts = new FineAccountRepository(db);
        BranchRepository branches = new BranchRepository(db);
        AuditLog audit = new DatabaseAuditLog(db);
        AvailabilityCache cache = new AvailabilityCache();
        CopyTypePolicies policies = new CopyTypePolicies();
        this.barcodes = new BarcodeScheme();

        this.fines = new FineService(loans, fineAccounts, members, policies,
            email, audit, clock);
        this.notices = new NoticeService(loans, holds, members, email, clock);
        this.circulation = new CirculationService(members, loans, copies, holds,
            fines, policies, email, audit, cache, clock);
        this.holdService = new HoldService(holds, members, email, audit);
        this.membership = new MembershipService(members, loans, holds, notices,
            email, audit);
        this.reports = new ReportService(loans, holds, copies, clock);
        this.stock = new StockService(copies, loans, members, barcodes, email,
            audit, cache);
        this.catalogue = new CatalogueService(copies, cache);
        this.branch = new BranchService(branches, clock);
    }

    // -------- circulation --------
    public String checkoutBook(String memberId, String barcode) throws Exception {
        return circulation.checkoutBook(memberId, barcode);
    }
    public String returnBook(String barcode) throws Exception {
        return circulation.returnBook(barcode);
    }
    public String renewLoan(String loanId) throws Exception {
        return circulation.renewLoan(loanId);
    }

    // -------- holds --------
    public String placeHold(String memberId, String isbn) throws Exception {
        return holdService.placeHold(memberId, isbn);
    }
    public String cancelHold(String holdId) throws Exception {
        return holdService.cancelHold(holdId);
    }
    public int expireHolds() throws Exception {
        return holdService.expireHolds();
    }

    // -------- membership --------
    public String registerMember(String name, String email, String tier) throws Exception {
        return membership.registerMember(name, email, tier);
    }
    public String updateMemberEmail(String memberId, String newEmail) throws Exception {
        return membership.updateMemberEmail(memberId, newEmail);
    }
    public String suspendMember(String memberId, String reason) throws Exception {
        return membership.suspendMember(memberId, reason);
    }
    public String reinstateMember(String memberId) throws Exception {
        return membership.reinstateMember(memberId);
    }
    public String getMemberSummary(String memberId) throws Exception {
        return membership.getMemberSummary(memberId);
    }
    public List<String> listMemberLoans(String memberId) throws Exception {
        return membership.listMemberLoans(memberId);
    }

    // -------- fines --------
    public double calculateFine(String loanId) throws Exception {
        return fines.calculateFine(loanId);
    }
    public String payFine(String memberId, double amount) throws Exception {
        return fines.payFine(memberId, amount);
    }
    public String waiveFine(String memberId, String reason) throws Exception {
        return fines.waiveFine(memberId, reason);
    }

    // -------- notices --------
    public void sendOverdueNotices() throws Exception {
        notices.sendOverdueNotices();
    }
    public void sendHoldReadyNotices() throws Exception {
        notices.sendHoldReadyNotices();
    }
    public void sendWelcomeEmail(String memberId) throws Exception {
        notices.sendWelcomeEmail(memberId);
    }

    // -------- reports --------
    public String generateOverdueReport(String branchCode) throws Exception {
        return reports.generateOverdueReport(branchCode);
    }
    public String generateBranchStatsReport(String branchCode) throws Exception {
        return reports.generateBranchStatsReport(branchCode);
    }
    public String exportOverdueCsv(String branchCode, String filePath) throws Exception {
        return reports.exportOverdueCsv(branchCode, filePath);
    }

    // -------- stock --------
    public String transferCopy(String barcode, String toBranchCode) throws Exception {
        return stock.transferCopy(barcode, toBranchCode);
    }
    public String markCopyLost(String barcode) throws Exception {
        return stock.markCopyLost(barcode);
    }
    public String markCopyRepaired(String barcode) throws Exception {
        return stock.markCopyRepaired(barcode);
    }
    public String addCopy(String isbn, String title, String copyType,
            String branchCode) throws Exception {
        return stock.addCopy(isbn, title, copyType, branchCode);
    }
    public String removeCopy(String barcode) throws Exception {
        return stock.removeCopy(barcode);
    }

    // -------- catalogue --------
    public List<String> searchCatalogue(String query) throws Exception {
        return catalogue.searchCatalogue(query);
    }
    public int getAvailability(String isbn) throws Exception {
        return catalogue.getAvailability(isbn);
    }

    // -------- branch --------
    public boolean isBranchOpen(String branchCode) throws Exception {
        return branch.isBranchOpen(branchCode);
    }
    public boolean validateBarcode(String barcode) {
        return barcodes.validate(barcode);
    }

    // Preserved smoke test; still runs against the live database — run with care.
    public static void main(String[] args) throws Exception {
        LibraryManager mgr = new LibraryManager();
        System.out.println(mgr.checkoutBook("m-1001", "40012938-7"));
        System.out.println(mgr.calculateFine("l-2001"));
        System.out.println(mgr.getMemberSummary("m-1001"));
        System.out.println(mgr.generateOverdueReport("GLE"));
        System.out.println(mgr.isBranchOpen("GLE"));
        mgr.sendOverdueNotices();
        mgr.expireHolds();
    }
}
```

## What changed, what didn't, and what to do next

**Preserved unchanged:** every rate, limit, message string, email subject/body, check ordering, side-effect ordering (mark-returned before fine, audit after email, cache invalidation points), report and CSV formats, ordering (report sorted by due date, CSV unsorted), all thirteen quirks listed at the top, and the entire public API including `main`. The `Copy`/`Periodical` classes are no longer used internally (the type check now goes through `CopyTypePolicy` keyed on `copy_type`, which is what `findCopyByBarcode` used to switch on anyway), but I haven't touched those classes — other code may use them.

**Deliberately changed:**
1. All SQL is parameterized. Injection is closed, and inputs containing single quotes (member names, titles, search queries, waiver reasons) now behave correctly instead of failing or corrupting statements. This is the only change that alters observable behaviour for any input.
2. Connections are opened per operation and closed via try-with-resources; the original opened a fresh connection on nearly every call, never closed any of them, and stored the latest in a shared mutable field. Statement-level autocommit semantics are unchanged (still no transactions — see below).
3. The availability cache is a `ConcurrentHashMap` behind a small class instead of a bare `HashMap` field. Same no-TTL semantics.

**Deliberately not done (each deserves its own reviewed change, ideally after characterization tests exist):**
- **Transactions.** `checkoutBook` and `returnBook` are still sequences of individually-committed statements, exactly as before; a crash mid-checkout still leaves partial state. Wrapping each service operation in a transaction is the highest-value follow-up, but it changes failure behaviour, so it shouldn't ride along with a refactoring.
- **Fixing the quirk list.** Items 1, 2, 3, 7, 8, and 9 in particular look like real defects with member-facing or finance-facing impact. Each is now isolated in one commented place, so each can be fixed (or blessed) as a one-line change once someone with authority over library policy signs off.
- **Money as `double`.** Fine arithmetic still uses `double` (and therefore still prints the occasional `15.700000000000001` in report totals, same as before). Moving to integer cents or `BigDecimal` changes printed output, so it's a separate change.
- **Externalizing credentials** — the DB password and SMTP host are still the same literals, now confined to the default constructor.

**First follow-up I'd actually schedule:** write characterization tests against the facade using the injectable seams that now exist (`ConnectionProvider` pointed at a throwaway Postgres, a recording `EmailSender` fake, `Clock.fixed`) to pin the current behaviour — including the quirks — before anyone starts fixing them. That's the test suite this class should have had before either of us touched it.
