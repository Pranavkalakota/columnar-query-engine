package columnar.exec.vec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import columnar.exec.row.Row;
import columnar.exec.row.RowFilter;
import columnar.exec.row.RowLimit;
import columnar.exec.row.RowOperator;
import columnar.exec.row.RowScan;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.storage.InMemoryTable;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.IntVector;
import columnar.vector.VectorBatch;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorLimitTest {

    private static final Schema SCHEMA = Schema.of(Field.of("n", DataType.INT32));

    private static InMemoryTable table(int rows, int batchSize) {
        List<VectorBatch> batches = new ArrayList<>();
        for (int start = 0; start < rows; start += batchSize) {
            int size = Math.min(batchSize, rows - start);
            VectorBatch batch = VectorBatch.allocate(SCHEMA, batchSize);
            for (int r = 0; r < size; r++) {
                ((IntVector) batch.column(0)).set(r, start + r);
            }
            batch.setRowCount(size);
            batches.add(batch);
        }
        return InMemoryTable.of(SCHEMA, batches);
    }

    private static Expr from15() {
        return Expr.col("n").binary(BinaryOp.GREATER_OR_EQUAL, Expr.lit(15L));
    }

    private static List<Integer> vector(VectorOperator source, long limit) {
        List<Integer> seen = new ArrayList<>();
        try (VectorLimit op = new VectorLimit(source, limit)) {
            op.open();
            VectorBatch batch;
            while ((batch = op.next()) != null) {
                for (int i = 0; i < batch.selectedCount(); i++) {
                    seen.add(((IntVector) batch.column(0)).get(batch.rowIndex(i)));
                }
            }
        }
        return seen;
    }

    private static List<Integer> row(RowOperator source, long limit) {
        List<Integer> seen = new ArrayList<>();
        try (RowLimit op = new RowLimit(source, limit)) {
            op.open();
            Row row;
            while ((row = op.next()) != null) {
                seen.add(row.getInt(0));
            }
        }
        return seen;
    }

    @Test
    @DisplayName("every limit from zero past the end agrees on both engines")
    void agreesAtEveryBoundary() {
        InMemoryTable table = table(100, 32);
        for (long limit : new long[] {0, 1, 31, 32, 33, 64, 99, 100, 101, 1_000}) {
            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, 100); i++) {
                expected.add(i);
            }
            assertEquals(expected, vector(new VectorScan(table), limit), "vector, limit " + limit);
            assertEquals(expected, row(new RowScan(table), limit), "row, limit " + limit);
        }
    }

    @Test
    @DisplayName("a limit over a filter trims the filter's selection")
    void trimsASelection() {
        InMemoryTable table = table(100, 32);
        List<Integer> expected = new ArrayList<>();
        for (int i = 15; i < 55; i++) {
            expected.add(i);
        }
        assertEquals(expected, row(new RowFilter(new RowScan(table), from15()), 40));
        assertEquals(expected, vector(new VectorFilter(new VectorScan(table), from15()), 40));
    }

    @Test
    @DisplayName("once the limit is reached the child is not pulled again")
    void stopsPulling() {
        InMemoryTable table = table(100, 10);
        int[] pulls = {0};
        VectorScan scan = new VectorScan(table);
        VectorOperator counting = new VectorOperator() {
            @Override
            public Schema schema() {
                return scan.schema();
            }

            @Override
            public void open() {
                scan.open();
            }

            @Override
            public VectorBatch next() {
                pulls[0]++;
                return scan.next();
            }

            @Override
            public void close() {
                scan.close();
            }
        };
        vector(counting, 15);
        assertEquals(2, pulls[0], "15 rows need exactly two batches of ten");
    }

    @Test
    @DisplayName("a negative limit is refused")
    void refusesNegative() {
        InMemoryTable table = table(5, 5);
        assertThrows(IllegalArgumentException.class, () -> new VectorLimit(new VectorScan(table), -1));
        assertThrows(IllegalArgumentException.class, () -> new RowLimit(new RowScan(table), -1));
    }
}
