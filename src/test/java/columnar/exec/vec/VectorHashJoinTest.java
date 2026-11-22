package columnar.exec.vec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.storage.InMemoryTable;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.LongVector;
import columnar.vector.StringVector;
import columnar.vector.VectorBatch;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorHashJoinTest {

    /** Rows of id, key, tag; hot means one key. */
    private static InMemoryTable table(String p, int rows, int batchSize, boolean hot) {
        Schema schema = Schema.of(Field.of(p + "_id", DataType.INT64), Field.of(p + "_key", DataType.INT64),
                Field.of(p + "_tag", DataType.VARCHAR));
        List<VectorBatch> batches = new ArrayList<>();
        for (int start = 0; start < rows; start += batchSize) {
            int size = Math.min(batchSize, rows - start);
            VectorBatch batch = VectorBatch.allocate(schema, batchSize);
            for (int r = 0; r < size; r++) {
                ((LongVector) batch.column(0)).set(r, start + r);
                ((LongVector) batch.column(1)).set(r, hot ? 7 : (start + r) % 10);
                ((StringVector) batch.column(2)).append(p + (start + r));
            }
            batch.setRowCount(size);
            batches.add(batch);
        }
        return InMemoryTable.of(schema, batches);
    }

    private static List<long[]> drain(VectorHashJoin join, int batchSize) {
        List<long[]> pairs = new ArrayList<>();
        join.open();
        VectorBatch batch;
        while ((batch = join.next()) != null) {
            assertTrue(batch.rowCount() <= batchSize, "a batch must respect the requested size");
            for (int r = 0; r < batch.rowCount(); r++) {
                pairs.add(new long[] {((LongVector) batch.column(0)).get(r), ((LongVector) batch.column(3)).get(r)});
            }
        }
        join.close();
        return pairs;
    }

    @Test
    @DisplayName("one probe row matching 3,000 build rows spills across many batches")
    void fanOutSpansBatches() {
        InMemoryTable probe = table("p", 5, 5, true);
        InMemoryTable build = table("b", 3_000, 512, true);
        List<long[]> pairs = drain(new VectorHashJoin(new VectorScan(probe), new VectorScan(build),
                List.of("p_key"), List.of("b_key"), 100), 100);
        assertEquals(15_000, pairs.size(), "5 probe rows times 3,000 matches");
        for (int i = 0; i < pairs.size(); i++) {
            assertEquals(i / 3_000, pairs.get(i)[0], "probe rows come out in order");
            assertEquals(i % 3_000, pairs.get(i)[1], "each probe row's matches in build order");
        }
    }

    @Test
    @DisplayName("output batch size never changes which pairs come out")
    void chunkingIsInvisible() {
        InMemoryTable probe = table("p", 300, 64, false);
        InMemoryTable build = table("b", 120, 50, false);
        List<long[]> wide = drain(new VectorHashJoin(new VectorScan(probe), new VectorScan(build),
                List.of("p_key"), List.of("b_key"), 1024), 1024);
        List<long[]> narrow = drain(new VectorHashJoin(new VectorScan(probe), new VectorScan(build),
                List.of("p_key"), List.of("b_key"), 3), 3);
        assertEquals(wide.size(), narrow.size());
        for (int i = 0; i < wide.size(); i++) {
            assertEquals(wide.get(i)[0], narrow.get(i)[0]);
            assertEquals(wide.get(i)[1], narrow.get(i)[1]);
        }
    }

    @Test
    @DisplayName("reopening rebuilds the table rather than doubling it")
    void reopenRebuilds() {
        InMemoryTable probe = table("p", 50, 16, false);
        InMemoryTable build = table("b", 40, 16, false);
        VectorHashJoin join = new VectorHashJoin(new VectorScan(probe), new VectorScan(build),
                List.of("p_key"), List.of("b_key"), 1024);
        int first = drain(join, 1024).size();
        int second = drain(join, 1024).size();
        assertEquals(200, first, "50 probe rows, 4 build matches each");
        assertEquals(first, second);
    }

    @Test
    @DisplayName("an empty probe side ends at once")
    void emptyProbe() {
        InMemoryTable probe = table("p", 0, 16, false);
        InMemoryTable build = table("b", 30, 16, false);
        VectorHashJoin join = new VectorHashJoin(new VectorScan(probe),
                new VectorScan(build), List.of("p_key"), List.of("b_key"), 1024);
        join.open();
        assertNull(join.next());
        join.close();
    }

    @Test
    @DisplayName("the output carries both sides' columns, strings intact")
    void outputSchemaAndStrings() {
        InMemoryTable probe = table("p", 3, 3, true);
        InMemoryTable build = table("b", 2, 2, true);
        VectorHashJoin join = new VectorHashJoin(new VectorScan(probe), new VectorScan(build),
                List.of("p_key"), List.of("b_key"), 1024);
        assertEquals(List.of("p_id", "p_key", "p_tag", "b_id", "b_key", "b_tag"), join.schema().names());
        join.open();
        VectorBatch batch = join.next();
        assertEquals("p2", batch.column(2).valueAt(5));
        assertEquals("b1", batch.column(5).valueAt(5));
        join.close();
        assertThrows(IllegalArgumentException.class, () -> new VectorHashJoin(new VectorScan(probe),
                new VectorScan(build), List.of("p_key"), List.of(), 1024));
    }
}
