package columnar.storage;

import columnar.storage.csv.DelimitedLoader;
import columnar.storage.csv.DelimitedOptions;
import columnar.types.Schema;
import columnar.vector.VectorBatch;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A table held entirely in memory as a list of {@link VectorBatch}es.
 *
 * <p>This is what lets a benchmark measure execution rather than I/O: the file
 * is parsed once, then the same batches are scanned repeatedly by the row and
 * vector engines so the only thing differing between runs is the execution
 * model.
 *
 * <p><b>Why loading allocates a batch per chunk.</b> {@link DelimitedLoader}
 * recycles one batch, resetting it each call, which is exactly right for
 * streaming and exactly wrong for materializing: keeping a reference to a
 * recycled batch would hand back the last chunk N times. So this allocates a
 * fresh batch per chunk and accepts the memory, which is the whole point of an
 * in-memory table.
 *
 * <p><b>Ownership.</b> The batches belong to the table and outlive any one
 * query, so a consumer must treat the column data as immutable. An operator may
 * set a selection vector on a batch, but that makes the batch unsafe to share
 * between two scans at once; parallel scans would need their own selections,
 * which is only noted here.
 */
public final class InMemoryTable {

    private final Schema schema;
    private final List<VectorBatch> batches;
    private final long rowCount;

    private InMemoryTable(Schema schema, List<VectorBatch> batches, long rowCount) {
        this.schema = schema;
        this.batches = Collections.unmodifiableList(batches);
        this.rowCount = rowCount;
    }

    /** Builds a table of exactly these batches, for tests and for generated data. */
    public static InMemoryTable of(Schema schema, List<VectorBatch> batches) {
        long rows = 0;
        for (VectorBatch batch : batches) {
            rows += batch.rowCount();
        }
        return new InMemoryTable(schema, new ArrayList<>(batches), rows);
    }

    /** Reads a delimited file fully into memory. */
    public static InMemoryTable load(Path file, Schema schema, DelimitedOptions options, int batchSize)
            throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in, schema, options, batchSize);
        }
    }

    public static InMemoryTable load(InputStream in, Schema schema, DelimitedOptions options, int batchSize)
            throws IOException {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be positive: " + batchSize);
        }
        List<VectorBatch> batches = new ArrayList<>();
        long rows = 0;
        try (DelimitedLoader loader = new DelimitedLoader(in, schema, options)) {
            while (true) {
                VectorBatch batch = VectorBatch.allocate(schema, batchSize);
                if (!loader.loadInto(batch)) {
                    break;
                }
                rows += batch.rowCount();
                batches.add(batch);
            }
        }
        return new InMemoryTable(schema, batches, rows);
    }

    public Schema schema() {
        return schema;
    }

    /** Total rows across every batch. */
    public long rowCount() {
        return rowCount;
    }

    public int batchCount() {
        return batches.size();
    }

    public VectorBatch batch(int index) {
        return batches.get(index);
    }

    /** The batches, in order. Unmodifiable, though the batches themselves are not. */
    public List<VectorBatch> batches() {
        return batches;
    }
}
