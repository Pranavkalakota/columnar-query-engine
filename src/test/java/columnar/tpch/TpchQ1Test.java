package columnar.tpch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.differential.DuckDbOracle;
import columnar.differential.ResultComparator;
import columnar.differential.ResultTable;
import columnar.exec.agg.Aggregate;
import columnar.exec.row.Row;
import columnar.exec.row.RowFilter;
import columnar.exec.row.RowGroupedAggregate;
import columnar.exec.row.RowScan;
import columnar.exec.row.RowSort;
import columnar.exec.sort.SortKey;
import columnar.exec.vec.VectorFilter;
import columnar.exec.vec.VectorGroupedAggregate;
import columnar.exec.vec.VectorScan;
import columnar.exec.vec.VectorSort;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.storage.InMemoryTable;
import columnar.storage.csv.DelimitedOptions;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.VectorBatch;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TpchQ1Test {

    private static final Schema LINEITEM = Schema.of(
            Field.of("l_orderkey", DataType.INT64), Field.of("l_partkey", DataType.INT64),
            Field.of("l_suppkey", DataType.INT64), Field.of("l_linenumber", DataType.INT64),
            Field.of("l_quantity", DataType.DOUBLE), Field.of("l_extendedprice", DataType.DOUBLE),
            Field.of("l_discount", DataType.DOUBLE), Field.of("l_tax", DataType.DOUBLE),
            Field.of("l_returnflag", DataType.VARCHAR), Field.of("l_linestatus", DataType.VARCHAR),
            Field.of("l_shipdate", DataType.DATE), Field.of("l_commitdate", DataType.DATE),
            Field.of("l_receiptdate", DataType.DATE), Field.of("l_shipinstruct", DataType.VARCHAR),
            Field.of("l_shipmode", DataType.VARCHAR), Field.of("l_comment", DataType.VARCHAR));

    private static final List<String> COLUMNS = List.of("l_returnflag", "l_linestatus",
            "sum_qty", "sum_base_price", "sum_disc_price", "sum_charge",
            "avg_qty", "avg_price", "avg_disc", "count_order");

    /** TPC-H Q1, exactly as the spec writes it. */
    private static final String QUERY = """
            SELECT l_returnflag, l_linestatus,
                   sum(l_quantity) AS sum_qty,
                   sum(l_extendedprice) AS sum_base_price,
                   sum(l_extendedprice * (1 - l_discount)) AS sum_disc_price,
                   sum(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge,
                   avg(l_quantity) AS avg_qty,
                   avg(l_extendedprice) AS avg_price,
                   avg(l_discount) AS avg_disc,
                   count(*) AS count_order
            FROM lineitem
            WHERE l_shipdate <= DATE '1998-12-01' - INTERVAL '90' day
            GROUP BY l_returnflag, l_linestatus
            ORDER BY l_returnflag, l_linestatus
            """;

    /** The Q1 cutoff: 1998-12-01 less ninety days. */
    private static Expr predicate() {
        return Expr.col("l_shipdate").binary(BinaryOp.LESS_OR_EQUAL, Expr.date("1998-09-02"));
    }

    /** l_extendedprice * (1 - l_discount), as Q1 writes it. */
    private static Expr discounted() {
        return Expr.col("l_extendedprice").binary(BinaryOp.MULTIPLY,
                Expr.lit(1.0).binary(BinaryOp.SUBTRACT, Expr.col("l_discount")));
    }

    private static List<Aggregate> aggregates() {
        Expr charge = discounted().binary(BinaryOp.MULTIPLY,
                Expr.lit(1.0).binary(BinaryOp.ADD, Expr.col("l_tax")));
        return List.of(
                Aggregate.sum(Expr.col("l_quantity"), "sum_qty"),
                Aggregate.sum(Expr.col("l_extendedprice"), "sum_base_price"),
                Aggregate.sum(discounted(), "sum_disc_price"),
                Aggregate.sum(charge, "sum_charge"),
                Aggregate.avg(Expr.col("l_quantity"), "avg_qty"),
                Aggregate.avg(Expr.col("l_extendedprice"), "avg_price"),
                Aggregate.avg(Expr.col("l_discount"), "avg_disc"),
                Aggregate.countStar("count_order"));
    }

    private static List<SortKey> order() {
        return List.of(SortKey.asc("l_returnflag"), SortKey.asc("l_linestatus"));
    }

    private static ResultTable runQ1(Path tbl, int batchSize, int outputBatch) throws IOException {
        InMemoryTable table = InMemoryTable.load(tbl, LINEITEM, DelimitedOptions.tbl(), batchSize);
        List<List<Object>> rows = new ArrayList<>();
        try (VectorSort op = new VectorSort(new VectorGroupedAggregate(
                new VectorFilter(new VectorScan(table), predicate()),
                List.of("l_returnflag", "l_linestatus"), aggregates(), outputBatch), order(), outputBatch)) {
            op.open();
            VectorBatch batch;
            while ((batch = op.next()) != null) {
                for (int row = 0; row < batch.rowCount(); row++) {
                    List<Object> values = new ArrayList<>();
                    for (int column = 0; column < batch.schema().size(); column++) {
                        values.add(batch.column(column).valueAt(row));
                    }
                    rows.add(values);
                }
            }
        }
        return ResultTable.of(COLUMNS, rows);
    }

    /** The same Q1 on the row engine. */
    private static ResultTable runQ1Row(Path tbl, int batchSize) throws IOException {
        InMemoryTable table = InMemoryTable.load(tbl, LINEITEM, DelimitedOptions.tbl(), batchSize);
        List<List<Object>> rows = new ArrayList<>();
        try (RowSort op = new RowSort(new RowGroupedAggregate(
                new RowFilter(new RowScan(table), predicate()),
                List.of("l_returnflag", "l_linestatus"), aggregates()), order())) {
            op.open();
            Row row;
            while ((row = op.next()) != null) {
                List<Object> values = new ArrayList<>();
                for (int column = 0; column < op.schema().size(); column++) {
                    values.add(row.get(column));
                }
                rows.add(values);
            }
        }
        return ResultTable.of(COLUMNS, rows);
    }

    @Test
    @DisplayName("the row engine computes the same Q1 as DuckDB")
    void rowEngineMatchesTheOracle(@TempDir Path dir) throws IOException {
        TpchData.generate(0.01, dir);
        try (DuckDbOracle oracle = DuckDbOracle.inMemory()) {
            oracle.registerParquet("lineitem", dir.resolve("lineitem.parquet"));
            ResultTable expected = oracle.query(QUERY);
            ResultComparator comparator = new ResultComparator();
            for (int batchSize : new int[] {1, 1024}) {
                var diff = comparator.compare(expected, runQ1Row(dir.resolve("lineitem.tbl"), batchSize), true);
                assertTrue(diff.isEmpty(), "row batch " + batchSize + ": " + diff.orElse(""));
            }
        }
    }

    @Test
    @DisplayName("full Q1 matches DuckDB at SF0.01, in order, at every batch size")
    void groupedAggregationMatchesTheOracle(@TempDir Path dir) throws IOException {
        TpchData.generate(0.01, dir);
        Path tbl = dir.resolve("lineitem.tbl");
        try (DuckDbOracle oracle = DuckDbOracle.inMemory()) {
            oracle.registerParquet("lineitem", dir.resolve("lineitem.parquet"));
            ResultTable expected = oracle.query(QUERY);
            assertEquals(4, expected.rows().size(), "Q1 has four returnflag/linestatus pairs");
            ResultComparator comparator = new ResultComparator();
            for (int batchSize : new int[] {1, 7, 1024}) {
                for (int outputBatch : new int[] {1, 1024}) {
                    ResultTable actual = runQ1(tbl, batchSize, outputBatch);
                    var diff = comparator.compare(expected, actual, true);
                    assertTrue(diff.isEmpty(),
                            "batch " + batchSize + "/" + outputBatch + ": " + diff.orElse(""));
                }
            }
        }
    }

    @Test
    @DisplayName("the filter drops rows, so grouping is not over the whole table")
    void predicateIsSelective(@TempDir Path dir) throws IOException {
        TpchData.generate(0.01, dir);
        try (DuckDbOracle oracle = DuckDbOracle.inMemory()) {
            oracle.registerParquet("lineitem", dir.resolve("lineitem.parquet"));
            long total = oracle.count("lineitem");
            long matching = (Long) oracle.query(
                    "SELECT count(*) FROM lineitem WHERE l_shipdate <= DATE '1998-09-02'")
                    .rows().get(0).get(0);
            assertTrue(matching > 0 && matching < total,
                    "Q1 should match some rows but not all: " + matching + " of " + total);
        }
    }
}
