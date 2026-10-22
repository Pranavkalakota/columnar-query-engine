package columnar.exec.row;

import columnar.storage.InMemoryTable;
import columnar.types.Schema;
import columnar.vector.VectorBatch;

/** Reads an in-memory table one row at a time. */
public final class RowScan implements RowOperator {

    private final InMemoryTable table;
    private BatchRow cursor;
    private int batchIndex;
    private int rowInBatch;
    private boolean opened;

    public RowScan(InMemoryTable table) {
        this.table = table;
    }

    @Override
    public Schema schema() {
        return table.schema();
    }

    @Override
    public void open() {
        batchIndex = 0;
        rowInBatch = 0;
        cursor = null;
        opened = true;
    }

    /** Advances past exhausted batches, so an empty one is not end of stream. */
    @Override
    public Row next() {
        if (!opened) {
            throw new IllegalStateException("open() before next()");
        }
        while (batchIndex < table.batchCount()) {
            VectorBatch batch = table.batch(batchIndex);
            if (rowInBatch < batch.rowCount()) {
                if (cursor == null) {
                    cursor = new BatchRow(batch);
                } else if (rowInBatch == 0) {
                    cursor.reset(batch);
                }
                cursor.position(rowInBatch++);
                return cursor;
            }
            batchIndex++;
            rowInBatch = 0;
        }
        return null;
    }

    @Override
    public void close() {
        cursor = null;
        opened = false;
    }
}
