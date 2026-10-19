package columnar.differential;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A query result, normalized so numerics compare by value rather than by class. */
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
        if (v instanceof BigInteger i) {
            // DuckDB returns SUM as HUGEINT, which JDBC hands back as BigInteger.
            try {
                return i.longValueExact();
            } catch (ArithmeticException ignored) {
                return i.doubleValue();
            }
        }
        if (v instanceof BigDecimal d) {
            // Widen only when there is a fraction or it will not fit a long.
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
        // Anything exotic compares by text rather than passing as equal.
        return v.toString();
    }
}
