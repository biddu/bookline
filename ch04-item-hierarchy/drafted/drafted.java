// drafted illustrative code, written to contain the exact defects the prose dissects

public abstract class LibraryItem {
    protected Long id;
    protected String title;
    protected LocalDate addedAt;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}

public abstract class LendableItem extends LibraryItem {
    protected boolean checkedOut;
    protected String borrowerId;
    protected LocalDate dueDate;

    public void checkOut(String borrowerId) {
        if (checkedOut) {
            throw new IllegalStateException("Item is already checked out");
        }
        this.checkedOut = true;
        this.borrowerId = borrowerId;
        this.dueDate = LocalDate.now().plusDays(21);
    }

    public void checkIn() {
        this.checkedOut = false;
        this.borrowerId = null;
        this.dueDate = null;
    }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
}

public abstract class PrintedItem extends LendableItem {
    protected String publisher;
    protected int pageCount;

    public int getPageCount() { return pageCount; }
}

public class Book extends PrintedItem {
    private String isbn;
    private String author;
    private String barcode;
    private String shelfLocation;
}

public class ReferenceItem extends Book {
    @Override
    public void checkOut(String borrowerId) {
        throw new UnsupportedOperationException(
            "Reference items cannot be checked out");
    }
}

public class AudioBook extends Book {
    private int runtimeMinutes;
    private String narrator;

    @Override
    public int getPageCount() {
        return 0; // audiobooks do not have pages
    }
}

public abstract class MediaItem extends LendableItem {
    protected int runtimeMinutes;
}

public class Dvd extends MediaItem {
    private String region;
    private String rating;
}

public class Periodical extends PrintedItem {
    private String issn;
    private int issueNumber;
}
