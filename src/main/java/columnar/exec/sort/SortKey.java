package columnar.exec.sort;

/** One ORDER BY column; NULLs sort last, as in DuckDB. */
public record SortKey(String column, boolean descending) {

    public SortKey {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("a sort key needs a column");
        }
    }

    public static SortKey asc(String column) {
        return new SortKey(column, false);
    }

    public static SortKey desc(String column) {
        return new SortKey(column, true);
    }

    @Override
    public String toString() {
        return column + (descending ? " DESC" : "");
    }
}
