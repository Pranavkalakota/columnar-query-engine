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

/** A table held in memory as batches. */
public final class InMemoryTable {

    private final Schema schema;
    private final List<VectorBatch> batches;
    private final long rowCount;

    private InMemoryTable(Schema schema, List<VectorBatch> batches, long rowCount) {
        this.schema = schema;
        this.batches = Collections.unmodifiableList(batches);
        this.rowCount = rowCount;
    }

    /** Builds a table from existing batches. */
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

    /** The batches, in order. */
    public List<VectorBatch> batches() {
        return batches;
    }
}
