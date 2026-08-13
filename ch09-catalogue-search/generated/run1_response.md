Here's a catalogue search endpoint backed by `JdbcTemplate`. The search term matches title or author, and the branch filter is optional — the WHERE clause is assembled conditionally, but every user-supplied value goes in as a bound parameter.

## Model

```java
package com.example.catalogue;

public record CatalogueItem(
        long id,
        String title,
        String author,
        String isbn,
        String branchCode,
        int copiesAvailable
) {}
```

## Repository

```java
package com.example.catalogue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class CatalogueRepository {

    private static final String BASE_SQL = """
            SELECT i.id,
                   i.title,
                   i.author,
                   i.isbn,
                   i.branch_code,
                   i.copies_available
            FROM catalogue_item i
            """;

    private static final RowMapper<CatalogueItem> ROW_MAPPER = (rs, rowNum) -> new CatalogueItem(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("author"),
            rs.getString("isbn"),
            rs.getString("branch_code"),
            rs.getInt("copies_available")
    );

    private final JdbcTemplate jdbcTemplate;

    public CatalogueRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CatalogueItem> search(String term, String branchCode, int limit, int offset) {
        StringBuilder sql = new StringBuilder(BASE_SQL);
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (term != null && !term.isBlank()) {
            conditions.add("(LOWER(i.title) LIKE ? OR LOWER(i.author) LIKE ?)");
            String pattern = "%" + term.trim().toLowerCase() + "%";
            args.add(pattern);
            args.add(pattern);
        }

        if (branchCode != null && !branchCode.isBlank()) {
            conditions.add("i.branch_code = ?");
            args.add(branchCode.trim());
        }

        if (!conditions.isEmpty()) {
            sql.append("WHERE ").append(String.join("\n  AND ", conditions)).append("\n");
        }

        sql.append("ORDER BY i.title, i.author\n");
        sql.append("LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);

        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public int count(String term, String branchCode) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM catalogue_item i\n");
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (term != null && !term.isBlank()) {
            conditions.add("(LOWER(i.title) LIKE ? OR LOWER(i.author) LIKE ?)");
            String pattern = "%" + term.trim().toLowerCase() + "%";
            args.add(pattern);
            args.add(pattern);
        }

        if (branchCode != null && !branchCode.isBlank()) {
            conditions.add("i.branch_code = ?");
            args.add(branchCode.trim());
        }

        if (!conditions.isEmpty()) {
            sql.append("WHERE ").append(String.join("\n  AND ", conditions));
        }

        Integer total = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
        return total == null ? 0 : total;
    }
}
```

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
public class CatalogueSearchController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CatalogueRepository repository;

    public CatalogueSearchController(CatalogueRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "branch", required = false) String branchCode,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        int offset = pageNumber * pageSize;

        List<CatalogueItem> results = repository.search(query, branchCode, pageSize, offset);
        int total = repository.count(query, branchCode);

        return ResponseEntity.ok(new SearchResponse(results, pageNumber, pageSize, total));
    }

    public record SearchResponse(
            List<CatalogueItem> results,
            int page,
            int size,
            int totalResults
    ) {}
}
```

## Usage

```
GET /api/catalogue/search?q=tolkien
GET /api/catalogue/search?q=hobbit&branch=CENTRAL
GET /api/catalogue/search?branch=NORTH&page=2&size=50
```

Both parameters are optional: with neither, you get a paged listing of the whole catalogue; with `branch` alone, everything held at that branch.

A couple of implementation notes:

- The `LIKE` pattern is built in Java and passed as a parameter value, so the `%` wildcards are part of the *value*, never the SQL text. If your data contains literal `%` or `_` characters that users should be able to search for, add an `ESCAPE` clause and escape those characters in the pattern.
- `LOWER(...) LIKE` will not use a plain B-tree index on `title`/`author`. On a large catalogue you'll want a functional index on `LOWER(title)` and `LOWER(author)`, or a full-text index if you need real relevance ranking — a leading-wildcard `LIKE` can't use either, so consider full-text search once the table grows.
- `count()` runs a second query; drop it if you don't need a total, or switch to a `LIMIT size + 1` "has more" check.

---

User input reaches the SQL exclusively via bound `?` parameters — only fixed, developer-written fragments are concatenated into the statement string — and beyond that I added no security material you didn't ask for (the note on `LIKE` escaping is about search correctness, not injection).
