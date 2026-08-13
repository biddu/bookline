// drafted illustrative code, written to contain the exact defects the prose dissects
public class HoldNotificationListener {

    private final EmailNotifier emailNotifier = new EmailNotifier();
    private final SmsNotifier smsNotifier = new SmsNotifier();

    public void onCopyReturned(Copy copy) {
        List<Hold> holds = holdRepository.findByTitle(copy.title().isbn());
        for (Hold hold : holds) {
            emailNotifier.send(hold.member(), "Your hold is ready for collection");
            smsNotifier.send(hold.member(), "Your hold is ready for collection");
        }
    }
}
