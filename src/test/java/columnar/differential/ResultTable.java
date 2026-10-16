package columnar.differential;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A materialized query result, normalized so that two results can be compared
 * without caring which Java class each side happened to produce.
 *
 * <p>Normalization matters because the oracle and the engine disagree on
 * representation all the time: JDBC hands back {@code Integer} where the
 * engine has a {@code long}, and {@code BigDecimal} where the engine has a
 * {@code double}. Numerics must compare by value, not by class, so every
 * integral type collapses to {@link Long} and every approximate type to
 * {@link Double} on the way in.
 *
 * <p>A {@code BigDecimal} with no fractional part that fits in a long stays
 * integral rather than becoming a double, so that large {@code SUM} results
 * are compared exactly instead of through 53 bits of mantissa.
 */
public record ResultTable(List<String> columnNames, List<List<Object>> rows) {

    public ResultTable {
        columnNames = List.copyOf(columnNames);
        rows = List.copyOf(rows);
    }

    public int columnCount() {
        return columnNames.size();
    }

    public int rowCount() {
        return rows.size();
    }

    /** Drains a JDBC result set, normalizing every value. */
    public static ResultTable of(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int n = meta.getColumnCount();
        List<String> names = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            names.add(meta.getColumnLabel(i));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next()) {
            List<Object> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                row.add(normalize(rs.getObject(i)));
            }
            rows.add(row);
        }
        return new ResultTable(names, rows);
    }

    /** Convenience factory for tests and for engine-side results. */
    public static ResultTable of(List<String> columnNames, List<List<Object>> rows) {
        List<List<Object>> normalized = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            List<Object> out = new ArrayList<>(row.size());
            for (Object v : row) {
                out.add(normalize(v));
            }
            normalized.add(out);
        }
        return new ResultTable(columnNames, normalized);
    }

    static Object normalize(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal d) {
            // Keep exact integers exact; only widen to double when there is a
            // fractional part or the value cannot survive a long.
            if (d.scale() <= 0) {
                try {
                    return d.longValueExact();
                } catch (ArithmeticException ignored) {
                    return d.doubleValue();
                }
            }
            return d.doubleValue();
        }
        if (v instanceof Float f) {
            return f.doubleValue();
        }
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof Number nb
                && (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte)) {
            return nb.longValue();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof LocalDate || v instanceof Boolean || v instanceof String) {
            return v;
        }
        // Anything exotic compares by its textual form rather than silently
        // passing as equal.
        return v.toString();
    }
}
