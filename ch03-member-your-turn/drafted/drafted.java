public class Member {

    private static int nextMemberNumber = 100001;

    public String memberNumber;
    public String name;
    public String email;
    public String tier;
    private boolean consentToHistory;
    private double outstandingBalance;
    private final List<Loan> currentLoans = new ArrayList<>();

    public Member(String name, String email) {
        this.memberNumber = String.valueOf(nextMemberNumber++);
        this.name = name;
        this.email = email;
        this.tier = "STANDARD";
        this.consentToHistory = true;
    }

    public List<Loan> getCurrentLoans() { return currentLoans; }
    public double getOutstandingBalance() { return outstandingBalance; }
    public void setOutstandingBalance(double b) { this.outstandingBalance = b; }
    public boolean hasConsented() { return consentToHistory; }
    public void setConsentToHistory(boolean c) { this.consentToHistory = c; }
}
