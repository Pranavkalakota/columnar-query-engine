package columnar.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;

import columnar.exec.row.Row;
import columnar.exec.row.RowOperator;
import columnar.exec.row.RowFilter;
import columnar.exec.row.RowScan;
import columnar.exec.row.RowSort;
import columnar.exec.sort.SortKey;
import columnar.exec.vec.VectorFilter;
import columnar.exec.vec.VectorOperator;
import columnar.exec.vec.VectorScan;
import columnar.exec.vec.VectorSort;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.storage.InMemoryTable;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.BooleanVector;
import columnar.vector.DoubleVector;
import columnar.vector.IntVector;
import columnar.vector.LongVector;
import columnar.vector.StringVector;
import columnar.vector.VectorBatch;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.StringJoiner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ORDER BY semantics pinned to DuckDB, not to assumptions. */
class SortSemanticsOracleTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("id", DataType.INT64), Field.of("i", DataType.INT32),
            Field.of("d", DataType.DOUBLE), Field.of("s", DataType.VARCHAR),
            Field.of("dt", DataType.DATE), Field.of("b", DataType.BOOLEAN));

    private static final double[] DOUBLES = {Double.NaN, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, -0.0, 0.0, 1.5, -2.25, 1e300};
    private static final String[] STRINGS = {"", "a", "Z", "ab", "é", "中",
        "～", "😀", "𐍈", "a😀"};
    private static final int ROWS = 300;

    private static DuckDbOracle oracle;
    private static InMemoryTable table;

    @BeforeAll
    static void load() {
        Random random = new Random(20261121L);
        List<VectorBatch> batches = new ArrayList<>();
        StringJoiner values = new StringJoiner(",\n");
        for (int start = 0; start < ROWS; start += 64) {
            int size = Math.min(64, ROWS - start);
            VectorBatch batch = VectorBatch.allocate(SCHEMA, 64);
            for (int r = 0; r < size; r++) {
                values.add(fill(batch, r, start + r, random));
            }
            batch.setRowCount(size);
            batches.add(batch);
        }
        table = InMemoryTable.of(SCHEMA, batches);
        oracle = DuckDbOracle.inMemory();
        oracle.execute("CREATE TABLE t (id BIGINT, i INTEGER, d DOUBLE, s VARCHAR, dt DATE, b BOOLEAN)");
        oracle.execute("INSERT INTO t VALUES " + values);
    }

    @AfterAll
    static void close() {
        oracle.close();
    }

    /** Fills one row, returning it as SQL. */
    private static String fill(VectorBatch batch, int r, long id, Random random) {
        ((LongVector) batch.column(0)).set(r, id);
        StringJoiner sql = new StringJoiner(", ", "(", ")");
        sql.add(Long.toString(id));
        boolean nulls = random.nextInt(6) == 0;
        int i = random.nextInt(5) - 2;
        sql.add(nulls ? "NULL" : Integer.toString(i));
        if (nulls) {
            batch.column(1).setNull(r);
        } else {
            ((IntVector) batch.column(1)).set(r, i);
        }
        double d = DOUBLES[random.nextInt(DOUBLES.length)];
        boolean nullD = random.nextInt(7) == 0;
        sql.add(nullD ? "NULL" : "'" + d + "'::DOUBLE");
        if (nullD) {
            batch.column(2).setNull(r);
        } else {
            ((DoubleVector) batch.column(2)).set(r, d);
        }
        StringVector s = (StringVector) batch.column(3);
        if (random.nextInt(8) == 0) {
            s.appendNull();
            sql.add("NULL");
        } else {
            String value = STRINGS[random.nextInt(STRINGS.length)];
            s.append(value);
            sql.add("'" + value.replace("'", "''") + "'");
        }
        int day = random.nextInt(40_000) - 15_000;
        boolean nullDate = random.nextInt(9) == 0;
        sql.add(nullDate ? "NULL" : "DATE '" + LocalDate.ofEpochDay(day) + "'");
        if (nullDate) {
            batch.column(4).setNull(r);
        } else {
            ((IntVector) batch.column(4)).set(r, day);
        }
        boolean flag = random.nextBoolean();
        boolean nullB = random.nextInt(5) == 0;
        sql.add(nullB ? "NULL" : Boolean.toString(flag).toUpperCase());
        if (nullB) {
            batch.column(5).setNull(r);
        } else {
            ((BooleanVector) batch.column(5)).set(r, flag);
        }
        return sql.toString();
    }

    private static List<Long> duckIds(List<SortKey> keys, String where) {
        StringJoiner order = new StringJoiner(", ");
        keys.forEach(k -> order.add(k.column() + (k.descending() ? " DESC" : "")));
        String sql = "SELECT id FROM t" + (where == null ? "" : " WHERE " + where)
                + " ORDER BY " + order + ", id";
        List<Long> ids = new ArrayList<>();
        oracle.query(sql).rows().forEach(row -> ids.add((Long) row.get(0)));
        return ids;
    }

    private static List<SortKey> withTiebreak(List<SortKey> keys) {
        List<SortKey> all = new ArrayList<>(keys);
        all.add(SortKey.asc("id"));
        return all;
    }

    private static List<Long> rowIds(List<SortKey> keys, Expr where) {
        RowOperator source = where == null ? new RowScan(table) : new RowFilter(new RowScan(table), where);
        List<Long> ids = new ArrayList<>();
        try (RowSort sort = new RowSort(source, withTiebreak(keys))) {
            sort.open();
            Row row;
            while ((row = sort.next()) != null) {
                ids.add(row.getLong(0));
            }
        }
        return ids;
    }

    private static List<Long> vectorIds(List<SortKey> keys, Expr where) {
        VectorOperator source = where == null ? new VectorScan(table)
                : new VectorFilter(new VectorScan(table), where);
        List<Long> ids = new ArrayList<>();
        try (VectorSort sort = new VectorSort(source, withTiebreak(keys), 50)) {
            sort.open();
            VectorBatch batch;
            while ((batch = sort.next()) != null) {
                for (int r = 0; r < batch.rowCount(); r++) {
                    ids.add(((LongVector) batch.column(0)).get(r));
                }
            }
        }
        return ids;
    }

    private static void check(List<SortKey> keys) {
        List<Long> expected = duckIds(keys, null);
        assertEquals(ROWS, expected.size());
        assertEquals(expected, rowIds(keys, null), "row engine, ORDER BY " + keys);
        assertEquals(expected, vectorIds(keys, null), "vector engine, ORDER BY " + keys);
    }

    @Test
    @DisplayName("every type sorts as DuckDB sorts it, both directions, NULLs last")
    void singleKeys() {
        for (String column : List.of("i", "d", "s", "dt", "b")) {
            check(List.of(SortKey.asc(column)));
            check(List.of(SortKey.desc(column)));
        }
    }

    @Test
    @DisplayName("multi-key orders with mixed directions match DuckDB")
    void multipleKeys() {
        check(List.of(SortKey.desc("s"), SortKey.asc("i")));
        check(List.of(SortKey.asc("b"), SortKey.desc("d")));
        check(List.of(SortKey.desc("dt"), SortKey.asc("s"), SortKey.desc("i")));
    }

    @Test
    @DisplayName("sorting a filtered input sorts only the rows that survived")
    void sortsASelection() {
        List<SortKey> keys = List.of(SortKey.desc("d"));
        Expr where = Expr.col("i").binary(BinaryOp.GREATER_OR_EQUAL, Expr.lit(0L));
        List<Long> expected = duckIds(keys, "i >= 0");
        assertEquals(expected, rowIds(keys, where), "row engine");
        assertEquals(expected, vectorIds(keys, where), "vector engine");
    }
}
