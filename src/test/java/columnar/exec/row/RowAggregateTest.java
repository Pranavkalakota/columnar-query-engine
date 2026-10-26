package columnar.exec.row;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.differential.DuckDbOracle;
import columnar.differential.ResultComparator;
import columnar.differential.ResultTable;
import columnar.exec.agg.Aggregate;
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
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RowAggregateTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("a", DataType.INT64), Field.of("b", DataType.DOUBLE));

    private static final String CSV = "1,1.5\n2,\n,2.5\n3,0.07\n4,0.05\n";

    private static Path csvFile(Path dir, String body) throws IOException {
        Path file = dir.resolve("t.csv");
        Files.writeString(file, body);
        return file;
    }

    private static Row aggregate(Path file, List<Aggregate> aggregates) throws IOException {
        InMemoryTable table = InMemoryTable.load(file, SCHEMA, DelimitedOptions.csv(), 3);
        RowAggregate op = new RowAggregate(new RowScan(table), aggregates);
        op.open();
        return op.next();
    }

    @Test
    @DisplayName("every aggregate matches the oracle on the same data")
    void matchesTheOracle(@TempDir Path dir) throws IOException {
        Path file = csvFile(dir, CSV);
        List<Aggregate> aggregates = List.of(
                Aggregate.countStar("n"),
                Aggregate.count(Expr.col("b"), "nb"),
                Aggregate.sum(Expr.col("a"), "sa"),
                Aggregate.sum(Expr.col("b"), "sb"),
                Aggregate.avg(Expr.col("b"), "ab"),
                Aggregate.min(Expr.col("a"), "mn"),
                Aggregate.max(Expr.col("b"), "mx"));
        Row row = aggregate(file, aggregates);
        List<Object> actual = Arrays.asList(row.get(0), row.get(1), row.get(2),
                row.get(3), row.get(4), row.get(5), row.get(6));
        try (DuckDbOracle oracle = DuckDbOracle.inMemory()) {
            oracle.execute("CREATE VIEW t AS SELECT * FROM read_csv('%s', header=false, columns={'a':'BIGINT','b':'DOUBLE'})"
                    .formatted(DuckDbOracle.quote(file)));
            ResultTable expected = oracle.query(
                    "SELECT count(*), count(b), sum(a), sum(b), avg(b), min(a), max(b) FROM t");
            var diff = new ResultComparator().compare(expected,
                    ResultTable.of(expected.columnNames(), List.of(actual)), true);
            assertTrue(diff.isEmpty(), diff.orElse(""));
        }
    }

    @Test
    @DisplayName("an empty input still produces one row: SUM is NULL but COUNT is 0")
    void emptyInputStillProducesOneRow(@TempDir Path dir) throws IOException {
        Row row = aggregate(csvFile(dir, ""),
                List.of(Aggregate.countStar("n"), Aggregate.sum(Expr.col("a"), "s"),
                        Aggregate.avg(Expr.col("b"), "av")));
        assertNotNull(row, "ungrouped aggregation always yields exactly one row");
        assertEquals(0L, row.get(0));
        assertNull(row.get(1));
        assertNull(row.get(2));
    }

    @Test
    @DisplayName("exactly one row is produced, and the second call ends the stream")
    void producesExactlyOneRow(@TempDir Path dir) throws IOException {
        InMemoryTable table = InMemoryTable.load(csvFile(dir, CSV), SCHEMA, DelimitedOptions.csv(), 3);
        try (RowAggregate op = new RowAggregate(new RowScan(table), List.of(Aggregate.countStar("n")))) {
            op.open();
            assertEquals(5L, op.next().get(0));
            assertNull(op.next());
            op.open();
            assertEquals(5L, op.next().get(0), "reopening recomputes from scratch");
        }
    }

    @Test
    @DisplayName("the output schema takes names and types from the aggregates")
    void derivedSchema(@TempDir Path dir) throws IOException {
        InMemoryTable table = InMemoryTable.load(csvFile(dir, CSV), SCHEMA, DelimitedOptions.csv(), 3);
        try (RowAggregate op = new RowAggregate(new RowScan(table),
                List.of(Aggregate.countStar("n"), Aggregate.sum(Expr.col("a"), "s"),
                        Aggregate.avg(Expr.col("a"), "av")))) {
            assertEquals(List.of("n", "s", "av"), op.schema().names());
            assertEquals(DataType.INT64, op.schema().type(0));
            assertEquals(DataType.INT64, op.schema().type(1), "SUM over BIGINT stays integral");
            assertEquals(DataType.DOUBLE, op.schema().type(2), "AVG is always DOUBLE");
        }
    }

    @Test
    @DisplayName("aggregating an expression works, not just a bare column")
    void aggregatesAnExpression(@TempDir Path dir) throws IOException {
        Row row = aggregate(csvFile(dir, CSV), List.of(
                Aggregate.sum(Expr.col("a").binary(BinaryOp.MULTIPLY, Expr.col("b")), "revenue")));
        assertEquals(1 * 1.5 + 3 * 0.07 + 4 * 0.05, (Double) row.get(0), 1e-9);
    }

    @Test
    @DisplayName("an empty aggregate list is refused")
    void needsAtLeastOneAggregate(@TempDir Path dir) throws IOException {
        InMemoryTable table = InMemoryTable.load(csvFile(dir, CSV), SCHEMA, DelimitedOptions.csv(), 3);
        RowScan scan = new RowScan(table);
        assertThrows(IllegalArgumentException.class, () -> new RowAggregate(scan, List.of()));
    }
}
