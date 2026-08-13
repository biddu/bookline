/**
 * Renews an existing loan for a member.
 *
 * <p>A renewal extends the due date by the standard loan period,
 * provided the loan belongs to the requesting member, the renewal
 * limit has not been reached, and the item is not on hold for
 * another member.</p>
 *
 * @param barcode          the barcode of the copy on loan
 * @param membershipNumber the member requesting the renewal
 * @return the result of the renewal attempt
 */
public RenewalResult renewLoan(String barcode, String membershipNumber) {
    Loan loan = loanRepository.findOpenLoanByBarcode(barcode)
            .orElseThrow(() -> new LoanNotFoundException(
                    "No open loan found for barcode: " + barcode));

    // A loan may only be renewed by the member who holds it.
    if (!loan.getMembershipNumber().equals(membershipNumber)) {
        return RenewalResult.refused("Loan does not belong to this member.");
    }

    // Renewals are limited so that items keep circulating.
    if (loan.getRenewalCount() >= MAX_RENEWALS) {
        return RenewalResult.refused("Renewal limit reached.");
    }

    // A renewal must be refused while the item is on hold.
    List<Hold> activeHolds =
            holdRepository.findActiveHoldsByCopyId(loan.getCopyId());
    if (!activeHolds.isEmpty()) {
        return RenewalResult.refused("Item is reserved for another member.");
    }

    // Extend the due date by the standard loan period.
    LocalDate newDueDate = loan.getDueDate().plusDays(STANDARD_LOAN_PERIOD_DAYS);
    loan.setDueDate(newDueDate);
    loan.incrementRenewalCount();
    loanRepository.save(loan);

    return RenewalResult.renewed(newDueDate);
}
