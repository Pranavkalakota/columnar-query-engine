package columnar.exec.row;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

class RowScanTest {

    private static final Schema SCHEMA = Schema.of(Field.of("k", DataType.INT64));

    private static InMemoryTable table(int rows, int batchSize) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            text.append(i).append("|\n");
        }
        return InMemoryTable.load(new ByteArrayInputStream(text.toString().getBytes(UTF_8)),
                SCHEMA, DelimitedOptions.tbl(), batchSize);
    }

    private static List<Long> drain(RowScan scan) {
        List<Long> seen = new ArrayList<>();
        scan.open();
        Row row;
        while ((row = scan.next()) != null) {
            seen.add(row.getLong(0));
        }
        scan.close();
        return seen;
    }

    @Test
    @DisplayName("every row comes back exactly once, at every batch size and row count")
    void everyRowExactlyOnce() throws IOException {
        for (int batchSize : new int[] {1, 2, 3, 7, 64}) {
            for (int rows : new int[] {0, 1, batchSize - 1, batchSize, batchSize + 1,
                    2 * batchSize, 2 * batchSize + 1}) {
                List<Long> seen = drain(new RowScan(table(rows, batchSize)));
                String where = "batch " + batchSize + " rows " + rows;
                assertEquals(rows, seen.size(), where);
                for (int i = 0; i < rows; i++) {
                    assertEquals((long) i, seen.get(i), where + " at " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("an empty batch in the middle does not end the stream")
    void emptyBatchDoesNotEndTheStream() {
        VectorBatch first = VectorBatch.allocate(SCHEMA, 2);
        ((LongVector) first.column(0)).set(0, 10L);
        first.setRowCount(1);
        VectorBatch empty = VectorBatch.allocate(SCHEMA, 2);
        empty.setRowCount(0);
        VectorBatch last = VectorBatch.allocate(SCHEMA, 2);
        ((LongVector) last.column(0)).set(0, 20L);
        last.setRowCount(1);
        InMemoryTable table = InMemoryTable.of(SCHEMA, List.of(first, empty, last));
        assertEquals(List.of(10L, 20L), drain(new RowScan(table)),
                "the scan must keep pulling past an empty batch");
    }

    @Test
    @DisplayName("the scan yields one reused cursor, not a row object per row")
    void cursorIsReused() throws IOException {
        RowScan scan = new RowScan(table(3, 2));
        scan.open();
        Row first = scan.next();
        Row second = scan.next();
        assertSame(first, second, "allocating per row is the boxed engine's job, not the scan's");
        assertEquals(1L, second.getLong(0));
        scan.close();
    }

    @Test
    @DisplayName("a scan can be reopened and replays from the start")
    void reopenReplays() throws IOException {
        RowScan scan = new RowScan(table(4, 3));
        assertEquals(List.of(0L, 1L, 2L, 3L), drain(scan));
        assertEquals(List.of(0L, 1L, 2L, 3L), drain(scan), "second pass sees the same rows");
    }

    @Test
    @DisplayName("next before open, or after close, is refused rather than returning null")
    void mustBeOpened() throws IOException {
        RowScan scan = new RowScan(table(2, 2));
        assertThrows(IllegalStateException.class, scan::next);
        scan.open();
        scan.close();
        assertThrows(IllegalStateException.class, scan::next);
    }

    @Test
    @DisplayName("the scan reports the table's schema and an empty table ends immediately")
    void schemaAndEmptyTable() throws IOException {
        RowScan scan = new RowScan(table(0, 4));
        assertEquals(SCHEMA, scan.schema());
        scan.open();
        assertNull(scan.next());
        scan.close();
    }
}
