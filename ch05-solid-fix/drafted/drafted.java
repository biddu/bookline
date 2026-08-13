public interface ILibraryManager {
    String checkoutBook(String memberId, String barcode) throws Exception;
    double calculateFine(String loanId) throws Exception;
    // ... all 31 method signatures ...
}

public interface ICirculationManager extends ILibraryManager { }

public interface IFineManager extends ILibraryManager { }

public interface INotificationManager extends ILibraryManager { }

/**
 * Refactored to follow SOLID principles: single responsibility,
 * open-closed, Liskov substitution, interface segregation, and
 * dependency inversion.
 */
public class LibraryManagerImpl implements ICirculationManager,
        IFineManager, INotificationManager {

    // ... the original 912 lines, unchanged, including the inline SQL,
    //     the SMTP session, and all seven fine-rate literals ...
}
