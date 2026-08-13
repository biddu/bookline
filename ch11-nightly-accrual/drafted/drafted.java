@Component
public class NightlyFineJob {

    private static final BigDecimal DAILY_RATE = new BigDecimal("0.30");

    private final LoanRepository loanRepository;
    private final FineAccrualRepository fineAccrualRepository;

    public NightlyFineJob(LoanRepository loanRepository,
                          FineAccrualRepository fineAccrualRepository) {
        this.loanRepository = loanRepository;
        this.fineAccrualRepository = fineAccrualRepository;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void accrueFines() {
        LocalDate today = LocalDate.now();
        List<Loan> overdueLoans = loanRepository
            .findByReturnedAtIsNullAndDueAtBefore(today.atStartOfDay());

        for (Loan loan : overdueLoans) {
            FineAccrual accrual = new FineAccrual(
                loan.getId(), today, DAILY_RATE);
            fineAccrualRepository.save(accrual);
            log.info("Charged fine for loan {}", loan.getId());
        }
        log.info("Nightly fine run complete: {} loans", overdueLoans.size());
    }
}
