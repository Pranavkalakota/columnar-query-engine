package columnar.bench;

import columnar.exec.agg.Aggregate;
import columnar.exec.row.BoxedRowScan;
import columnar.exec.row.Row;
import columnar.exec.row.RowFilter;
import columnar.exec.row.RowGroupedAggregate;
import columnar.exec.row.RowOperator;
import columnar.exec.row.RowScan;
import columnar.exec.vec.VectorFilter;
import columnar.exec.vec.VectorGroupedAggregate;
import columnar.exec.vec.VectorScan;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.storage.InMemoryTable;
import columnar.storage.csv.DelimitedOptions;
import columnar.vector.VectorBatch;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** Times TPC-H Q1 on each engine. */
public final class Q1Benchmark {

    private Q1Benchmark() {
    }

    private static final List<String> KEYS = List.of("l_returnflag", "l_linestatus");

    /** The Q1 cutoff: 1998-12-01 less ninety days. */
    public static Expr predicate() {
        return Expr.col("l_shipdate").binary(BinaryOp.LESS_OR_EQUAL, Expr.date("1998-09-02"));
    }

    public static List<Aggregate> aggregates() {
        Expr discounted = Expr.col("l_extendedprice").binary(BinaryOp.MULTIPLY,
                Expr.lit(1.0).binary(BinaryOp.SUBTRACT, Expr.col("l_discount")));
        Expr charge = discounted.binary(BinaryOp.MULTIPLY,
                Expr.lit(1.0).binary(BinaryOp.ADD, Expr.col("l_tax")));
        return List.of(
                Aggregate.sum(Expr.col("l_quantity"), "sum_qty"),
                Aggregate.sum(Expr.col("l_extendedprice"), "sum_base_price"),
                Aggregate.sum(discounted, "sum_disc_price"),
                Aggregate.sum(charge, "sum_charge"),
                Aggregate.avg(Expr.col("l_quantity"), "avg_qty"),
                Aggregate.avg(Expr.col("l_extendedprice"), "avg_price"),
                Aggregate.avg(Expr.col("l_discount"), "avg_disc"),
                Aggregate.countStar("count_order"));
    }

    /** Sorted rows, so two engines compare without an ORDER BY. */
    private static List<String> sorted(List<String> rows) {
        Collections.sort(rows);
        return rows;
    }

    public static Object runQ1(RowOperator scan) {
        List<String> rows = new ArrayList<>();
        try (RowGroupedAggregate op = new RowGroupedAggregate(
                new RowFilter(scan, predicate()), KEYS, aggregates())) {
            op.open();
            Row row;
            while ((row = op.next()) != null) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < op.schema().size(); i++) {
                    line.append(row.get(i)).append('|');
                }
                rows.add(line.toString());
            }
        }
        return sorted(rows);
    }

    public static Object runQ1Vector(InMemoryTable table) {
        List<String> rows = new ArrayList<>();
        try (VectorGroupedAggregate op = new VectorGroupedAggregate(
                new VectorFilter(new VectorScan(table), predicate()), KEYS, aggregates(), 1024)) {
            op.open();
            VectorBatch batch;
            while ((batch = op.next()) != null) {
                for (int row = 0; row < batch.rowCount(); row++) {
                    StringBuilder line = new StringBuilder();
                    for (int i = 0; i < batch.schema().size(); i++) {
                        line.append(batch.column(i).valueAt(row)).append('|');
                    }
                    rows.add(line.toString());
                }
            }
        }
        return sorted(rows);
    }

    public static void main(String[] args) throws IOException {
        Path tbl = Path.of(args.length > 0 ? args[0] : "data/sf0.1").resolve("lineitem.tbl");
        int warmups = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int runs = args.length > 2 ? Integer.parseInt(args[2]) : 15;

        InMemoryTable table = InMemoryTable.load(tbl, Q6Benchmark.LINEITEM, DelimitedOptions.tbl(), 1024);
        System.out.println(Benchmark.environment());
        System.out.printf("%s: %,d rows, %d batches%n", tbl, table.rowCount(), table.batchCount());

        Function<String, Benchmark.Result> measure = name -> Benchmark.measure(name, warmups, runs,
                () -> runQ1(name.equals("ROW_BOXED") ? new BoxedRowScan(table) : new RowScan(table)));
        Benchmark.Result boxed = measure.apply("ROW_BOXED");
        Benchmark.Result tuned = measure.apply("ROW_TUNED");
        Benchmark.Result vector = Benchmark.measure("VECTOR", warmups, runs, () -> runQ1Vector(table));

        System.out.println(boxed);
        System.out.println(tuned);
        System.out.println(vector);
        agree(boxed.last(), tuned.last());
        agree(tuned.last(), vector.last());
        System.out.printf("%d groups, all three engines agree%n", ((List<?>) vector.last()).size());
        System.out.printf("ROW_TUNED is %.2fx faster than ROW_BOXED%n", boxed.relativeTo(tuned));
        System.out.printf("VECTOR is %.2fx faster than ROW_TUNED%n", tuned.relativeTo(vector));
        System.out.printf("VECTOR is %.2fx faster than ROW_BOXED%n", boxed.relativeTo(vector));
    }

    /** A benchmark that measures the wrong answer measures nothing. */
    private static void agree(Object left, Object right) {
        if (!left.equals(right)) {
            throw new IllegalStateException("engines disagree:\n" + left + "\n" + right);
        }
    }
}
