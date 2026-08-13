import java.sql.*;
import javax.mail.*;
import javax.mail.internet.*;
// ... 14 further imports ...

public class LibraryManager {

    private Connection conn;
    private static final String DB_URL =
        "jdbc:postgresql://localhost:5432/bookline";
    private static final String SMTP_HOST = "mail.ardaralibraries.ie";
    private static final double DAILY_FINE_RATE = 0.25;
    private static final double PERIODICAL_FINE_RATE = 0.50;
    // ... 5 further fields: SMTP port, sender address, two date
    //     formatters, a HashMap<String, Integer> availability cache ...

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
        Copy copy = findCopyByBarcode(barcode);
        if ("REFERENCE".equals(copy.getStatus())) {
            throw new RuntimeException("Reference stock cannot be borrowed");
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
            + (copy instanceof Periodical ? 0.50 : 0.25) + " per day.");
        return "OK";
    }

    // ... returnBook, placeHold, cancelHold, renewLoan, registerMember,
    //     updateMemberEmail ... (11 methods elided) ...

    public double calculateFine(String loanId) throws Exception {
        // ... loads the loan with another inline query, then:
        long daysOverdue = ChronoUnit.DAYS.between(dueAt, LocalDate.now());
        if (copyType.equals("PERIODICAL")) {
            return daysOverdue * 0.50;
        }
        return daysOverdue * 0.25;
    }

    public void sendOverdueNotices() throws Exception {
        // ... selects overdue loans, then for each:
        double owed = daysLate * 0.25;
        sendEmail(email, "Overdue notice", "You owe EUR " + owed
            + ". The daily rate is EUR 0.25.");
    }

    private void sendEmail(String to, String subject, String body)
            throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", SMTP_HOST);
        Session session = Session.getInstance(props);
        // ... 16 lines of MimeMessage construction ...
        Transport.send(msg);
    }

    public String generateOverdueReport(String branchCode) throws Exception {
        StringBuilder sb = new StringBuilder("OVERDUE REPORT\n");
        // ... query, loop, sb.append(daysLate * 0.25), totals ...
        return sb.toString();
    }

    // ... 12 further methods: hold expiry, barcode validation,
    //     opening-hours lookup, CSV export, a main() "for testing" ...
}
