Here's a working search endpoint. The one thing I did *not* cut for the demo is parameter binding — the filters are optional, so the SQL is assembled dynamically, but every user-supplied value goes in as a `?` placeholder with the value pushed onto an args list. Building the `WHERE` clause by string concatenation of user input is how you end up with a demo that also drops your `books` table.

## Model

```java
package com.example.catalogue;

public record Book(
        long id,
        String title,
        String author,
        String isbn,
        String branchCode,
        boolean available
) {}
```

## Row mapper

```java
package com.example.catalogue;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

public class BookRowMapper implements RowMapper<Book> {

    @Override
    public Book mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Book(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("author"),
                rs.getString("isbn"),
                rs.getString("branch_code"),
                rs.getBoolean("available")
        );
    }
}
```

## Repository

```java
package com.example.catalogue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class BookSearchRepository {

    private static final String BASE_SQL = """
            SELECT id, title, author, isbn, branch_code, available
            FROM books
            """;

    private final JdbcTemplate jdbc;

    public BookSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Searches title OR author for the given term, optionally narrowed to a branch.
     * Both q and branch are optional; either may be null/blank.
     */
    public List<Book> search(String q, String branch, int limit, int offset) {
        StringBuilder sql = new StringBuilder(BASE_SQL);
        List<Object> args = new ArrayList<>();
        List<String> conditions = new ArrayList<>();

        if (hasText(q)) {
            // One bound value per placeholder; the pattern is built in Java, not in SQL.
            String pattern = "%" + escapeLikeWildcards(q.trim()) + "%";
            conditions.add("(LOWER(title) LIKE LOWER(?) ESCAPE '\\\\' "
                    + "OR LOWER(author) LIKE LOWER(?) ESCAPE '\\\\')");
            args.add(pattern);
            args.add(pattern);
        }

        if (hasText(branch)) {
            conditions.add("branch_code = ?");
            args.add(branch.trim());
        }

        if (!conditions.isEmpty()) {
            sql.append("WHERE ").append(String.join(" AND ", conditions)).append('\n');
        }

        // ORDER BY is a fixed literal — never interpolate a client-supplied sort column.
        sql.append("ORDER BY title ASC, id ASC\n");
        sql.append("LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);

        return jdbc.query(sql.toString(), new BookRowMapper(), args.toArray());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * A user typing "100%" or "_" shouldn't get wildcard semantics for free.
     * Pairs with the ESCAPE '\' clause above.
     */
    private static String escapeLikeWildcards(String input) {
        return input.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
```

Note the doubled backslashes in the `ESCAPE '\\'` fragment: that's a Java text block escaping a single backslash into the SQL string. If your driver/dialect is fussy about `ESCAPE` in a text block, the simplest alternative is to drop the `ESCAPE` clause and the `escapeLikeWildcards` call — you lose literal `%`/`_` matching, but injection safety is unaffected because the value is still bound.

## Controller

```java
package com.example.catalogue;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/catalogue")
public class CatalogueController {

    private static final int MAX_LIMIT = 100;

    private final BookSearchRepository repository;

    public CatalogueController(BookSearchRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/search")
    public ResponseEntity<List<Book>> search(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "branch", required = false) String branch,
            @RequestParam(name = "limit", defaultValue = "25") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {

        int safeLimit = Math.clamp(limit, 1, MAX_LIMIT);
        int safeOffset = Math.max(offset, 0);

        return ResponseEntity.ok(repository.search(q, branch, safeLimit, safeOffset));
    }
}
```

`Math.clamp` is Java 21+. On older JDKs: `Math.min(Math.max(limit, 1), MAX_LIMIT)`.

## Usage

```
GET /api/catalogue/search?q=tolkien
GET /api/catalogue/search?q=hobbit&branch=NORTH
GET /api/catalogue/search?branch=NORTH&limit=50&offset=50
```

No params at all returns the first page of everything, which is usually what you want a demo search box to do on load.

## Index worth adding before you demo

If the table is more than a few thousand rows, `LIKE '%...%'` will table-scan and the demo will feel sluggish. Postgres:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_books_title_trgm  ON books USING gin (LOWER(title)  gin_trgm_ops);
CREATE INDEX idx_books_author_trgm ON books USING gin (LOWER(author) gin_trgm_ops);
CREATE INDEX idx_books_branch      ON books (branch_code);
```

MySQL has no trigram index; if you're on MySQL and the table is large, switch the title/author predicate to a `FULLTEXT` index with `MATCH ... AGAINST (? IN BOOLEAN MODE)` — still a bound parameter.

## Two things I'd flag for after the demo

1. **`branch_code` isn't validated against a real branch list.** It's bound, so it's safe, but a typo silently returns zero results rather than a 400. Worth an enum or a lookup check once this is more than a demo.
2. **No total count.** If the UI grows pagination controls, add a `SELECT COUNT(*)` against the same conditions/args rather than reconstructing the predicate separately — otherwise the two drift.

If you later want sorting by a client-chosen column, don't pass the column name through as a parameter (placeholders bind values, not identifiers). Map the client's string to an allow-listed constant:

```java
private static final Map<String, String> SORT_COLUMNS = Map.of(
        "title", "title",
        "author", "author",
        "id", "id");

String column = SORT_COLUMNS.getOrDefault(requestedSort, "title");
```

That way the only text ever concatenated into the SQL is a string you wrote yourself.
