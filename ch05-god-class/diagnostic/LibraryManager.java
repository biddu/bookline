import java.sql.*;
import javax.mail.*;
import javax.mail.internet.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * Central manager for Ardara Libraries' Bookline system: circulation,
 * holds, membership, fines, notices, reports, stock and branch queries.
 *
 * TODO: split this up at some point; it has grown a bit.
 */
public class LibraryManager {

    private Connection conn;
    private static final String DB_URL =
        "jdbc:postgresql://localhost:5432/bookline";
    private static final String SMTP_HOST = "mail.ardaralibraries.ie";
    private static final double DAILY_FINE_RATE = 0.25;
    private static final double PERIODICAL_FINE_RATE = 0.50;
    private static final int SMTP_PORT = 25;
    private static final String FROM_ADDRESS = "noreply@ardaralibraries.ie";
    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private HashMap<String, Integer> availabilityCache = new HashMap<>();

    // NOTE: the two rate fields above were added for a cleanup that was
    // never finished; the live rates are still written inline below.
    // Do not change a rate without also checking checkoutBook,
    // calculateFine, sendOverdueNotices and generateOverdueReport.

    // ==================== CIRCULATION ====================

    /**
     * Checks a copy out to a member, emails the receipt,
     * and refreshes the availability cache.
     */
    public String checkoutBook(String memberId, String barcode)
            throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM loan WHERE member_id = '" + memberId
            + "' AND returned_at IS NULL");
        rs.next();
        if (rs.getInt(1) > 10) {
            return "Checkout refused: member already has 10 loans.";
        }
        ResultSet srs = conn.createStatement().executeQuery(
            "SELECT status FROM member WHERE id = '" + memberId + "'");
        if (srs.next() && "SUSPENDED".equals(srs.getString("status"))) {
            return "Checkout refused: membership is suspended.";
        }
        Copy copy = findCopyByBarcode(barcode);
        if ("REFERENCE".equals(copy.getStatus())) {
            throw new RuntimeException("Reference stock cannot be borrowed");
        }
        ResultSet crs = conn.createStatement().executeQuery(
            "SELECT member_id FROM hold WHERE copy_id = '" + copy.getId()
            + "' AND status = 'READY'");
        if (crs.next() && !crs.getString("member_id").equals(memberId)) {
            return "Checkout refused: copy is reserved for another member.";
        }
        LocalDate due = LocalDate.now().plusDays(21);
        if (copy instanceof Periodical) {
            due = LocalDate.now().plusDays(7);  // periodicals: one week
        }
        conn.createStatement().executeUpdate(
            "INSERT INTO loan (copy_id, member_id, due_at) VALUES ('"
            + copy.getId() + "', '" + memberId + "', '" + due + "')");
        sendEmail(lookupEmail(memberId), "Your checkout receipt",
            "Due " + due + ". Overdue items are fined at EUR "
            + (copy instanceof Periodical ? 0.50 : 0.25) + " per day."
            + " Fines accrue at EUR 0.25 per open day until the item"
            + " is returned to any branch.");
        availabilityCache.remove(copy.getIsbn());
        logAudit("CHECKOUT", "copy " + copy.getId() + " member " + memberId);
        return "OK";
    }

    /**
     * Returns a copy, assesses any fine, credits the fine
     * account, and wakes the oldest hold on the title.
     */
    public String returnBook(String barcode) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        Copy copy = findCopyByBarcode(barcode);
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT id, member_id, due_at FROM loan WHERE copy_id = '"
            + copy.getId() + "' AND returned_at IS NULL");
        if (!rs.next()) {
            return "No open loan found for barcode " + barcode;
        }
        String loanId = rs.getString("id");
        String memberId = rs.getString("member_id");
        LocalDate dueAt = rs.getDate("due_at").toLocalDate();
        conn.createStatement().executeUpdate(
            "UPDATE loan SET returned_at = NOW() WHERE id = '" + loanId + "'");
        if (LocalDate.now().isAfter(dueAt)) {
            double fine = calculateFine(loanId);
            ensureFineAccount(memberId);
            ResultSet frs = conn.createStatement().executeQuery(
                "SELECT balance FROM fine_account WHERE member_id = '"
                + memberId + "'");
            frs.next();
            conn.createStatement().executeUpdate(
                "UPDATE fine_account SET balance = "
                + (frs.getDouble("balance") + fine) + " WHERE member_id = '"
                + memberId + "'");
            sendEmail(lookupEmail(memberId), "Item returned late",
                "Thank you for returning your item. A fine of EUR " + fine
                + " has been added to your account.");
        }
        // wake the oldest hold on this title, if there is one
        ResultSet hrs = conn.createStatement().executeQuery(
            "SELECT id, member_id FROM hold WHERE isbn = '" + copy.getIsbn()
            + "' AND status = 'QUEUED' ORDER BY placed_at LIMIT 1");
        if (hrs.next()) {
            conn.createStatement().executeUpdate(
                "UPDATE hold SET status = 'READY', copy_id = '" + copy.getId()
                + "' WHERE id = '" + hrs.getString("id") + "'");
            sendEmail(lookupEmail(hrs.getString("member_id")),
                "Your hold is ready",
                "The item you reserved is ready for collection.");
        }
        availabilityCache.remove(copy.getIsbn());
        logAudit("RETURN", "copy " + copy.getId() + " loan " + loanId);
        return "OK";
    }

    /**
     * Places a hold for a member on a title (one active hold
     * per member per title).
     */
    public String placeHold(String memberId, String isbn) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM hold WHERE member_id = '" + memberId
            + "' AND isbn = '" + isbn + "' AND status IN ('QUEUED','READY')");
        rs.next();
        if (rs.getInt(1) > 0) {
            return "Member already has an active hold on this title.";
        }
        String holdId = UUID.randomUUID().toString();
        conn.createStatement().executeUpdate(
            "INSERT INTO hold (id, member_id, isbn, status, placed_at) VALUES ('"
            + holdId + "', '" + memberId + "', '" + isbn
            + "', 'QUEUED', NOW())");
        logAudit("HOLD_PLACED", "member " + memberId + " isbn " + isbn);
        return holdId;
    }

    /**
     * Cancels a queued or ready hold.
     */
    public String cancelHold(String holdId) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        int updated = conn.createStatement().executeUpdate(
            "UPDATE hold SET status = 'CANCELLED' WHERE id = '" + holdId
            + "' AND status IN ('QUEUED','READY')");
        if (updated == 0) {
            return "Hold not found or already fulfilled.";
        }
        return "OK";
    }

    /**
     * Renews an open loan for a further three weeks, up to
     * two renewals.
     */
    public String renewLoan(String loanId) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT member_id, due_at, renewal_count FROM loan WHERE id = '"
            + loanId + "' AND returned_at IS NULL");
        if (!rs.next()) {
            return "No open loan with id " + loanId;
        }
        String memberId = rs.getString("member_id");
        int renewals = rs.getInt("renewal_count");
        if (renewals >= 2) {
            return "Renewal refused: limit reached.";
        }
        ResultSet lrs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM loan WHERE member_id = '" + memberId
            + "' AND returned_at IS NULL");
        lrs.next();
        if (lrs.getInt(1) >= 10) {
            return "Renewal refused: member is at the loan limit.";
        }
        LocalDate newDue = rs.getDate("due_at").toLocalDate().plusDays(21);
        conn.createStatement().executeUpdate(
            "UPDATE loan SET due_at = '" + newDue + "', renewal_count = "
            + (renewals + 1) + " WHERE id = '" + loanId + "'");
        sendEmail(lookupEmail(memberId), "Loan renewed",
            "Your loan has been renewed. The new due date is " + newDue + ".");
        logAudit("RENEW", "loan " + loanId + " new due " + newDue);
        return "OK";
    }

    // ==================== MEMBERSHIP ====================

    /**
     * Registers a new member and sends the welcome email.
     */
    public String registerMember(String name, String email, String tier)
            throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        if (name == null || name.trim().isEmpty()) {
            return "Name is required.";
        }
        if (email == null || !email.contains("@")) {
            return "Invalid email address.";
        }
        if (tier == null || tier.trim().isEmpty()) {
            tier = "ADULT";  // sensible default
        }
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM member WHERE email = '" + email + "'");
        rs.next();
        if (rs.getInt(1) > 0) {
            return "A member with this email already exists.";
        }
        String memberId = UUID.randomUUID().toString();
        conn.createStatement().executeUpdate(
            "INSERT INTO member (id, name, email, tier, status, joined_at)"
            + " VALUES ('" + memberId + "', '" + name + "', '" + email
            + "', '" + tier + "', 'ACTIVE', NOW())");
        sendWelcomeEmail(memberId);
        return memberId;
    }

    /**
     * Updates the email address on a membership.
     */
    public String updateMemberEmail(String memberId, String newEmail)
            throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        if (newEmail == null || !newEmail.contains("@")) {
            return "Invalid email address.";
        }
        conn.createStatement().executeUpdate(
            "UPDATE member SET email = '" + newEmail + "' WHERE id = '"
            + memberId + "'");
        return "OK";
    }

    /**
     * Suspends a membership and notifies the member.
     */
    public String suspendMember(String memberId, String reason)
            throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        conn.createStatement().executeUpdate(
            "UPDATE member SET status = 'SUSPENDED' WHERE id = '"
            + memberId + "'");
        // a suspended member should not sit on ready holds
        conn.createStatement().executeUpdate(
            "UPDATE hold SET status = 'CANCELLED' WHERE member_id = '"
            + memberId + "' AND status IN ('QUEUED','READY')");
        logAudit("SUSPEND", "member " + memberId);
        sendEmail(lookupEmail(memberId), "Your membership has been suspended",
            "Your membership has been suspended. Reason: " + reason
            + ". Please contact your branch.");
        return "OK";
    }

    /**
     * Reinstates a suspended membership.
     */
    public String reinstateMember(String memberId) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        conn.createStatement().executeUpdate(
            "UPDATE member SET status = 'ACTIVE' WHERE id = '"
            + memberId + "'");
        sendEmail(lookupEmail(memberId), "Welcome back",
            "Your membership has been reinstated.");
        return "OK";
    }

    /**
     * Builds the member summary shown in the web app.
     */
    public String getMemberSummary(String memberId) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet mrs = conn.createStatement().executeQuery(
            "SELECT name, tier FROM member WHERE id = '" + memberId + "'");
        if (!mrs.next()) {
            return "Member not found.";
        }
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM loan WHERE member_id = '" + memberId
            + "' AND returned_at IS NULL");
        rs.next();
        int loans = rs.getInt(1);
        StringBuilder sb = new StringBuilder();
        sb.append("Member: ").append(mrs.getString("name")).append("\n");
        sb.append("Tier: ").append(mrs.getString("tier")).append("\n");
        sb.append("Current loans: ").append(loans).append("\n");
        if (loans < 10) {
            sb.append("You can borrow ").append(10 - loans)
              .append(" more items.\n");
        } else {
            sb.append("You cannot borrow more items right now.\n");
        }
        return sb.toString();
    }

    /**
     * Lists the open loans for one member, soonest due first.
     */
    public List<String> listMemberLoans(String memberId) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        List<String> out = new ArrayList<>();
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT c.title, l.due_at FROM loan l JOIN copy c"
            + " ON l.copy_id = c.id WHERE l.member_id = '" + memberId
            + "' AND l.returned_at IS NULL ORDER BY l.due_at");
        while (rs.next()) {
            out.add(rs.getString("title") + " (due "
                + rs.getDate("due_at").toLocalDate().format(DATE_FMT) + ")");
        }
        return out;
    }

    // ==================== FINES ====================

    /**
     * Computes the fine owed on a loan as of today.
     */
    public double calculateFine(String loanId) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT l.due_at, c.copy_type FROM loan l JOIN copy c"
            + " ON l.copy_id = c.id WHERE l.id = '" + loanId + "'");
        if (!rs.next()) {
            return 0.0;
        }
        LocalDate dueAt = rs.getDate("due_at").toLocalDate();
        String copyType = rs.getString("copy_type");
        if (!LocalDate.now().isAfter(dueAt)) {
            return 0.0;
        }
        long daysOverdue = ChronoUnit.DAYS.between(dueAt, LocalDate.now());
        if (copyType.equals("PERIODICAL")) {
            return daysOverdue * 0.50;
        }
        if (daysOverdue > 60) {
            return 60 * 0.25;   // capped after 60 days
        }
        return daysOverdue * 0.25;
    }

    /**
     * Records a fine payment and emails the receipt.
     */
    public String payFine(String memberId, double amount) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT balance FROM fine_account WHERE member_id = '"
            + memberId + "'");
        if (!rs.next()) {
            return "No fine account for member " + memberId;
        }
        double balance = rs.getDouble("balance");
        if (amount <= 0) {
            return "Payment must be greater than zero.";
        }
        if (amount > balance) {
            return "Payment exceeds balance of EUR " + balance;
        }
        conn.createStatement().executeUpdate(
            "UPDATE fine_account SET balance = " + (balance - amount)
            + " WHERE member_id = '" + memberId + "'");
        sendEmail(lookupEmail(memberId), "Payment received",
            "We received your payment of EUR " + amount + ". Your new"
            + " balance is EUR " + (balance - amount) + ".");
        logAudit("FINE_PAID", "member " + memberId + " amount " + amount);
        return "OK";
    }

    /**
     * Clears a member's fine balance and records the waiver.
     */
    public String waiveFine(String memberId, String reason) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        conn.createStatement().executeUpdate(
            "UPDATE fine_account SET balance = 0 WHERE member_id = '"
            + memberId + "'");
        conn.createStatement().executeUpdate(
            "INSERT INTO fine_waiver (member_id, reason, waived_at) VALUES ('"
            + memberId + "', '" + reason + "', NOW())");
        logAudit("FINE_WAIVED", "member " + memberId + " reason " + reason);
        return "OK";
    }

    // ==================== NOTICES ====================

    /**
     * Nightly job: emails every member with an overdue loan.
     */
    public void sendOverdueNotices() throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT l.id, l.member_id, l.due_at, c.title FROM loan l"
            + " JOIN copy c ON l.copy_id = c.id"
            + " WHERE l.returned_at IS NULL AND l.due_at < NOW()");
        while (rs.next()) {
            LocalDate dueAt = rs.getDate("due_at").toLocalDate();
            long daysLate = ChronoUnit.DAYS.between(dueAt, LocalDate.now());
            String email = lookupEmail(rs.getString("member_id"));
            double owed = daysLate * 0.25;
            sendEmail(email, "Overdue notice", "You owe EUR " + owed
                + ". The daily rate is EUR 0.25.");
        }
    }

    /**
     * Nightly job: reminds members whose holds are waiting.
     */
    public void sendHoldReadyNotices() throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT h.id, h.member_id, c.title FROM hold h JOIN copy c"
            + " ON h.copy_id = c.id WHERE h.status = 'READY'"
            + " AND h.notified_at IS NULL");
        while (rs.next()) {
            sendEmail(lookupEmail(rs.getString("member_id")),
                "Reminder: your hold is waiting",
                "\"" + rs.getString("title") + "\" is being held for you."
                + " Please collect it within 7 days.");
            conn.createStatement().executeUpdate(
                "UPDATE hold SET notified_at = NOW() WHERE id = '"
                + rs.getString("id") + "'");
        }
    }

    /**
     * Sends the standard welcome email.
     */
    public void sendWelcomeEmail(String memberId) throws Exception {
        sendEmail(lookupEmail(memberId), "Welcome to Ardara Libraries",
            "Welcome! Your membership is now active. You can borrow"
            + " items at any of our eleven branches.");
    }

    // ==================== REPORTS ====================

    /**
     * Builds the plain-text overdue report for one branch.
     */
    public String generateOverdueReport(String branchCode) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        StringBuilder sb = new StringBuilder("OVERDUE REPORT\n");
        sb.append("Branch: ").append(branchCode).append("\n");
        sb.append("Generated: ").append(LocalDate.now().format(DATE_FMT))
          .append("\n\n");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT m.name, c.title, l.due_at FROM loan l"
            + " JOIN member m ON l.member_id = m.id"
            + " JOIN copy c ON l.copy_id = c.id"
            + " WHERE l.returned_at IS NULL AND l.due_at < NOW()"
            + " AND c.branch_code = '" + branchCode + "' ORDER BY l.due_at");
        double total = 0.0;
        int count = 0;
        while (rs.next()) {
            LocalDate dueAt = rs.getDate("due_at").toLocalDate();
            long daysLate = ChronoUnit.DAYS.between(dueAt, LocalDate.now());
            double owed = daysLate * 0.25;
            sb.append(rs.getString("name")).append(" | ")
              .append(rs.getString("title")).append(" | ")
              .append(daysLate).append(" days | EUR ")
              .append(owed).append("\n");
            total += owed;
            count++;
        }
        sb.append("\nTotal overdue: ").append(count).append("\n");
        sb.append("Total owed: EUR ").append(total).append("\n");
        return sb.toString();
    }

    /**
     * Builds the monthly statistics summary for one branch.
     */
    public String generateBranchStatsReport(String branchCode)
            throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        StringBuilder sb = new StringBuilder("BRANCH STATISTICS\n");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM loan l JOIN copy c ON l.copy_id = c.id"
            + " WHERE c.branch_code = '" + branchCode
            + "' AND l.checked_out_at > NOW() - INTERVAL '30 days'");
        rs.next();
        sb.append("Checkouts (30 days): ").append(rs.getInt(1)).append("\n");
        ResultSet hrs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM hold h JOIN copy c ON h.copy_id = c.id"
            + " WHERE c.branch_code = '" + branchCode
            + "' AND h.status = 'QUEUED'");
        hrs.next();
        sb.append("Holds queued: ").append(hrs.getInt(1)).append("\n");
        ResultSet mrs = conn.createStatement().executeQuery(
            "SELECT COUNT(DISTINCT member_id) FROM loan"
            + " WHERE checked_out_at > NOW() - INTERVAL '30 days'");
        mrs.next();
        sb.append("Active members (30 days): ").append(mrs.getInt(1))
          .append("\n");
        ResultSet ors = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM loan l JOIN copy c ON l.copy_id = c.id"
            + " WHERE c.branch_code = '" + branchCode
            + "' AND l.returned_at IS NULL AND l.due_at < NOW()");
        ors.next();
        sb.append("Currently overdue: ").append(ors.getInt(1)).append("\n");
        ResultSet lrs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM copy WHERE branch_code = '" + branchCode
            + "' AND status = 'LOST'");
        lrs.next();
        sb.append("Lost items: ").append(lrs.getInt(1)).append("\n");
        return sb.toString();
    }

    /**
     * Writes the overdue list for one branch to a CSV file.
     */
    public String exportOverdueCsv(String branchCode, String filePath)
            throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT m.name, m.email, c.title, l.due_at FROM loan l"
            + " JOIN member m ON l.member_id = m.id"
            + " JOIN copy c ON l.copy_id = c.id"
            + " WHERE l.returned_at IS NULL AND l.due_at < NOW()"
            + " AND c.branch_code = '" + branchCode + "'");
        PrintWriter pw = new PrintWriter(new FileWriter(filePath));
        // NB: no escaping of commas in titles; Finance opens this in Excel
        pw.println("branch,name,email,title,due_at,days_overdue");
        while (rs.next()) {
            LocalDate dueAt = rs.getDate("due_at").toLocalDate();
            long daysLate = ChronoUnit.DAYS.between(dueAt, LocalDate.now());
            pw.println(branchCode + "," + rs.getString("name") + ","
                + rs.getString("email") + "," + rs.getString("title")
                + "," + dueAt + "," + daysLate);
        }
        pw.close();
        return filePath;
    }

    // ==================== HOLD MAINTENANCE ====================

    /**
     * Nightly job: expires holds not collected within a week.
     */
    public int expireHolds() throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT id, member_id FROM hold WHERE status = 'READY'"
            + " AND notified_at < NOW() - INTERVAL '7 days'");
        int expired = 0;
        while (rs.next()) {
            conn.createStatement().executeUpdate(
                "UPDATE hold SET status = 'EXPIRED' WHERE id = '"
                + rs.getString("id") + "'");
            sendEmail(lookupEmail(rs.getString("member_id")),
                "Your hold has expired",
                "The item you reserved was not collected in time and has"
                + " been passed to the next member.");
            expired++;
        }
        // NB: the copy is not automatically offered to the next hold in
        // the queue; the next return of the copy will pick it up.
        logAudit("HOLDS_EXPIRED", String.valueOf(expired));
        return expired;
    }

    // ==================== STOCK ====================

    /**
     * Moves a copy to another branch.
     */
    public String transferCopy(String barcode, String toBranchCode)
            throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        Copy copy = findCopyByBarcode(barcode);
        if ("REFERENCE".equals(copy.getStatus())) {
            return "Reference stock cannot be transferred.";
        }
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM loan WHERE copy_id = '" + copy.getId()
            + "' AND returned_at IS NULL");
        rs.next();
        if (rs.getInt(1) > 0) {
            return "Cannot transfer a copy that is out on loan.";
        }
        conn.createStatement().executeUpdate(
            "UPDATE copy SET branch_code = '" + toBranchCode
            + "' WHERE id = '" + copy.getId() + "'");
        availabilityCache.remove(copy.getIsbn());
        return "OK";
    }

    /**
     * Marks a copy lost and notifies the borrower.
     */
    public String markCopyLost(String barcode) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        Copy copy = findCopyByBarcode(barcode);
        conn.createStatement().executeUpdate(
            "UPDATE copy SET status = 'LOST' WHERE id = '"
            + copy.getId() + "'");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT member_id FROM loan WHERE copy_id = '" + copy.getId()
            + "' AND returned_at IS NULL");
        if (rs.next()) {
            sendEmail(lookupEmail(rs.getString("member_id")),
                "Item marked as lost",
                "An item on your account has been marked lost. The"
                + " replacement cost will be added to your account.");
        }
        availabilityCache.remove(copy.getIsbn());
        return "OK";
    }

    /**
     * Returns a repaired copy to the available pool.
     */
    public String markCopyRepaired(String barcode) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        Copy copy = findCopyByBarcode(barcode);
        conn.createStatement().executeUpdate(
            "UPDATE copy SET status = 'AVAILABLE' WHERE id = '"
            + copy.getId() + "'");
        availabilityCache.remove(copy.getIsbn());
        return "OK";
    }

    /**
     * Adds a new copy to stock and assigns a barcode.
     */
    public String addCopy(String isbn, String title, String copyType,
            String branchCode) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        String copyId = UUID.randomUUID().toString();
        String barcode = generateBarcode(branchCode);
        conn.createStatement().executeUpdate(
            "INSERT INTO copy (id, isbn, title, copy_type, branch_code,"
            + " barcode, status) VALUES ('" + copyId + "', '" + isbn
            + "', '" + title + "', '" + copyType + "', '" + branchCode
            + "', '" + barcode + "', 'AVAILABLE')");
        availabilityCache.remove(isbn);
        logAudit("COPY_ADDED", "isbn " + isbn + " barcode " + barcode);
        return barcode;
    }

    /**
     * Withdraws a copy from stock.
     */
    public String removeCopy(String barcode) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        Copy copy = findCopyByBarcode(barcode);
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM loan WHERE copy_id = '" + copy.getId()
            + "' AND returned_at IS NULL");
        rs.next();
        if (rs.getInt(1) > 0) {
            return "Cannot remove a copy that is out on loan.";
        }
        conn.createStatement().executeUpdate(
            "UPDATE copy SET status = 'WITHDRAWN' WHERE id = '"
            + copy.getId() + "'");
        availabilityCache.remove(copy.getIsbn());
        return "OK";
    }

    // ==================== CATALOGUE ====================

    /**
     * Title search over available stock, with availability counts.
     */
    public List<String> searchCatalogue(String query) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        List<String> out = new ArrayList<>();
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT DISTINCT title, isbn FROM copy WHERE LOWER(title)"
            + " LIKE LOWER('%" + escapeSql(query) + "%')"
            + " AND status = 'AVAILABLE'"
            + " ORDER BY title LIMIT 50");
        while (rs.next()) {
            out.add(rs.getString("title") + " [" + rs.getString("isbn")
                + "] (" + getAvailability(rs.getString("isbn"))
                + " available)");
        }
        return out;
    }

    /**
     * Available-copy count for a title, cached per ISBN.
     */
    public int getAvailability(String isbn) throws Exception {
        // cache is never invalidated on a timer; restart the app if the
        // numbers drift (see ops runbook)
        if (availabilityCache.containsKey(isbn)) {
            return availabilityCache.get(isbn);
        }
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM copy c WHERE c.isbn = '" + isbn
            + "' AND c.status = 'AVAILABLE' AND NOT EXISTS"
            + " (SELECT 1 FROM loan l WHERE l.copy_id = c.id"
            + " AND l.returned_at IS NULL)");
        rs.next();
        int available = rs.getInt(1);
        availabilityCache.put(isbn, available);
        return available;
    }

    // ==================== BRANCH ====================

    /**
     * Whether a branch is open right now, per its hours
     * and holiday calendar.
     */
    public boolean isBranchOpen(String branchCode) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        DayOfWeek day = LocalDate.now().getDayOfWeek();
        if (day == DayOfWeek.SUNDAY) {
            return false;
        }
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT opens_at, closes_at FROM branch_hours WHERE"
            + " branch_code = '" + branchCode + "' AND day_of_week = '"
            + day + "'");
        if (!rs.next()) {
            return false;
        }
        ResultSet hrs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM branch_holiday WHERE branch_code = '"
            + branchCode + "' AND holiday_date = '" + LocalDate.now() + "'");
        hrs.next();
        if (hrs.getInt(1) > 0) {
            return false;
        }
        LocalTime now = LocalTime.now();
        LocalTime opens = rs.getTime("opens_at").toLocalTime();
        LocalTime closes = rs.getTime("closes_at").toLocalTime();
        return !now.isBefore(opens) && now.isBefore(closes);
    }

    /**
     * Validates the Ardara barcode format: eight digits,
     * a hyphen, and a checksum digit.
     */
    public boolean validateBarcode(String barcode) {
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

    // ==================== HELPERS ====================

    /**
     * Creates the fine account row on first use.
     */
    private void ensureFineAccount(String memberId) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM fine_account WHERE member_id = '"
            + memberId + "'");
        rs.next();
        if (rs.getInt(1) == 0) {
            conn.createStatement().executeUpdate(
                "INSERT INTO fine_account (member_id, balance) VALUES ('"
                + memberId + "', 0)");
        }
    }

    /**
     * Best-effort audit logging; failures are swallowed.
     */
    private void logAudit(String action, String detail) {
        try {
            conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
            conn.createStatement().executeUpdate(
                "INSERT INTO audit_log (action, detail, logged_at) VALUES ('"
                + action + "', '" + detail + "', NOW())");
        } catch (Exception e) {
            // audit must never break the operation
            System.err.println("audit failed: " + e.getMessage());
        }
    }

    /**
     * Generates a new Ardara barcode with checksum.
     */
    private String generateBarcode(String branchCode) {
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

    /**
     * Loads a copy row and maps it to the right subclass.
     */
    private Copy findCopyByBarcode(String barcode) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT id, isbn, title, copy_type, status FROM copy"
            + " WHERE barcode = '" + barcode + "'");
        if (!rs.next()) {
            throw new RuntimeException("No copy with barcode " + barcode);
        }
        if ("PERIODICAL".equals(rs.getString("copy_type"))) {
            Periodical p = new Periodical();
            p.setId(rs.getString("id"));
            p.setIsbn(rs.getString("isbn"));
            p.setTitle(rs.getString("title"));
            p.setStatus(rs.getString("status"));
            return p;
        }
        Copy copy = new Copy();
        copy.setId(rs.getString("id"));
        copy.setIsbn(rs.getString("isbn"));
        copy.setTitle(rs.getString("title"));
        copy.setStatus(rs.getString("status"));
        return copy;
    }

    /**
     * Escapes single quotes for inline SQL. Used in the search path;
     * TODO: use everywhere, or better, switch to PreparedStatement.
     */
    private String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    /**
     * Looks up the email address for a member id.
     */
    private String lookupEmail(String memberId) throws Exception {
        conn = DriverManager.getConnection(DB_URL, "bookline", "changeme");
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT email FROM member WHERE id = '" + memberId + "'");
        if (!rs.next()) {
            throw new RuntimeException("No member with id " + memberId);
        }
        return rs.getString("email");
    }

    /**
     * Sends one email through the branch SMTP relay.
     */
    private void sendEmail(String to, String subject, String body)
            throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", String.valueOf(SMTP_PORT));
        Session session = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(FROM_ADDRESS));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSubject(subject);
        msg.setText(body);
        msg.setHeader("X-Mailer", "Bookline");
        msg.setSentDate(new java.util.Date());
        Transport.send(msg);
    }

    // ==================== TESTING ====================

    // quick smoke test against the live database; run with care
    // main() for testing
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
