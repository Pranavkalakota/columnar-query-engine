package columnar.tpch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.differential.DuckDbOracle;
import columnar.differential.ResultComparator;
import columnar.differential.ResultTable;
import columnar.exec.agg.Aggregate;
import columnar.exec.row.Row;
import columnar.exec.row.RowAggregate;
import columnar.exec.row.RowFilter;
import columnar.exec.row.RowScan;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.storage.InMemoryTable;
import columnar.storage.csv.DelimitedOptions;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TpchQ6Test {

    /** lineitem, with DECIMAL columns cast to DOUBLE. */
    private static final Schema LINEITEM = Schema.of(
            Field.of("l_orderkey", DataType.INT64), Field.of("l_partkey", DataType.INT64),
            Field.of("l_suppkey", DataType.INT64), Field.of("l_linenumber", DataType.INT64),
            Field.of("l_quantity", DataType.DOUBLE), Field.of("l_extendedprice", DataType.DOUBLE),
            Field.of("l_discount", DataType.DOUBLE), Field.of("l_tax", DataType.DOUBLE),
            Field.of("l_returnflag", DataType.VARCHAR), Field.of("l_linestatus", DataType.VARCHAR),
            Field.of("l_shipdate", DataType.DATE), Field.of("l_commitdate", DataType.DATE),
            Field.of("l_receiptdate", DataType.DATE), Field.of("l_shipinstruct", DataType.VARCHAR),
            Field.of("l_shipmode", DataType.VARCHAR), Field.of("l_comment", DataType.VARCHAR));

    private static final String Q6 = """
            SELECT SUM(l_extendedprice * l_discount) AS revenue
            FROM lineitem
            WHERE l_shipdate >= DATE '1994-01-01' AND l_shipdate < DATE '1995-01-01'
              AND l_discount >= 0.05 AND l_discount <= 0.07 AND l_quantity < 24
            """;

    private static Expr and(Expr left, Expr right) {
        return left.binary(BinaryOp.AND, right);
    }

    /** The Q6 predicate, as an expression tree. */
    private static Expr q6Predicate() {
        return and(and(and(
                Expr.col("l_shipdate").binary(BinaryOp.GREATER_OR_EQUAL, Expr.date("1994-01-01")),
                Expr.col("l_shipdate").binary(BinaryOp.LESS, Expr.date("1995-01-01"))),
                and(Expr.col("l_discount").binary(BinaryOp.GREATER_OR_EQUAL, Expr.lit(0.05)),
                        Expr.col("l_discount").binary(BinaryOp.LESS_OR_EQUAL, Expr.lit(0.07)))),
                Expr.col("l_quantity").binary(BinaryOp.LESS, Expr.lit(24.0)));
    }

    private static Object runQ6(Path tbl, int batchSize) throws IOException {
        InMemoryTable table = InMemoryTable.load(tbl, LINEITEM, DelimitedOptions.tbl(), batchSize);
        Expr revenue = Expr.col("l_extendedprice").binary(BinaryOp.MULTIPLY, Expr.col("l_discount"));
        try (RowAggregate op = new RowAggregate(
                new RowFilter(new RowScan(table), q6Predicate()),
                List.of(Aggregate.sum(revenue, "revenue")))) {
            op.open();
            Row row = op.next();
            assertNotNull(row, "ungrouped aggregation always yields a row");
            return row.get(0);
        }
    }

    @Test
    @DisplayName("TPC-H Q6 matches DuckDB at SF0.01, at every batch size")
    void q6MatchesTheOracle(@TempDir Path dir) throws IOException {
        TpchData.generate(0.01, dir);
        Path tbl = dir.resolve("lineitem.tbl");
        try (DuckDbOracle oracle = DuckDbOracle.inMemory()) {
            oracle.registerParquet("lineitem", dir.resolve("lineitem.parquet"));
            ResultTable expected = oracle.query(Q6);
            assertNotNull(expected.rows().get(0).get(0), "the oracle should find matching rows");
            ResultComparator comparator = new ResultComparator();
            for (int batchSize : new int[] {1, 7, 1024}) {
                ResultTable actual = ResultTable.of(List.of("revenue"),
                        List.of(List.of(runQ6(tbl, batchSize))));
                var diff = comparator.compare(expected, actual, true);
                assertTrue(diff.isEmpty(), "batch " + batchSize + ": " + diff.orElse(""));
            }
        }
    }

    @Test
    @DisplayName("the filter actually selects a subset, so the query is not trivially right")
    void predicateIsSelective(@TempDir Path dir) throws IOException {
        TpchData.generate(0.01, dir);
        try (DuckDbOracle oracle = DuckDbOracle.inMemory()) {
            oracle.registerParquet("lineitem", dir.resolve("lineitem.parquet"));
            long total = oracle.count("lineitem");
            long matching = (Long) oracle.query(
                    "SELECT count(*) FROM lineitem WHERE " + Q6.split("WHERE")[1]).rows().get(0).get(0);
            assertTrue(matching > 0 && matching < total,
                    "Q6 should match some rows but not all: " + matching + " of " + total);
            assertEquals(60175L, total, "SF0.01 lineitem");
        }
    }
}
