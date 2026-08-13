// drafted illustrative code, written to contain the exact defects the prose dissects
public class FineCalculator {

    private static FineCalculator instance;

    private final LibraryCalendar calendar;
    private final FineAccrualRepository repository;

    private FineCalculator() {
        this.calendar = new LibraryCalendar();
        this.repository = new FineAccrualRepository();
    }

    public static FineCalculator getInstance() {
        if (instance == null) {
            instance = new FineCalculator();
        }
        return instance;
    }

    public void accrueDailyFines(LocalDate date) {
        for (Loan loan : repository.overdueLoansOn(date)) {
            if (calendar.isOpen(loan.copy().homeBranch(), date)) {
                repository.save(new FineAccrual(loan.id(), date, dailyAmountFor(loan)));
            }
        }
    }
}
