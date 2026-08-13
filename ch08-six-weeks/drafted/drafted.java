public RenewalResult renewLoan(String barcode, String membershipNumber,
        boolean staffOverride, boolean suppressNotification) {

    Loan loan = loanRepository.findOpenLoanByBarcode(barcode)
            .orElseThrow(() -> new LoanNotFoundException(
                    "No open loan found for barcode: " + barcode));

    Member member = memberRepository.findByMembershipNumber(membershipNumber)
            .orElseThrow(() -> new MemberNotFoundException(membershipNumber));

    if (!loan.getMembershipNumber().equals(membershipNumber) && !staffOverride) {
        return RenewalResult.refused("Loan does not belong to this member.");
    }

    // Reference and periodical stock is not renewable.
    Copy copy = copyRepository.findByBarcode(barcode)
            .orElseThrow(() -> new CopyNotFoundException(barcode));
    if (copy instanceof ReferenceCopy) {
        return RenewalResult.refused("Reference items cannot be renewed.");
    } else if (copy instanceof PeriodicalCopy) {
        PeriodicalCopy periodical = (PeriodicalCopy) copy;
        if (periodical.isCurrentIssue()) {
            return RenewalResult.refused("Current issues cannot be renewed.");
        }
    }

    // Renewal limits vary by membership tier.
    int maxRenewals;
    if (member.getTier() == MembershipTier.STUDENT) {
        maxRenewals = 3;
    } else if (member.getTier() == MembershipTier.CONCESSION) {
        maxRenewals = 3;
    } else {
        maxRenewals = MAX_RENEWALS;
    }
    if (loan.getRenewalCount() >= maxRenewals && !staffOverride) {
        if (!suppressNotification) {
            try {
                notificationService.sendRenewalRefused(member, loan,
                        "Renewal limit reached.");
            } catch (NotificationException e) {
                log.warn("Failed to notify member {}", membershipNumber, e);
            }
        }
        return RenewalResult.refused("Renewal limit reached.");
    }

    // Members with large outstanding fines may not renew.
    Money balance = fineService.outstandingBalanceFor(membershipNumber);
    if (balance.isGreaterThan(FINE_BLOCK_THRESHOLD)) {
        if (member.getTier() != MembershipTier.CONCESSION) {
            return RenewalResult.refused("Outstanding fines exceed the limit.");
        }
    }

    // A renewal must be refused while the item is on hold.
    if (!staffOverride) {
        List<Hold> activeHolds =
                holdRepository.findActiveHoldsByCopyId(loan.getCopyId());
        if (!activeHolds.isEmpty()) {
            return RenewalResult.refused("Item is reserved for another member.");
        }
    }

    // Overdue loans may still be renewed within the grace window.
    LocalDate today = LocalDate.now();
    if (loan.getDueDate().isBefore(today)) {
        long daysOverdue = ChronoUnit.DAYS.between(loan.getDueDate(), today);
        if (daysOverdue > OVERDUE_RENEWAL_GRACE_DAYS && !staffOverride) {
            return RenewalResult.refused(
                    "Loan is too far overdue to renew. Please return the item.");
        }
    }

    // Extend from today for overdue loans, from the due date otherwise
    // (changed after branch feedback, see ticket BKL-214).
    int period;
    if (member.getTier() == MembershipTier.STUDENT) {
        period = STANDARD_LOAN_PERIOD_DAYS;
    } else {
        period = STANDARD_LOAN_PERIOD_DAYS;
    }
    LocalDate base = loan.getDueDate().isBefore(today) ? today : loan.getDueDate();
    LocalDate newDueDate = base.plusDays(period);

    // Skip forward past days the home branch is closed.
    Branch branch = copy.getHomeBranch();
    while (branch.isClosedOn(newDueDate)) {
        newDueDate = newDueDate.plusDays(1);
    }

    loan.setDueDate(newDueDate);
    loan.incrementRenewalCount();
    loanRepository.save(loan);

    if (!suppressNotification) {
        try {
            notificationService.sendRenewalConfirmed(member, loan, newDueDate);
        } catch (NotificationException e) {
            log.warn("Failed to notify member {}", membershipNumber, e);
        }
    }

    return RenewalResult.renewed(newDueDate);
}
