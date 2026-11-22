package columnar.exec.vec;

import columnar.types.Schema;
import columnar.vector.VectorBatch;

/** Passes the first n rows, trimming by selection. */
public final class VectorLimit implements VectorOperator {

    private final VectorOperator child;
    private final long limit;
    private long emitted;
    private int[] prefix = new int[0];

    public VectorLimit(VectorOperator child, long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("a limit cannot be negative: " + limit);
        }
        this.child = child;
        this.limit = limit;
    }

    @Override
    public Schema schema() {
        return child.schema();
    }

    @Override
    public void open() {
        child.open();
        emitted = 0;
    }

    @Override
    public VectorBatch next() {
        if (emitted >= limit) {
            return null;
        }
        VectorBatch batch;
        while ((batch = child.next()) != null) {
            int rows = batch.selectedCount();
            if (rows == 0) {
                continue;
            }
            long remaining = limit - emitted;
            if (rows > remaining) {
                keepFirst(batch, (int) remaining);
            }
            emitted += batch.selectedCount();
            return batch;
        }
        return null;
    }

    /** Shortens the selection; no row data moves. */
    private void keepFirst(VectorBatch batch, int k) {
        if (batch.hasSelection()) {
            batch.select(batch.selection(), k);
            return;
        }
        if (prefix.length < k) {
            prefix = new int[k];
            for (int i = 0; i < k; i++) {
                prefix[i] = i;
            }
        }
        batch.select(prefix, k);
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public String toString() {
        return "VectorLimit(" + limit + ")";
    }
}
