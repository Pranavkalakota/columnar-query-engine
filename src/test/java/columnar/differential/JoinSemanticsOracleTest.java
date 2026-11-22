package columnar.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.exec.row.Row;
import columnar.exec.row.RowFilter;
import columnar.exec.row.RowHashJoin;
import columnar.exec.row.RowOperator;
import columnar.exec.row.RowScan;
import columnar.exec.vec.VectorFilter;
import columnar.exec.vec.VectorHashJoin;
import columnar.exec.vec.VectorOperator;
import columnar.exec.vec.VectorScan;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.storage.InMemoryTable;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
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

/** Join semantics pinned to DuckDB, NULL keys included. */
class JoinSemanticsOracleTest {

    private static final String[] STRINGS = {"", "a", "ab", "Z", "é", "😀"};

    private static DuckDbOracle oracle;
    private static InMemoryTable a;
    private static InMemoryTable b;

    private static Schema schema(String p) {
        return Schema.of(Field.of(p + "_id", DataType.INT64), Field.of(p + "_long", DataType.INT64),
                Field.of(p + "_int", DataType.INT32), Field.of(p + "_date", DataType.DATE),
                Field.of(p + "_str", DataType.VARCHAR), Field.of(p + "_val", DataType.DOUBLE));
    }

    @BeforeAll
    static void load() {
        Random random = new Random(20261121L);
        oracle = DuckDbOracle.inMemory();
        a = table("a", 400, 40, random);
        b = table("b", 150, 37, random);
    }

    @AfterAll
    static void close() {
        oracle.close();
    }

    /** Builds a table in both engines from small pools. */
    private static InMemoryTable table(String p, int rows, int batchSize, Random random) {
        Schema schema = schema(p);
        List<VectorBatch> batches = new ArrayList<>();
        StringJoiner sql = new StringJoiner(",\n");
        for (int start = 0; start < rows; start += batchSize) {
            int size = Math.min(batchSize, rows - start);
            VectorBatch batch = VectorBatch.allocate(schema, batchSize);
            for (int r = 0; r < size; r++) {
                int at = r;
                StringJoiner row = new StringJoiner(", ", "(", ")");
                ((LongVector) batch.column(0)).set(r, start + r);
                row.add(Long.toString(start + r));
                long key = random.nextInt(25);
                row.add(nullOr(batch, 1, r, random, () -> ((LongVector) batch.column(1)).set(at, key), Long.toString(key)));
                int small = random.nextInt(6);
                row.add(nullOr(batch, 2, r, random, () -> ((IntVector) batch.column(2)).set(at, small), Integer.toString(small)));
                int day = 9000 + random.nextInt(8);
                row.add(nullOr(batch, 3, r, random, () -> ((IntVector) batch.column(3)).set(at, day),
                        "DATE '" + LocalDate.ofEpochDay(day) + "'"));
                StringVector str = (StringVector) batch.column(4);
                if (random.nextInt(10) == 0) {
                    str.appendNull();
                    row.add("NULL");
                } else {
                    String value = STRINGS[random.nextInt(STRINGS.length)];
                    str.append(value);
                    row.add("'" + value + "'");
                }
                double val = random.nextInt(100) / 100.0;
                ((DoubleVector) batch.column(5)).set(r, val);
                row.add(Double.toString(val));
                sql.add(row.toString());
            }
            batch.setRowCount(size);
            batches.add(batch);
        }
        oracle.execute("CREATE TABLE " + p + " (" + p + "_id BIGINT, " + p + "_long BIGINT, " + p + "_int INTEGER, "
                + p + "_date DATE, " + p + "_str VARCHAR, " + p + "_val DOUBLE)");
        oracle.execute("INSERT INTO " + p + " VALUES " + sql);
        return InMemoryTable.of(schema, batches);
    }

    private static String nullOr(VectorBatch batch, int column, int r, Random random, Runnable set, String sql) {
        if (random.nextInt(10) == 0) {
            batch.column(column).setNull(r);
            return "NULL";
        }
        set.run();
        return sql;
    }

    private static List<List<Object>> rowPairs(Expr whereA, Expr whereB, List<String> ka, List<String> kb) {
        RowOperator probe = whereA == null ? new RowScan(a) : new RowFilter(new RowScan(a), whereA);
        RowOperator build = whereB == null ? new RowScan(b) : new RowFilter(new RowScan(b), whereB);
        List<List<Object>> pairs = new ArrayList<>();
        try (RowHashJoin join = new RowHashJoin(probe, build, ka, kb)) {
            join.open();
            Row row;
            while ((row = join.next()) != null) {
                pairs.add(List.of(row.getLong(0), row.getLong(6)));
            }
        }
        return pairs;
    }

    private static List<List<Object>> vectorPairs(Expr whereA, Expr whereB, List<String> ka, List<String> kb) {
        VectorOperator probe = whereA == null ? new VectorScan(a) : new VectorFilter(new VectorScan(a), whereA);
        VectorOperator build = whereB == null ? new VectorScan(b) : new VectorFilter(new VectorScan(b), whereB);
        List<List<Object>> pairs = new ArrayList<>();
        try (VectorHashJoin join = new VectorHashJoin(probe, build, ka, kb, 64)) {
            join.open();
            VectorBatch batch;
            while ((batch = join.next()) != null) {
                for (int r = 0; r < batch.rowCount(); r++) {
                    pairs.add(List.of(((LongVector) batch.column(0)).get(r), ((LongVector) batch.column(6)).get(r)));
                }
            }
        }
        return pairs;
    }

    private static void check(String on, String where, Expr whereA, Expr whereB, List<String> ka, List<String> kb) {
        ResultTable expected = oracle.query("SELECT a_id, b_id FROM a JOIN b ON " + on
                + (where == null ? "" : " WHERE " + where));
        List<List<Object>> rows = rowPairs(whereA, whereB, ka, kb);
        List<List<Object>> vectors = vectorPairs(whereA, whereB, ka, kb);
        assertEquals(rows, vectors, "both engines emit the same pairs, in the same order: " + on);
        var diff = new ResultComparator().compare(expected, ResultTable.of(List.of("a_id", "b_id"), vectors), false);
        assertTrue(diff.isEmpty(), on + ": " + diff.orElse(""));
    }

    @Test
    @DisplayName("each key type joins as DuckDB joins it, NULL keys never matching")
    void singleKeys() {
        for (String suffix : List.of("long", "int", "date", "str")) {
            check("a_" + suffix + " = b_" + suffix, null, null, null, List.of("a_" + suffix), List.of("b_" + suffix));
        }
    }

    @Test
    @DisplayName("a two-column key matches DuckDB")
    void compositeKey() {
        check("a_long = b_long AND a_str = b_str", null, null, null,
                List.of("a_long", "a_str"), List.of("b_long", "b_str"));
    }

    @Test
    @DisplayName("joining filtered inputs joins only the survivors")
    void filteredSides() {
        Expr whereA = Expr.col("a_val").binary(BinaryOp.LESS, Expr.lit(0.5));
        Expr whereB = Expr.col("b_int").binary(BinaryOp.GREATER_OR_EQUAL, Expr.lit(2L));
        check("a_long = b_long", "a_val < 0.5 AND b_int >= 2", whereA, whereB, List.of("a_long"), List.of("b_long"));
    }

    @Test
    @DisplayName("an empty build side joins to nothing")
    void emptyBuildSide() {
        Expr none = Expr.col("b_val").binary(BinaryOp.LESS, Expr.lit(-1.0));
        check("a_long = b_long", "b_val < -1", null, none, List.of("a_long"), List.of("b_long"));
    }

    @Test
    @DisplayName("keys of different types are refused, where DuckDB would cast")
    void refusesMixedTypes() {
        assertThrows(UnsupportedOperationException.class,
                () -> new VectorHashJoin(new VectorScan(a), new VectorScan(b), List.of("a_int"), List.of("b_long"), 64));
        assertThrows(UnsupportedOperationException.class,
                () -> new RowHashJoin(new RowScan(a), new RowScan(b), List.of("a_int"), List.of("b_long")));
        assertThrows(UnsupportedOperationException.class,
                () -> new VectorHashJoin(new VectorScan(a), new VectorScan(b), List.of("a_val"), List.of("b_val"), 64));
        assertThrows(UnsupportedOperationException.class,
                () -> new RowHashJoin(new RowScan(a), new RowScan(b), List.of("a_val"), List.of("b_val")));
    }
}
