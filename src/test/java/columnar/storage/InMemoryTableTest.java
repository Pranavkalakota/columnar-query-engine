package columnar.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.storage.csv.DelimitedOptions;
import columnar.tpch.TpchData;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.VectorBatch;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InMemoryTableTest {

    private static final Schema ONE_LONG = Schema.of(Field.of("k", DataType.INT64));

    private static InMemoryTable load(String text, int batchSize) throws IOException {
        return InMemoryTable.load(new ByteArrayInputStream(text.getBytes(UTF_8)),
                ONE_LONG, DelimitedOptions.tbl(), batchSize);
    }

    private static String rows(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(i).append("|\n");
        }
        return text.toString();
    }

    @Test
    @DisplayName("every batch holds its own chunk, not N references to the recycled last one")
    void batchesHoldDistinctData() throws IOException {
        // If the loader's recycled batch were kept, all three would read as the last chunk.
        InMemoryTable table = load(rows(7), 3);
        assertEquals(3, table.batchCount());
        assertEquals(7, table.rowCount());
        List<Object> seen = new ArrayList<>();
        for (VectorBatch batch : table.batches()) {
            for (int row = 0; row < batch.rowCount(); row++) {
                seen.add(batch.column(0).valueAt(row));
            }
        }
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L), seen);
    }

    @Test
    @DisplayName("row and batch counts hold at every batch size, with a short final batch")
    void countsAcrossBatchSizes() throws IOException {
        for (int batchSize : new int[] {1, 2, 3, 7, 64}) {
            for (int rowCount : new int[] {0, 1, batchSize, batchSize + 1, 2 * batchSize + 1}) {
                InMemoryTable table = load(rows(rowCount), batchSize);
                String where = "batch " + batchSize + " rows " + rowCount;
                assertEquals(rowCount, table.rowCount(), where);
                int expectedBatches = (rowCount + batchSize - 1) / batchSize;
                assertEquals(expectedBatches, table.batchCount(), where);
            }
        }
    }

    @Test
    @DisplayName("an empty file makes an empty table, not a table with one empty batch")
    void emptyFile() throws IOException {
        InMemoryTable table = load("", 4);
        assertEquals(0, table.rowCount());
        assertEquals(0, table.batchCount());
        assertEquals(ONE_LONG, table.schema());
    }

    @Test
    @DisplayName("the batch list cannot be modified by a caller")
    void batchListIsUnmodifiable() throws IOException {
        InMemoryTable table = load(rows(2), 4);
        assertThrows(UnsupportedOperationException.class, () -> table.batches().clear());
    }

    @Test
    @DisplayName("a non-positive batch size is refused")
    void rejectsBadBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> load(rows(1), 0));
        assertThrows(IllegalArgumentException.class, () -> load(rows(1), -1));
    }

    @Test
    @DisplayName("a real generated region.tbl loads, trailing pipe and all")
    void loadsGeneratedTpchFile(@TempDir Path dir) throws IOException {
        TpchData.generate(0.01, dir);
        Schema region = Schema.of(
                Field.of("r_regionkey", DataType.INT32),
                Field.of("r_name", DataType.VARCHAR),
                Field.of("r_comment", DataType.VARCHAR));
        InMemoryTable table = InMemoryTable.load(
                dir.resolve("region.tbl"), region, DelimitedOptions.tbl(), 2);
        assertEquals(5, table.rowCount(), "TPC-H fixes region at 5 rows");
        assertEquals(3, table.batchCount());
        List<Object> names = new ArrayList<>();
        for (VectorBatch batch : table.batches()) {
            for (int row = 0; row < batch.rowCount(); row++) {
                names.add(batch.column("r_name").valueAt(row));
            }
        }
        assertTrue(names.contains("AFRICA"), names.toString());
        assertEquals(5, names.size());
    }
}
