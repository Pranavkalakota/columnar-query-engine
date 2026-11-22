package columnar.tpch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.differential.DuckDbOracle;
import columnar.differential.ResultComparator;
import columnar.differential.ResultTable;
import columnar.exec.row.Row;
import columnar.exec.row.RowOperator;
import columnar.exec.row.RowScan;
import columnar.exec.vec.VectorOperator;
import columnar.storage.InMemoryTable;
import columnar.storage.csv.DelimitedOptions;
import columnar.types.Schema;
import columnar.vector.VectorBatch;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TpchQ3Test {

    @TempDir
    static Path dir;
    private static DuckDbOracle oracle;

    @BeforeAll
    static void generate() {
        TpchData.generate(0.01, dir);
        oracle = DuckDbOracle.inMemory();
        for (String table : List.of("customer", "orders", "lineitem")) {
            oracle.registerParquet(table, dir.resolve(table + ".parquet"));
        }
    }

    @AfterAll
    static void close() {
        oracle.close();
    }

    private static InMemoryTable load(String table, Schema schema, int batchSize) throws IOException {
        return InMemoryTable.load(dir.resolve(table + ".tbl"), schema, DelimitedOptions.tbl(), batchSize);
    }

    private static ResultTable drain(VectorOperator op) {
        List<List<Object>> rows = new ArrayList<>();
        try (op) {
            op.open();
            VectorBatch batch;
            while ((batch = op.next()) != null) {
                for (int i = 0; i < batch.selectedCount(); i++) {
                    int r = batch.rowIndex(i);
                    List<Object> values = new ArrayList<>();
                    for (int c = 0; c < batch.schema().size(); c++) {
                        values.add(batch.column(c).valueAt(r));
                    }
                    rows.add(values);
                }
            }
        }
        return ResultTable.of(TpchQ3.COLUMNS, rows);
    }

    private static ResultTable drain(RowOperator op) {
        List<List<Object>> rows = new ArrayList<>();
        try (op) {
            op.open();
            Row row;
            while ((row = op.next()) != null) {
                List<Object> values = new ArrayList<>();
                for (int c = 0; c < op.schema().size(); c++) {
                    values.add(row.get(c));
                }
                rows.add(values);
            }
        }
        return ResultTable.of(TpchQ3.COLUMNS, rows);
    }

    private static void same(ResultTable expected, ResultTable actual, boolean ordered, String where) {
        var diff = new ResultComparator().compare(expected, actual, ordered);
        assertTrue(diff.isEmpty(), where + ": " + diff.orElse(""));
    }

    @Test
    @DisplayName("TPC-H Q3 matches DuckDB in order, on both engines, at every batch size")
    void topTenMatches() throws IOException {
        ResultTable expected = oracle.query(TpchQ3.ORDERED_SQL);
        assertEquals(10, expected.rows().size(), "Q3 returns its top ten");
        for (int batchSize : new int[] {1, 7, 1024}) {
            InMemoryTable customer = load("customer", TpchSchemas.CUSTOMER, batchSize);
            InMemoryTable orders = load("orders", TpchSchemas.ORDERS, batchSize);
            InMemoryTable lineitem = load("lineitem", TpchSchemas.LINEITEM, batchSize);
            same(expected, drain(TpchQ3.vector(customer, orders, lineitem, batchSize)), true,
                    "vector, batch " + batchSize);
            same(expected, drain(TpchQ3.row(new RowScan(customer), new RowScan(orders), new RowScan(lineitem))),
                    true, "row, batch " + batchSize);
        }
    }

    @Test
    @DisplayName("every group matches, not only the ten that survive the limit")
    void everyGroupMatches() throws IOException {
        ResultTable expected = oracle.query(TpchQ3.SQL);
        assertTrue(expected.rows().size() > 10, "enough groups that the limit hides some");
        InMemoryTable customer = load("customer", TpchSchemas.CUSTOMER, 1024);
        InMemoryTable orders = load("orders", TpchSchemas.ORDERS, 1024);
        InMemoryTable lineitem = load("lineitem", TpchSchemas.LINEITEM, 1024);
        same(expected, drain(TpchQ3.vectorGrouped(customer, orders, lineitem, 1024)), false, "vector");
        same(expected, drain(TpchQ3.rowGrouped(new RowScan(customer), new RowScan(orders),
                new RowScan(lineitem))), false, "row");
    }
}
