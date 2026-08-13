@RestController
@RequestMapping("/api/catalogue")
public class CatalogueSearchController {

    private final JdbcTemplate jdbc;

    public CatalogueSearchController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/search")
    public List<TitleSummary> search(@RequestParam String q,
                                     @RequestParam(required = false) String branch) {

        String sql = "SELECT isbn, title, author, format "
                   + "FROM title_search "
                   + "WHERE (title ILIKE '%" + q + "%' "
                   + "OR author ILIKE '%" + q + "%')";

        if (branch != null) {
            sql += " AND home_branch = '" + branch + "'";
        }

        sql += " ORDER BY title LIMIT 50";

        return jdbc.query(sql, this::mapRow);
    }

    private TitleSummary mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TitleSummary(rs.getString("isbn"), rs.getString("title"),
                                rs.getString("author"), rs.getString("format"));
    }
}
