public class Loan {
    private String copyBarcode;
    private String membershipNumber;
    private LocalDate checkedOutAt;
    private LocalDate dueAt;
    private LocalDate returnedAt;
    private int renewalCount;

    public Loan(String copyBarcode, String membershipNumber) {
        this.copyBarcode = copyBarcode;
        this.membershipNumber = membershipNumber;
        this.checkedOutAt = LocalDate.now();
        this.dueAt = this.checkedOutAt.plusDays(14);
        this.renewalCount = 0;
    }

    public String getCopyBarcode() { return copyBarcode; }
    public void setCopyBarcode(String copyBarcode) { this.copyBarcode = copyBarcode; }
    public String getMembershipNumber() { return membershipNumber; }
    public void setMembershipNumber(String m) { this.membershipNumber = m; }
    public LocalDate getCheckedOutAt() { return checkedOutAt; }
    public void setCheckedOutAt(LocalDate d) { this.checkedOutAt = d; }
    public LocalDate getDueAt() { return dueAt; }
    public void setDueAt(LocalDate dueAt) { this.dueAt = dueAt; }
    public LocalDate getReturnedAt() { return returnedAt; }
    public void setReturnedAt(LocalDate returnedAt) { this.returnedAt = returnedAt; }
    public int getRenewalCount() { return renewalCount; }
    public void setRenewalCount(int renewalCount) { this.renewalCount = renewalCount; }
}
