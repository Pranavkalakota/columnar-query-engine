package columnar.bench;

import columnar.exec.agg.Aggregate;
import columnar.exec.row.BoxedRowScan;
import columnar.exec.row.Row;
import columnar.exec.row.RowAggregate;
import columnar.exec.row.RowFilter;
import columnar.exec.row.RowOperator;
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
import java.util.function.Function;

/** Times TPC-H Q6 on each row engine. */
public final class Q6Benchmark {

    private Q6Benchmark() {
    }

    public static final Schema LINEITEM = Schema.of(
            Field.of("l_orderkey", DataType.INT64), Field.of("l_partkey", DataType.INT64),
            Field.of("l_suppkey", DataType.INT64), Field.of("l_linenumber", DataType.INT64),
            Field.of("l_quantity", DataType.DOUBLE), Field.of("l_extendedprice", DataType.DOUBLE),
            Field.of("l_discount", DataType.DOUBLE), Field.of("l_tax", DataType.DOUBLE),
            Field.of("l_returnflag", DataType.VARCHAR), Field.of("l_linestatus", DataType.VARCHAR),
            Field.of("l_shipdate", DataType.DATE), Field.of("l_commitdate", DataType.DATE),
            Field.of("l_receiptdate", DataType.DATE), Field.of("l_shipinstruct", DataType.VARCHAR),
            Field.of("l_shipmode", DataType.VARCHAR), Field.of("l_comment", DataType.VARCHAR));

    private static Expr and(Expr l, Expr r) {
        return l.binary(BinaryOp.AND, r);
    }

    /** The Q6 predicate, shared by both engines. */
    public static Expr predicate() {
        return and(and(and(
                Expr.col("l_shipdate").binary(BinaryOp.GREATER_OR_EQUAL, Expr.date("1994-01-01")),
                Expr.col("l_shipdate").binary(BinaryOp.LESS, Expr.date("1995-01-01"))),
                and(Expr.col("l_discount").binary(BinaryOp.GREATER_OR_EQUAL, Expr.lit(0.05)),
                        Expr.col("l_discount").binary(BinaryOp.LESS_OR_EQUAL, Expr.lit(0.07)))),
                Expr.col("l_quantity").binary(BinaryOp.LESS, Expr.lit(24.0)));
    }

    /** Builds Q6 over a scan and runs it. */
    public static Object runQ6(RowOperator scan) {
        Expr revenue = Expr.col("l_extendedprice").binary(BinaryOp.MULTIPLY, Expr.col("l_discount"));
        RowAggregate op = new RowAggregate(new RowFilter(scan, predicate()),
                List.of(Aggregate.sum(revenue, "revenue")));
        op.open();
        Row row = op.next();
        Object result = row == null ? null : row.get(0);
        op.close();
        return result;
    }

    public static void main(String[] args) throws IOException {
        Path tbl = Path.of(args.length > 0 ? args[0] : "data/sf0.01").resolve("lineitem.tbl");
        int warmups = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int runs = args.length > 2 ? Integer.parseInt(args[2]) : 9;

        long loadStart = System.nanoTime();
        InMemoryTable table = InMemoryTable.load(tbl, LINEITEM, DelimitedOptions.tbl(), 1024);
        double loadMillis = (System.nanoTime() - loadStart) / 1_000_000.0;

        System.out.println(Benchmark.environment());
        System.out.printf("%s: %,d rows loaded in %.0f ms, %d batches%n",
                tbl, table.rowCount(), loadMillis, table.batchCount());

        Function<String, Benchmark.Result> measure = name -> Benchmark.measure(name, warmups, runs,
                () -> runQ6(name.equals("ROW_BOXED") ? new BoxedRowScan(table) : new RowScan(table)));
        Benchmark.Result boxed = measure.apply("ROW_BOXED");
        Benchmark.Result tuned = measure.apply("ROW_TUNED");

        System.out.println(boxed);
        System.out.println(tuned);
        if (!boxed.last().equals(tuned.last())) {
            throw new IllegalStateException("engines disagree: " + boxed.last() + " vs " + tuned.last());
        }
        System.out.printf("revenue %s (both engines agree)%n", tuned.last());
        System.out.printf("ROW_TUNED is %.2fx faster than ROW_BOXED%n", boxed.relativeTo(tuned));
    }
}
