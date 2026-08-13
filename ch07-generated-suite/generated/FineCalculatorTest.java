package ie.ardaralibraries.bookline.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FineCalculatorTest {

    private final FineCalculator calculator = new FineCalculator();

    private OverdueLoanFacts facts(String dailyRate, String replacementCost) {
        return new OverdueLoanFacts("L-1", LocalDate.of(2026, 1, 5), Optional.empty(),
                new BigDecimal(dailyRate), new BigDecimal(replacementCost));
    }

    @Test
    void fullDailyRateWhenWellUnderCap() {
        BigDecimal increment = calculator.dailyIncrement(facts("0.50", "25.00"), BigDecimal.ZERO);

        assertEquals(0, new BigDecimal("0.50").compareTo(increment));
    }

    @Test
    void incrementClampedToRemainingHeadroom() {
        BigDecimal increment = calculator.dailyIncrement(facts("3.00", "25.00"), new BigDecimal("23.00"));

        assertEquals(0, new BigDecimal("2.00").compareTo(increment));
    }

    @Test
    void exactHeadroomStillChargesFullRate() {
        BigDecimal increment = calculator.dailyIncrement(facts("0.50", "25.00"), new BigDecimal("24.50"));

        assertEquals(0, new BigDecimal("0.50").compareTo(increment));
    }

    @Test
    void zeroWhenAccruedEqualsReplacementCost() {
        BigDecimal increment = calculator.dailyIncrement(facts("0.50", "25.00"), new BigDecimal("25.00"));

        assertEquals(0, BigDecimal.ZERO.compareTo(increment));
    }

    @Test
    void zeroWhenAccruedExceedsReplacementCost() {
        BigDecimal increment = calculator.dailyIncrement(facts("0.50", "25.00"), new BigDecimal("30.00"));

        assertEquals(0, BigDecimal.ZERO.compareTo(increment));
    }
}
