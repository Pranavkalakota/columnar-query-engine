package columnar.exec.vec;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import columnar.storage.InMemoryTable;
import columnar.storage.csv.DelimitedOptions;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.LongVector;
import columnar.vector.VectorBatch;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorScanTest {

    private static final Schema SCHEMA = Schema.of(Field.of("k", DataType.INT64));

    private static InMemoryTable table(int rows, int batchSize) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            text.append(i).append('\n');
        }
        return InMemoryTable.load(new ByteArrayInputStream(text.toString().getBytes(UTF_8)),
                SCHEMA, DelimitedOptions.csv(), batchSize);
    }

    private static List<Long> drain(VectorScan scan) {
        List<Long> seen = new ArrayList<>();
        scan.open();
        VectorBatch batch;
        while ((batch = scan.next()) != null) {
            LongVector k = (LongVector) batch.column(0);
            for (int i = 0; i < batch.selectedCount(); i++) {
                seen.add(k.get(batch.rowIndex(i)));
            }
        }
        scan.close();
        return seen;
    }

    @Test
    @DisplayName("every row comes back exactly once, at every batch size and row count")
    void everyRowExactlyOnce() throws IOException {
        for (int batchSize : new int[] {1, 2, 3, 7, 64}) {
            for (int rows : new int[] {0, 1, batchSize - 1, batchSize, batchSize + 1, 2 * batchSize + 1}) {
                List<Long> seen = drain(new VectorScan(table(rows, batchSize)));
                String where = "batch " + batchSize + " rows " + rows;
                assertEquals(rows, seen.size(), where);
                for (int i = 0; i < rows; i++) {
                    assertEquals((long) i, seen.get(i), where + " at " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("the batch is handed over, not copied")
    void handsOverTheSameBatch() throws IOException {
        InMemoryTable table = table(6, 2);
        VectorScan scan = new VectorScan(table);
        scan.open();
        assertSame(table.batch(0), scan.next(), "no copy; that is the point of the vector path");
        assertSame(table.batch(1), scan.next());
        scan.close();
    }

    @Test
    @DisplayName("a selection left by an earlier query is cleared")
    void clearsStaleSelection() throws IOException {
        InMemoryTable table = table(4, 4);
        table.batch(0).select(new int[] {1}, 1);
        VectorScan scan = new VectorScan(table);
        scan.open();
        VectorBatch batch = scan.next();
        assertFalse(batch.hasSelection(), "a stale selection would hide rows");
        assertEquals(4, batch.selectedCount());
        scan.close();
    }

    @Test
    @DisplayName("an empty batch is yielded, not treated as the end")
    void emptyBatchIsNotTheEnd() {
        VectorBatch first = VectorBatch.allocate(SCHEMA, 2);
        ((LongVector) first.column(0)).set(0, 10L);
        first.setRowCount(1);
        VectorBatch empty = VectorBatch.allocate(SCHEMA, 2);
        empty.setRowCount(0);
        VectorBatch last = VectorBatch.allocate(SCHEMA, 2);
        ((LongVector) last.column(0)).set(0, 20L);
        last.setRowCount(1);
        InMemoryTable table = InMemoryTable.of(SCHEMA, List.of(first, empty, last));
        assertEquals(List.of(10L, 20L), drain(new VectorScan(table)));
    }

    @Test
    @DisplayName("a scan replays on reopen, and an empty table ends at once")
    void reopenAndEmpty() throws IOException {
        VectorScan scan = new VectorScan(table(5, 2));
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), drain(scan));
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), drain(scan));
        VectorScan none = new VectorScan(table(0, 4));
        none.open();
        assertNull(none.next());
        none.close();
    }

    @Test
    @DisplayName("next before open is refused, and the schema comes from the table")
    void mustBeOpened() throws IOException {
        VectorScan scan = new VectorScan(table(2, 2));
        assertThrows(IllegalStateException.class, scan::next);
        assertEquals(SCHEMA, scan.schema());
    }
}
