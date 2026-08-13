Here's a `search` method that builds the `WHERE` clause from whichever filters are actually populated, while keeping every user-supplied value in a bound parameter.

```java
package com.example.catalogue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Repository
public class CatalogueRepository {

    private static final String BASE_SQL = """
            SELECT id, sku, title, description, branch_code, price_cents, in_stock
            FROM catalogue_item
            """;

    private static final RowMapper<CatalogueItem> ROW_MAPPER = CatalogueRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public CatalogueRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Finds catalogue items matching a free-text term, optionally restricted to one branch.
     *
     * @param searchTerm free-text fragment matched against SKU, title and description;
     *                   null or blank means "no text filter"
     * @param branchCode optional branch restriction; null or blank means "all branches"
     */
    public List<CatalogueItem> search(String searchTerm, String branchCode) {
        StringBuilder sql = new StringBuilder(BASE_SQL);
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (hasText(searchTerm)) {
            // Three placeholders, three bound copies of the same pattern.
            conditions.add("""
                    (LOWER(sku)         LIKE ? ESCAPE '\\'
                      OR LOWER(title)       LIKE ? ESCAPE '\\'
                      OR LOWER(description) LIKE ? ESCAPE '\\')""");
            String pattern = "%" + escapeLikeWildcards(searchTerm.trim().toLowerCase()) + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }

        if (hasText(branchCode)) {
            conditions.add("branch_code = ?");
            args.add(branchCode.trim());
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join("\n  AND ", conditions));
        }

        sql.append("\nORDER BY title ASC, id ASC");

        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    /** Convenience overload for the common "search everywhere" case. */
    public List<CatalogueItem> search(String searchTerm) {
        return search(searchTerm, null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Neutralises LIKE metacharacters so a user typing "50%" or "foo_bar"
     * gets a literal match instead of a wildcard search.
     */
    private static String escapeLikeWildcards(String value) {
        return value.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
    }

    private static CatalogueItem mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CatalogueItem(
                rs.getLong("id"),
                rs.getString("sku"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("branch_code"),
                rs.getLong("price_cents"),
                rs.getBoolean("in_stock"));
    }
}
```

And the row type, if you don't already have one:

```java
package com.example.catalogue;

public record CatalogueItem(
        Long id,
        String sku,
        String title,
        String description,
        String branchCode,
        long priceCents,
        boolean inStock) {
}
```

A few notes on the shape of it:

**What gets concatenated vs. what gets bound.** The only strings appended to the SQL are fixed fragments written in this file — `branch_code = ?`, the `LIKE` clause, `ORDER BY`. Every value that originates with the caller goes into `args` and travels to the driver as a bound parameter, so it is never parsed as SQL. That ordering matters: each time you add a condition containing `?`, add the matching value to `args` in the same block, so the two lists can't drift out of sync.

**No `WHERE 1=1` trick.** Collecting conditions in a list and joining with `AND` avoids the dummy predicate and keeps the generated SQL readable in logs.

**Named parameters if you prefer.** With more than a couple of filters, positional `?` gets brittle. Swapping in `NamedParameterJdbcTemplate` keeps the same structure but removes the ordering coupling:

```java
MapSqlParameterSource params = new MapSqlParameterSource();
if (hasText(searchTerm)) {
    conditions.add("(LOWER(sku) LIKE :term OR LOWER(title) LIKE :term OR LOWER(description) LIKE :term)");
    params.addValue("term", "%" + escapeLikeWildcards(searchTerm.trim().toLowerCase()) + "%");
}
if (hasText(branchCode)) {
    conditions.add("branch_code = :branchCode");
    params.addValue("branchCode", branchCode.trim());
}
// ...
return namedJdbc.query(sql.toString(), params, ROW_MAPPER);
```

**Statement cache.** Because the SQL text varies only with *which* filters are present (not with their values), you get at most a handful of distinct statements — four here — and the database can cache plans for each.

**If you later add a sort-by parameter,** that one genuinely can't be bound; column names aren't parameterisable. Validate it against an allow-list of permitted column names and map to a fixed fragment rather than interpolating whatever the caller sent.

A couple of tests worth having:

```java
@Test
void findsByTitleFragmentWithinBranch() {
    List<CatalogueItem> results = repository.search("lamp", "BR-02");
    assertThat(results).extracting(CatalogueItem::sku).containsExactly("LMP-100");
}

@Test
void blankBranchCodeSearchesAllBranches() {
    assertThat(repository.search("lamp", "  ")).hasSize(3);
}

@Test
void treatsQuotesAndWildcardsAsLiteralText() {
    // Neither should alter the query's structure or widen the match.
    assertThat(repository.search("'; DROP TABLE catalogue_item; --")).isEmpty();
    assertThat(repository.search("%")).isEmpty();
    assertThat(repository.search("lamp")).isNotEmpty(); // table still there
}
```
