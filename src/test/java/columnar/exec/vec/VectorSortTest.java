package columnar.exec.vec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.exec.row.Row;
import columnar.exec.row.RowFilter;
import columnar.exec.row.RowScan;
import columnar.exec.row.RowSort;
import columnar.exec.sort.SortKey;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.storage.InMemoryTable;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.DoubleVector;
import columnar.vector.IntVector;
import columnar.vector.LongVector;
import columnar.vector.StringVector;
import columnar.vector.VectorBatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorSortTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("id", DataType.INT64), Field.of("day", DataType.DATE),
            Field.of("flag", DataType.VARCHAR), Field.of("price", DataType.DOUBLE));

    private static InMemoryTable table(Random random, int rows, int batchSize) {
        List<VectorBatch> batches = new ArrayList<>();
        for (int start = 0; start < rows; start += batchSize) {
            int size = Math.min(batchSize, rows - start);
            VectorBatch batch = VectorBatch.allocate(SCHEMA, batchSize);
            for (int r = 0; r < size; r++) {
                ((LongVector) batch.column(0)).set(r, start + r);
                ((IntVector) batch.column(1)).set(r, 8000 + random.nextInt(50));
                ((StringVector) batch.column(2)).append(random.nextBoolean() ? "A" : "R");
                ((DoubleVector) batch.column(3)).set(r, random.nextInt(1000) / 10.0);
                if (random.nextInt(10) == 0) {
                    batch.column(3).setNull(r);
                }
            }
            batch.setRowCount(size);
            batches.add(batch);
        }
        return InMemoryTable.of(SCHEMA, batches);
    }

    private static List<SortKey> keys() {
        return List.of(SortKey.asc("flag"), SortKey.desc("price"), SortKey.asc("id"));
    }

    private static List<Long> vectorIds(VectorOperator source, int batchSize) {
        List<Long> ids = new ArrayList<>();
        try (VectorSort sort = new VectorSort(source, keys(), batchSize)) {
            sort.open();
            VectorBatch batch;
            while ((batch = sort.next()) != null) {
                assertTrue(batch.rowCount() <= batchSize, "a batch must respect the requested size");
                for (int r = 0; r < batch.rowCount(); r++) {
                    ids.add(((LongVector) batch.column(0)).get(r));
                }
            }
        }
        return ids;
    }

    private static List<Long> rowIds(InMemoryTable table, Expr where) {
        List<Long> ids = new ArrayList<>();
        try (RowSort sort = new RowSort(where == null ? new RowScan(table)
                : new RowFilter(new RowScan(table), where), keys())) {
            sort.open();
            Row row;
            while ((row = sort.next()) != null) {
                ids.add(row.getLong(0));
            }
        }
        return ids;
    }

    @Test
    @DisplayName("20,000 rows over many batches sort the same on both engines")
    void agreesWithRowSortAtScale() {
        Random random = new Random(20261121L);
        InMemoryTable table = table(random, 20_000, 1024);
        assertEquals(rowIds(table, null), vectorIds(new VectorScan(table), 1024));
    }

    @Test
    @DisplayName("sorting behind a filter agrees too")
    void agreesBehindAFilter() {
        Random random = new Random(3L);
        InMemoryTable table = table(random, 5_000, 333);
        Expr where = Expr.col("day").binary(BinaryOp.LESS, Expr.date("1991-12-01"));
        assertEquals(rowIds(table, where), vectorIds(new VectorFilter(new VectorScan(table), where), 1024));
    }

    @Test
    @DisplayName("emitting in small batches changes nothing")
    void chunkingIsInvisible() {
        Random random = new Random(5L);
        InMemoryTable table = table(random, 777, 64);
        assertEquals(vectorIds(new VectorScan(table), 1024), vectorIds(new VectorScan(table), 7));
    }

    @Test
    @DisplayName("reopening sorts again from scratch")
    void reopenStartsOver() {
        Random random = new Random(9L);
        InMemoryTable table = table(random, 100, 32);
        VectorSort sort = new VectorSort(new VectorScan(table), keys(), 1024);
        sort.open();
        int first = sort.next().rowCount();
        sort.open();
        int second = sort.next().rowCount();
        sort.close();
        assertEquals(100, first);
        assertEquals(first, second, "a second run must not double the rows");
    }

    @Test
    @DisplayName("an empty input sorts to nothing")
    void emptyInput() {
        Random random = new Random(13L);
        InMemoryTable table = table(random, 50, 16);
        Expr impossible = Expr.col("price").binary(BinaryOp.LESS, Expr.lit(-1.0));
        VectorSort sort = new VectorSort(new VectorFilter(new VectorScan(table), impossible), keys(), 1024);
        sort.open();
        assertNull(sort.next());
        sort.close();
    }

    @Test
    @DisplayName("a sorted DATE still reads as an int for a filter above")
    void rowSortKeepsDatesUsable() {
        Random random = new Random(17L);
        InMemoryTable table = table(random, 200, 64);
        RowSort sorted = new RowSort(new RowScan(table), List.of(SortKey.desc("day")));
        Expr late = Expr.col("day").binary(BinaryOp.GREATER_OR_EQUAL, Expr.date("1991-12-01"));
        try (RowFilter filter = new RowFilter(sorted, late)) {
            filter.open();
            int previous = Integer.MAX_VALUE;
            Row row;
            while ((row = filter.next()) != null) {
                int day = row.getInt(1);
                assertTrue(day <= previous, "still descending after the filter");
                previous = day;
            }
        }
    }

    @Test
    @DisplayName("bad arguments are refused")
    void refusesBadArguments() {
        Random random = new Random(19L);
        InMemoryTable table = table(random, 8, 8);
        assertThrows(IllegalArgumentException.class, () -> new VectorSort(new VectorScan(table), List.of(), 1024));
        assertThrows(IllegalArgumentException.class, () -> new VectorSort(new VectorScan(table), keys(), 0));
        assertThrows(IllegalArgumentException.class, () -> new RowSort(new RowScan(table), List.of()));
        assertThrows(IllegalArgumentException.class, () -> SortKey.asc(" "));
    }
}
