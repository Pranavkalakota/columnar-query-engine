package columnar.exec.vec;

import columnar.storage.InMemoryTable;
import columnar.types.Schema;
import columnar.vector.VectorBatch;

/** Hands out a table's batches without copying them. */
public final class VectorScan implements VectorOperator {

    private final InMemoryTable table;
    private int batchIndex;
    private boolean opened;

    public VectorScan(InMemoryTable table) {
        this.table = table;
    }

    @Override
    public Schema schema() {
        return table.schema();
    }

    @Override
    public void open() {
        batchIndex = 0;
        opened = true;
    }

    /** Clears any selection a previous query left on the batch. */
    @Override
    public VectorBatch next() {
        if (!opened) {
            throw new IllegalStateException("open() before next()");
        }
        if (batchIndex >= table.batchCount()) {
            return null;
        }
        VectorBatch batch = table.batch(batchIndex++);
        batch.clearSelection();
        return batch;
    }

    @Override
    public void close() {
        opened = false;
    }
}
