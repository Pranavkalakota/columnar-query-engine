package columnar.exec.row;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.differential.DuckDbOracle;
import columnar.differential.ResultComparator;
import columnar.differential.ResultTable;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.storage.InMemoryTable;
import columnar.storage.csv.DelimitedOptions;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RowFilterTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("a", DataType.INT64), Field.of("b", DataType.DOUBLE));

    /** Deliberately includes NULLs in both columns, since NULL must reject like FALSE. */
    private static final String CSV = """
            1,1.5
            2,
            ,2.5
            3,0.07
            4,0.05
            5,
            ,
            6,9.5
            """;

    private static Path csvFile(Path dir) throws IOException {
        Path file = dir.resolve("t.csv");
        Files.writeString(file, CSV);
        return file;
    }

    private static List<List<Object>> runFilter(Path file, Expr predicate, int batchSize) throws IOException {
        InMemoryTable table = InMemoryTable.load(file, SCHEMA, DelimitedOptions.csv(), batchSize);
        List<List<Object>> rows = new ArrayList<>();
        try (RowFilter filter = new RowFilter(new RowScan(table), predicate)) {
            filter.open();
            Row row;
            while ((row = filter.next()) != null) {
                rows.add(List.of(String.valueOf(row.get(0)), String.valueOf(row.get(1))));
            }
        }
        return rows;
    }

    private static ResultTable engineResult(Path file, Expr predicate, int batchSize) throws IOException {
        InMemoryTable table = InMemoryTable.load(file, SCHEMA, DelimitedOptions.csv(), batchSize);
        List<List<Object>> rows = new ArrayList<>();
        try (RowFilter filter = new RowFilter(new RowScan(table), predicate)) {
            filter.open();
            Row row;
            while ((row = filter.next()) != null) {
                rows.add(java.util.Arrays.asList(row.get(0), row.get(1)));
            }
        }
        return ResultTable.of(List.of("a", "b"), rows);
    }

    @Test
    @DisplayName("filters match the oracle on the same data, at every batch size")
    void filtersMatchTheOracle(@TempDir Path dir) throws IOException {
        Path file = csvFile(dir);
        record Case(String sql, Expr expr) {}
        List<Case> cases = List.of(
                new Case("a < 3", Expr.col("a").binary(BinaryOp.LESS, Expr.lit(3L))),
                new Case("b >= 0.07", Expr.col("b").binary(BinaryOp.GREATER_OR_EQUAL, Expr.lit(0.07))),
                new Case("a < 5 AND b > 1.0", Expr.col("a").binary(BinaryOp.LESS, Expr.lit(5L))
                        .binary(BinaryOp.AND, Expr.col("b").binary(BinaryOp.GREATER, Expr.lit(1.0)))),
                new Case("a = 2 OR b = 2.5", Expr.col("a").binary(BinaryOp.EQUAL, Expr.lit(2L))
                        .binary(BinaryOp.OR, Expr.col("b").binary(BinaryOp.EQUAL, Expr.lit(2.5)))),
                new Case("a > 100", Expr.col("a").binary(BinaryOp.GREATER, Expr.lit(100L))));
        ResultComparator comparator = new ResultComparator();
        try (DuckDbOracle oracle = DuckDbOracle.inMemory()) {
            oracle.execute("CREATE VIEW t AS SELECT * FROM read_csv('%s', header=false, columns={'a':'BIGINT','b':'DOUBLE'})"
                    .formatted(DuckDbOracle.quote(file)));
            for (Case c : cases) {
                ResultTable expected = oracle.query("SELECT a, b FROM t WHERE " + c.sql());
                for (int batchSize : new int[] {1, 3, 8, 64}) {
                    var diff = comparator.compare(expected, engineResult(file, c.expr(), batchSize), false);
                    assertTrue(diff.isEmpty(), c.sql() + " at batch " + batchSize + ": " + diff.orElse(""));
                }
            }
        }
    }

    @Test
    @DisplayName("a NULL predicate rejects the row, exactly as FALSE does")
    void nullRejectsLikeFalse(@TempDir Path dir) throws IOException {
        // b is NULL on three rows; b > 0 is NULL there, so those rows must not pass.
        Path file = csvFile(dir);
        List<List<Object>> passed = runFilter(file,
                Expr.col("b").binary(BinaryOp.GREATER, Expr.lit(0.0)), 4);
        assertEquals(5, passed.size(), "the three NULL-b rows are rejected");
        assertTrue(passed.stream().noneMatch(r -> r.get(1).equals("null")));
    }

    @Test
    @DisplayName("a non-boolean predicate is refused when the filter is built, not per row")
    void predicateMustBeBoolean(@TempDir Path dir) throws IOException {
        InMemoryTable table = InMemoryTable.load(csvFile(dir), SCHEMA, DelimitedOptions.csv(), 4);
        RowScan scan = new RowScan(table);
        assertThrows(IllegalArgumentException.class, () -> new RowFilter(scan, Expr.col("a")));
    }

    @Test
    @DisplayName("the filter reports its child's schema and prints readably")
    void schemaAndToString(@TempDir Path dir) throws IOException {
        InMemoryTable table = InMemoryTable.load(csvFile(dir), SCHEMA, DelimitedOptions.csv(), 4);
        RowFilter filter = new RowFilter(new RowScan(table),
                Expr.col("a").binary(BinaryOp.LESS, Expr.lit(3L)));
        assertEquals(SCHEMA, filter.schema());
        assertEquals("Filter((a < 3))", filter.toString());
    }
}
