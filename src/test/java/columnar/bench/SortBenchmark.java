package columnar.bench;

import columnar.exec.row.BoxedRowScan;
import columnar.exec.row.Row;
import columnar.exec.row.RowOperator;
import columnar.exec.row.RowScan;
import columnar.exec.row.RowSort;
import columnar.exec.sort.SortKey;
import columnar.exec.vec.VectorScan;
import columnar.exec.vec.VectorSort;
import columnar.storage.InMemoryTable;
import columnar.storage.csv.DelimitedOptions;
import columnar.vector.LongVector;
import columnar.vector.VectorBatch;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/** Times a full ORDER BY of lineitem on each engine. */
public final class SortBenchmark {

    private SortBenchmark() {
    }

    /** Ends on the primary key, so the order is total. */
    public static final List<SortKey> ORDER = List.of(SortKey.asc("l_shipdate"),
            SortKey.desc("l_extendedprice"), SortKey.asc("l_orderkey"), SortKey.asc("l_linenumber"));

    /** Order-sensitive, so equal checksums mean equal order. */
    private static long mix(long hash, long orderKey, long lineNumber) {
        return hash * 1_000_003L + orderKey * 8 + lineNumber;
    }

    public static Object runRow(RowOperator scan) {
        long hash = 17;
        try (RowSort sort = new RowSort(scan, ORDER)) {
            sort.open();
            Row row;
            while ((row = sort.next()) != null) {
                hash = mix(hash, row.getLong(0), row.getLong(3));
            }
        }
        return hash;
    }

    public static Object runVector(InMemoryTable table) {
        long hash = 17;
        try (VectorSort sort = new VectorSort(new VectorScan(table), ORDER, 1024)) {
            sort.open();
            VectorBatch batch;
            while ((batch = sort.next()) != null) {
                LongVector orderKey = (LongVector) batch.column(0);
                LongVector lineNumber = (LongVector) batch.column(3);
                for (int r = 0; r < batch.rowCount(); r++) {
                    hash = mix(hash, orderKey.get(r), lineNumber.get(r));
                }
            }
        }
        return hash;
    }

    public static void main(String[] args) throws IOException {
        Path tbl = Path.of(args.length > 0 ? args[0] : "data/sf0.1").resolve("lineitem.tbl");
        int warmups = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int runs = args.length > 2 ? Integer.parseInt(args[2]) : 11;

        InMemoryTable table = InMemoryTable.load(tbl, Q6Benchmark.LINEITEM, DelimitedOptions.tbl(), 1024);
        System.out.println(Benchmark.environment());
        System.out.printf("%s: %,d rows, ORDER BY %s%n", tbl, table.rowCount(), ORDER);

        Function<String, Benchmark.Result> measure = name -> Benchmark.measure(name, warmups, runs,
                () -> runRow(name.equals("ROW_BOXED") ? new BoxedRowScan(table) : new RowScan(table)));
        Benchmark.Result boxed = measure.apply("ROW_BOXED");
        Benchmark.Result tuned = measure.apply("ROW_TUNED");
        Benchmark.Result vector = Benchmark.measure("VECTOR", warmups, runs, () -> runVector(table));

        System.out.println(boxed);
        System.out.println(tuned);
        System.out.println(vector);
        if (!boxed.last().equals(tuned.last()) || !tuned.last().equals(vector.last())) {
            throw new IllegalStateException("engines disagree on order: "
                    + boxed.last() + " " + tuned.last() + " " + vector.last());
        }
        System.out.printf("order checksum %s (all three engines agree)%n", vector.last());
        System.out.printf("VECTOR is %.2fx faster than ROW_TUNED%n", tuned.relativeTo(vector));
        System.out.printf("VECTOR is %.2fx faster than ROW_BOXED%n", boxed.relativeTo(vector));
    }
}
