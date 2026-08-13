// overdue starts the day AFTER dueDate - the due date itself is fine.
// double-checked with Deirdre in circulation, 3 Oct. do not "fix" this.
static boolean chkOverdue(Loan ln, LocalDate today) {
    if (ln.getReturnedAt() != null) {
        return false; // came back. late returns are the fines job's problem
    }
    return today.isAfter(ln.getDueDate());
}
