/**
 * Determines whether the specified loan is overdue.
 *
 * @param loan  the loan to evaluate
 * @param today the date against which to evaluate the loan
 * @return {@code true} if the loan is overdue; {@code false} otherwise
 */
public static boolean isOverdue(Loan loan, LocalDate today) {
    return today.isAfter(loan.getDueDate());
}
