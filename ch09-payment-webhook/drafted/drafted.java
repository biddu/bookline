@PostMapping("/webhooks/payments")
public ResponseEntity<Void> handle(@RequestBody GatewayEvent event) {

    Payment payment = new Payment(
        new MembershipNumber(event.memberRef()),
        Money.euro(event.amount()),
        event.reference());

    paymentRepository.save(payment);
    memberAccount.reduceBalance(event.memberRef(), Money.euro(event.amount()));

    return ResponseEntity.ok().build();
}
