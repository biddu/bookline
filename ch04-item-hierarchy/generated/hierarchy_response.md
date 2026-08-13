Here's a JPA model for the county library's lendable items, targeting Java 21.

## Design decisions up front

**One inheritance hierarchy, one orthogonal flag.** Books, DVDs, audiobooks, and periodicals are genuinely different *kinds* of things — they carry different data (ISBN vs. runtime vs. narrator vs. issue number) — so they get subclasses. But "reference stock" is not a kind of item; it's a *circulation policy* that can apply to any kind. A county library has reference books, reference periodicals (bound back-issues), even reference DVDs (local-history archives). If you modelled `ReferenceItem` as a subclass, you'd need `ReferenceBook`, `ReferencePeriodical`, and so on — a combinatorial explosion. So reference status lives on the base class as a `CirculationPolicy` enum, and the loan-eligibility rule is enforced in one place.

**`SINGLE_TABLE` inheritance.** The subclasses add only a handful of columns each, the hierarchy is queried polymorphically all the time ("what does patron X have out?"), and single-table avoids joins on every catalogue search. The trade-off (nullable subclass columns) is acceptable here.

## The code

```java
// LibraryItem.java
package org.countylib.catalog;

import jakarta.persistence.*;
import java.time.Year;
import java.util.Objects;

@Entity
@Table(name = "library_item")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "item_type", length = 20)
public abstract class LibraryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String barcode;

    @Column(nullable = false)
    private String title;

    @Column(name = "publication_year")
    private Year publicationYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "circulation_policy", nullable = false, length = 20)
    private CirculationPolicy circulationPolicy = CirculationPolicy.LENDABLE;

    protected LibraryItem() { }  // required by JPA

    protected LibraryItem(String barcode, String title, Year publicationYear,
                          CirculationPolicy circulationPolicy) {
        this.barcode = Objects.requireNonNull(barcode);
        this.title = Objects.requireNonNull(title);
        this.publicationYear = publicationYear;
        this.circulationPolicy = Objects.requireNonNull(circulationPolicy);
    }

    /** The single source of truth for "can this leave the building?" */
    public boolean isLendable() {
        return circulationPolicy == CirculationPolicy.LENDABLE;
    }

    /** Standard loan period; subclasses override where policy differs. */
    public int loanPeriodDays() {
        return 21;
    }

    public Long getId() { return id; }
    public String getBarcode() { return barcode; }
    public String getTitle() { return title; }
    public Year getPublicationYear() { return publicationYear; }
    public CirculationPolicy getCirculationPolicy() { return circulationPolicy; }

    public void setCirculationPolicy(CirculationPolicy policy) {
        this.circulationPolicy = Objects.requireNonNull(policy);
    }
}
```

```java
// CirculationPolicy.java
package org.countylib.catalog;

/**
 * Circulation policy is orthogonal to item type: any item —
 * book, periodical, DVD — can be reference-only.
 */
public enum CirculationPolicy {
    /** May be checked out and taken home. */
    LENDABLE,
    /** Reference stock: consult in the building only. */
    REFERENCE_ONLY
}
```

```java
// Book.java
package org.countylib.catalog;

import jakarta.persistence.*;
import java.time.Year;

@Entity
@DiscriminatorValue("BOOK")
public class Book extends LibraryItem {

    @Column(length = 17)
    private String isbn;

    private String author;

    @Column(name = "page_count")
    private Integer pageCount;

    protected Book() { }

    public Book(String barcode, String title, Year publicationYear,
                CirculationPolicy policy, String isbn, String author, Integer pageCount) {
        super(barcode, title, publicationYear, policy);
        this.isbn = isbn;
        this.author = author;
        this.pageCount = pageCount;
    }

    public String getIsbn() { return isbn; }
    public String getAuthor() { return author; }
    public Integer getPageCount() { return pageCount; }
}
```

```java
// Dvd.java
package org.countylib.catalog;

import jakarta.persistence.*;
import java.time.Year;

@Entity
@DiscriminatorValue("DVD")
public class Dvd extends LibraryItem {

    @Column(name = "runtime_minutes")
    private Integer runtimeMinutes;

    @Column(name = "age_rating", length = 10)
    private String ageRating;

    private String director;

    protected Dvd() { }

    public Dvd(String barcode, String title, Year publicationYear,
               CirculationPolicy policy, Integer runtimeMinutes,
               String ageRating, String director) {
        super(barcode, title, publicationYear, policy);
        this.runtimeMinutes = runtimeMinutes;
        this.ageRating = ageRating;
        this.director = director;
    }

    @Override
    public int loanPeriodDays() {
        return 7;  // county policy: AV stock circulates on a shorter loan
    }

    public Integer getRuntimeMinutes() { return runtimeMinutes; }
    public String getAgeRating() { return ageRating; }
    public String getDirector() { return director; }
}
```

```java
// Audiobook.java
package org.countylib.catalog;

import jakarta.persistence.*;
import java.time.Year;

@Entity
@DiscriminatorValue("AUDIOBOOK")
public class Audiobook extends LibraryItem {

    private String author;

    private String narrator;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "audio_format", length = 20)
    private AudioFormat format;

    public enum AudioFormat { CD, MP3_CD, PLAYAWAY }

    protected Audiobook() { }

    public Audiobook(String barcode, String title, Year publicationYear,
                     CirculationPolicy policy, String author, String narrator,
                     Integer durationMinutes, AudioFormat format) {
        super(barcode, title, publicationYear, policy);
        this.author = author;
        this.narrator = narrator;
        this.durationMinutes = durationMinutes;
        this.format = format;
    }

    public String getAuthor() { return author; }
    public String getNarrator() { return narrator; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public AudioFormat getFormat() { return format; }
}
```

```java
// Periodical.java
package org.countylib.catalog;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.Year;

@Entity
@DiscriminatorValue("PERIODICAL")
public class Periodical extends LibraryItem {

    @Column(length = 9)
    private String issn;

    @Column(name = "issue_number")
    private Integer issueNumber;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    protected Periodical() { }

    public Periodical(String barcode, String title, Year publicationYear,
                      CirculationPolicy policy, String issn,
                      Integer issueNumber, LocalDate issueDate) {
        super(barcode, title, publicationYear, policy);
        this.issn = issn;
        this.issueNumber = issueNumber;
        this.issueDate = issueDate;
    }

    @Override
    public int loanPeriodDays() {
        return 7;  // current issues turn over quickly
    }

    public String getIssn() { return issn; }
    public Integer getIssueNumber() { return issueNumber; }
    public LocalDate getIssueDate() { return issueDate; }
}
```

Enforce the reference rule where loans are created, so it can't be bypassed:

```java
// LoanService.java (excerpt)
package org.countylib.circulation;

import org.countylib.catalog.LibraryItem;

public class LoanService {

    public Loan checkOut(LibraryItem item, Patron patron) {
        if (!item.isLendable()) {
            throw new ReferenceOnlyItemException(
                "Item %s ('%s') is reference stock and cannot leave the building"
                    .formatted(item.getBarcode(), item.getTitle()));
        }
        return Loan.open(item, patron, item.loanPeriodDays());
    }
}
```

## Notes

- **Why not a `ReferenceItem` subclass?** JPA entities have exactly one class for their lifetime. If reference status were a subclass, moving a book from the reference shelf into circulation (which libraries do routinely when they buy a second copy) would mean deleting and re-inserting the row with a new discriminator — losing its identity, loan history, and reservations. As an enum field, it's a one-column update.
- **Sealed hierarchy option.** If you want the compiler to guarantee the set of item types is closed, Java 21 lets you declare `public abstract sealed class LibraryItem permits Book, Dvd, Audiobook, Periodical`. Hibernate 6.2+ handles sealed entity classes fine, and you gain exhaustive `switch` pattern matching over item types in service code. I left it unsealed above only because some JPA tooling (older bytecode enhancers) still stumbles on it — add `sealed`/`permits` if your stack is current.
- **Queries stay simple.** "Everything that can go out the door" is `SELECT i FROM LibraryItem i WHERE i.circulationPolicy = LENDABLE` — one query across all five categories, no unions.
