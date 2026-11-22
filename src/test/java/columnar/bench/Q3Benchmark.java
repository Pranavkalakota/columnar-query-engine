package columnar.bench;

import columnar.exec.row.BoxedRowScan;
import columnar.exec.row.Row;
import columnar.exec.row.RowOperator;
import columnar.exec.row.RowScan;
import columnar.exec.vec.VectorOperator;
import columnar.storage.InMemoryTable;
import columnar.storage.csv.DelimitedOptions;
import columnar.tpch.TpchQ3;
import columnar.tpch.TpchSchemas;
import columnar.vector.VectorBatch;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Times TPC-H Q3 on each engine. */
public final class Q3Benchmark {

    private Q3Benchmark() {
    }

    public static Object runRow(RowOperator customer, RowOperator orders, RowOperator lineitem) {
        List<String> rows = new ArrayList<>();
        try (RowOperator op = TpchQ3.row(customer, orders, lineitem)) {
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
        return rows;
    }

    public static Object runVector(InMemoryTable customer, InMemoryTable orders, InMemoryTable lineitem) {
        List<String> rows = new ArrayList<>();
        try (VectorOperator op = TpchQ3.vector(customer, orders, lineitem, 1024)) {
            op.open();
            VectorBatch batch;
            while ((batch = op.next()) != null) {
                for (int i = 0; i < batch.selectedCount(); i++) {
                    int r = batch.rowIndex(i);
                    StringBuilder line = new StringBuilder();
                    for (int c = 0; c < batch.schema().size(); c++) {
                        line.append(batch.column(c).valueAt(r)).append('|');
                    }
                    rows.add(line.toString());
                }
            }
        }
        return rows;
    }

    public static void main(String[] args) throws IOException {
        Path data = Path.of(args.length > 0 ? args[0] : "data/sf0.1");
        int warmups = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int runs = args.length > 2 ? Integer.parseInt(args[2]) : 15;

        InMemoryTable customer = InMemoryTable.load(data.resolve("customer.tbl"), TpchSchemas.CUSTOMER,
                DelimitedOptions.tbl(), 1024);
        InMemoryTable orders = InMemoryTable.load(data.resolve("orders.tbl"), TpchSchemas.ORDERS,
                DelimitedOptions.tbl(), 1024);
        InMemoryTable lineitem = InMemoryTable.load(data.resolve("lineitem.tbl"), TpchSchemas.LINEITEM,
                DelimitedOptions.tbl(), 1024);
        System.out.println(Benchmark.environment());
        System.out.printf("%s: customer %,d, orders %,d, lineitem %,d rows%n",
                data, customer.rowCount(), orders.rowCount(), lineitem.rowCount());

        Function<String, Benchmark.Result> measure = name -> Benchmark.measure(name, warmups, runs,
                () -> name.equals("ROW_BOXED")
                        ? runRow(new BoxedRowScan(customer), new BoxedRowScan(orders), new BoxedRowScan(lineitem))
                        : runRow(new RowScan(customer), new RowScan(orders), new RowScan(lineitem)));
        Benchmark.Result boxed = measure.apply("ROW_BOXED");
        Benchmark.Result tuned = measure.apply("ROW_TUNED");
        Benchmark.Result vector = Benchmark.measure("VECTOR", warmups, runs,
                () -> runVector(customer, orders, lineitem));

        System.out.println(boxed);
        System.out.println(tuned);
        System.out.println(vector);
        if (!boxed.last().equals(tuned.last()) || !tuned.last().equals(vector.last())) {
            throw new IllegalStateException("engines disagree:\n" + boxed.last() + "\n" + tuned.last()
                    + "\n" + vector.last());
        }
        System.out.printf("%d rows, all three engines agree%n", ((List<?>) vector.last()).size());
        System.out.printf("ROW_TUNED is %.2fx faster than ROW_BOXED%n", boxed.relativeTo(tuned));
        System.out.printf("VECTOR is %.2fx faster than ROW_TUNED%n", tuned.relativeTo(vector));
        System.out.printf("VECTOR is %.2fx faster than ROW_BOXED%n", boxed.relativeTo(vector));
    }
}
