/**
 * Assigns a returned copy to the next waiting hold for its title,
 * or returns it to the open shelves if no holds are waiting.
 *
 * <p>Holds are satisfied strictly in the order in which they were
 * placed, so that the queue is fair: the member who has waited
 * longest is always served first. The assignment is transactional,
 * and the member is notified that the item is ready for collection.</p>
 *
 * @param barcode the barcode of the returned copy
 * @return the outcome of the assignment
 */
@Transactional
public HoldAssignmentResult assignReturnedCopy(Barcode barcode) {
    Copy copy = copyRepository.findByBarcode(barcode)
            .orElseThrow(() -> new CopyNotFoundException(barcode));

    List<Hold> waiting = holdRepository
            .findByTitleAndState(copy.getTitleIsbn(), HoldState.WAITING);

    if (waiting.isEmpty()) {
        copy.setStatus(CopyStatus.AVAILABLE);
        copyRepository.save(copy);
        return HoldAssignmentResult.shelved(barcode);
    }

    // Fairness: the longest-waiting member is served first.
    Hold next = waiting.stream()
            .sorted(Comparator.comparing(Hold::getPlacedAt))
            .findFirst()
            .orElseThrow();

    next.setState(HoldState.READY_FOR_PICKUP);
    next.setReadyAt(Instant.now());
    copy.setStatus(CopyStatus.ON_HOLD_SHELF);

    holdRepository.save(next);
    copyRepository.save(copy);
    notificationService.holdReady(next, copy);

    return HoldAssignmentResult.assigned(next.getId(), barcode);
}
