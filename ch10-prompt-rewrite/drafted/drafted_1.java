public class LibraryManager {

    private Connection conn;
    private static final double DAILY_FINE_RATE = 0.25;

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
        // ... 860 further lines: inline SQL, inline SMTP,
        //     seven fine-rate literals, a main() "for testing" ...
    }
}
