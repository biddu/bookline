public class Loan {
    public String bookTitle;
    public String memberName;
    public String loanDate;
    public String dueDate;
    public boolean returned;
    public double fine;

    public Loan(String bookTitle, String memberName) {
        this.bookTitle = bookTitle;
        this.memberName = memberName;
        this.loanDate = LocalDate.now().toString();
        this.dueDate = LocalDate.now().plusDays(14).toString();
    }

    public void returnBook() {
        this.returned = true;
    }

    public double calculateFine(int daysLate) {
        this.fine = daysLate * 0.50;
        return fine;
    }
}
