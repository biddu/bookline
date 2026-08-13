// drafted illustrative code, written to contain the exact defects the prose dissects
public class FineCalculationStrategy {

    public Money calculateFine(Loan loan, int overdueDays) {
        BigDecimal rate = new BigDecimal("0.30");
        BigDecimal amount = rate.multiply(BigDecimal.valueOf(overdueDays));
        return new Money(amount, Currency.getInstance("EUR"));
    }
}

public class FineService {

    private final FineCalculationStrategy strategy = new FineCalculationStrategy();

    public Money assessFine(Loan loan, int overdueDays) {
        return strategy.calculateFine(loan, overdueDays);
    }
}
