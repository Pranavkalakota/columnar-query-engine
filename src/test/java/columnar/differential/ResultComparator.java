package columnar.differential;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compares an engine result against the oracle's result.
 *
 * <p>The rules: results compare unordered unless the query has an
 * {@code ORDER BY}, doubles compare with a relative tolerance, {@code NULL}
 * equals {@code NULL}, and numerics compare by value rather than by class.
 *
 * <p>The unordered case is the interesting one. Sorting both sides first does
 * not work, because a tolerance is not a total order: two rows that should
 * match can sort apart. So this does multiset matching instead, bucketing rows
 * by their exactly-comparable columns and scanning linearly only inside a
 * bucket. A column counts as exact when no {@code Double} appears in it on
 * either side, which keeps a column that is integral on one side and
 * approximate on the other out of the key.
 */
public final class ResultComparator {

    public static final double DEFAULT_TOLERANCE = 1e-9;

    private final double tolerance;

    public ResultComparator() {
        this(DEFAULT_TOLERANCE);
    }

    public ResultComparator(double tolerance) {
        this.tolerance = tolerance;
    }

    /** Returns empty when the results match, or a description of the first difference. */
    public Optional<String> compare(ResultTable expected, ResultTable actual, boolean ordered) {
        if (expected.columnCount() != actual.columnCount()) {
            return Optional.of("column count: expected %d, got %d"
                    .formatted(expected.columnCount(), actual.columnCount()));
        }
        if (expected.rowCount() != actual.rowCount()) {
            return Optional.of("row count: expected %d, got %d"
                    .formatted(expected.rowCount(), actual.rowCount()));
        }
        return ordered ? compareOrdered(expected, actual) : compareUnordered(expected, actual);
    }

    private Optional<String> compareOrdered(ResultTable expected, ResultTable actual) {
        for (int i = 0; i < expected.rowCount(); i++) {
            List<Object> e = expected.rows().get(i);
            List<Object> a = actual.rows().get(i);
            if (!rowsEqual(e, a)) {
                return Optional.of("row %d: expected %s, got %s".formatted(i, e, a));
            }
        }
        return Optional.empty();
    }

    private Optional<String> compareUnordered(ResultTable expected, ResultTable actual) {
        boolean[] exact = exactColumns(expected, actual);
        Map<List<Object>, List<List<Object>>> buckets = new HashMap<>();
        for (List<Object> row : actual.rows()) {
            buckets.computeIfAbsent(key(row, exact), k -> new ArrayList<>()).add(row);
        }
        for (List<Object> e : expected.rows()) {
            List<List<Object>> candidates = buckets.get(key(e, exact));
            boolean matched = false;
            if (candidates != null) {
                for (int i = 0; i < candidates.size(); i++) {
                    if (rowsEqual(e, candidates.get(i))) {
                        candidates.remove(i);
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                return Optional.of("no row in the result matches expected " + e);
            }
        }
        return Optional.empty();
    }

    /** A column is exact when neither side ever holds an approximate value in it. */
    private static boolean[] exactColumns(ResultTable expected, ResultTable actual) {
        boolean[] exact = new boolean[expected.columnCount()];
        java.util.Arrays.fill(exact, true);
        for (ResultTable t : List.of(expected, actual)) {
            for (List<Object> row : t.rows()) {
                for (int c = 0; c < exact.length; c++) {
                    if (row.get(c) instanceof Double) {
                        exact[c] = false;
                    }
                }
            }
        }
        return exact;
    }

    private static List<Object> key(List<Object> row, boolean[] exact) {
        List<Object> k = new ArrayList<>();
        for (int c = 0; c < exact.length; c++) {
            if (exact[c]) {
                k.add(row.get(c));
            }
        }
        return k;
    }

    boolean rowsEqual(List<Object> a, List<Object> b) {
        for (int c = 0; c < a.size(); c++) {
            if (!valuesEqual(a.get(c), b.get(c))) {
                return false;
            }
        }
        return true;
    }

    boolean valuesEqual(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            if (a instanceof Long && b instanceof Long) {
                return na.longValue() == nb.longValue();
            }
            return doublesEqual(na.doubleValue(), nb.doubleValue());
        }
        return a.equals(b);
    }

    /**
     * Relative comparison. NaN matches NaN, and the {@code ==} check
     * short-circuits exact equality and matching infinities.
     */
    private boolean doublesEqual(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return Double.isNaN(a) && Double.isNaN(b);
        }
        if (a == b) {
            return true;
        }
        double scale = Math.max(Math.abs(a), Math.abs(b));
        return Math.abs(a - b) <= tolerance * scale;
    }
}
