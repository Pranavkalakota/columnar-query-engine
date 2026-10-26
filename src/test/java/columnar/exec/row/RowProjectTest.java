package columnar.exec.row;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RowProjectTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("a", DataType.INT64), Field.of("b", DataType.DOUBLE));

    private static final String CSV = "1,1.5\n2,\n,2.5\n3,0.07\n4,0.05\n";

    private static Path csvFile(Path dir) throws IOException {
        Path file = dir.resolve("t.csv");
        Files.writeString(file, CSV);
        return file;
    }

    private static RowProject project(Path file, List<String> names, List<Expr> exprs) throws IOException {
        InMemoryTable table = InMemoryTable.load(file, SCHEMA, DelimitedOptions.csv(), 3);
        return new RowProject(new RowScan(table), names, exprs);
    }

    @Test
    @DisplayName("computed columns match the oracle, including NULL propagation")
    void matchesTheOracle(@TempDir Path dir) throws IOException {
        Path file = csvFile(dir);
        List<Expr> exprs = List.of(
                Expr.col("a").binary(BinaryOp.ADD, Expr.lit(1L)),
                Expr.col("b").binary(BinaryOp.MULTIPLY, Expr.lit(2.0)),
                Expr.col("a").binary(BinaryOp.DIVIDE, Expr.lit(2L)));
        List<List<Object>> rows = new ArrayList<>();
        try (RowProject p = project(file, List.of("x", "y", "z"), exprs)) {
            p.open();
            Row row;
            while ((row = p.next()) != null) {
                rows.add(Arrays.asList(row.get(0), row.get(1), row.get(2)));
            }
        }
        try (DuckDbOracle oracle = DuckDbOracle.inMemory()) {
            oracle.execute("CREATE VIEW t AS SELECT * FROM read_csv('%s', header=false, columns={'a':'BIGINT','b':'DOUBLE'})"
                    .formatted(DuckDbOracle.quote(file)));
            ResultTable expected = oracle.query("SELECT a + 1, b * 2.0, a / 2 FROM t");
            var diff = new ResultComparator().compare(expected,
                    ResultTable.of(List.of("x", "y", "z"), rows), false);
            assertTrue(diff.isEmpty(), diff.orElse(""));
        }
    }

    @Test
    @DisplayName("the output schema takes its names from the caller and its types from the expressions")
    void derivedSchema(@TempDir Path dir) throws IOException {
        try (RowProject p = project(csvFile(dir), List.of("sum", "ratio"),
                List.of(Expr.col("a").binary(BinaryOp.ADD, Expr.lit(1L)),
                        Expr.col("a").binary(BinaryOp.DIVIDE, Expr.lit(2L))))) {
            assertEquals(List.of("sum", "ratio"), p.schema().names());
            assertEquals(DataType.INT64, p.schema().type(0));
            assertEquals(DataType.DOUBLE, p.schema().type(1), "division always widens to DOUBLE");
        }
    }

    @Test
    @DisplayName("one output row is reused, and a NULL input yields a NULL output")
    void reusesOutputRowAndPropagatesNull(@TempDir Path dir) throws IOException {
        try (RowProject p = project(csvFile(dir), List.of("y"),
                List.of(Expr.col("b").binary(BinaryOp.MULTIPLY, Expr.lit(2.0))))) {
            p.open();
            Row first = p.next();
            assertEquals(3.0, first.get(0));
            Row second = p.next();
            assertSame(first, second, "the projection reuses its output row");
            assertNull(second.get(0), "NULL times anything is NULL");
        }
    }

    @Test
    @DisplayName("a name count that does not match the expressions is refused")
    void nameCountMustMatch(@TempDir Path dir) throws IOException {
        InMemoryTable table = InMemoryTable.load(csvFile(dir), SCHEMA, DelimitedOptions.csv(), 3);
        RowScan scan = new RowScan(table);
        assertThrows(IllegalArgumentException.class,
                () -> new RowProject(scan, List.of("only"), List.of(Expr.col("a"), Expr.col("b"))));
    }
}
