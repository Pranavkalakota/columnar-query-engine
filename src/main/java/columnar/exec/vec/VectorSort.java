package columnar.exec.vec;

import columnar.exec.sort.IntSort;
import columnar.exec.sort.SortKey;
import columnar.types.Schema;
import columnar.vector.VectorBatch;
import java.util.List;

/** Batch ORDER BY: buffers columns, sorts a permutation, gathers. */
public final class VectorSort implements VectorOperator {

    private final VectorOperator child;
    private final List<SortKey> keys;
    private final int[] keyColumns;
    private final boolean[] descending;
    private final int batchSize;
    private ColumnBuffer[] buffers;
    private int[] order = new int[0];
    private int rows;
    private int emitted;
    private boolean drained;

    public VectorSort(VectorOperator child, List<SortKey> keys, int batchSize) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("a sort needs at least one key");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be positive: " + batchSize);
        }
        this.child = child;
        this.keys = List.copyOf(keys);
        this.batchSize = batchSize;
        Schema schema = child.schema();
        this.keyColumns = new int[this.keys.size()];
        this.descending = new boolean[this.keys.size()];
        for (int k = 0; k < keyColumns.length; k++) {
            keyColumns[k] = schema.requireIndexOf(this.keys.get(k).column());
            descending[k] = this.keys.get(k).descending();
        }
        this.buffers = freshBuffers();
    }

    @Override
    public Schema schema() {
        return child.schema();
    }

    @Override
    public void open() {
        child.open();
        buffers = freshBuffers();
        rows = 0;
        emitted = 0;
        drained = false;
    }

    /** Sorts on the first call, then emits in batches. */
    @Override
    public VectorBatch next() {
        if (!drained) {
            drain();
            sort();
            drained = true;
        }
        if (emitted >= rows) {
            return null;
        }
        int n = Math.min(batchSize, rows - emitted);
        VectorBatch out = VectorBatch.allocate(schema(), n);
        out.setRowCount(n);
        for (int c = 0; c < buffers.length; c++) {
            buffers[c].gather(order, emitted, n, out.column(c));
        }
        emitted += n;
        return out;
    }

    private ColumnBuffer[] freshBuffers() {
        Schema schema = child.schema();
        ColumnBuffer[] fresh = new ColumnBuffer[schema.size()];
        for (int c = 0; c < fresh.length; c++) {
            fresh[c] = ColumnBuffer.create(schema.type(c));
        }
        return fresh;
    }

    /** Copies everything, so upstream may reuse its batches. */
    private void drain() {
        VectorBatch batch;
        while ((batch = child.next()) != null) {
            int n = batch.selectedCount();
            for (int c = 0; c < buffers.length; c++) {
                buffers[c].appendAll(batch.column(c), batch.selection(), n);
            }
            rows += n;
        }
    }

    private void sort() {
        if (order.length < rows) {
            order = new int[rows];
        }
        for (int i = 0; i < rows; i++) {
            order[i] = i;
        }
        IntSort.sort(order, rows, this::compareRows);
    }

    /** NULLs last whichever way the key points. */
    private int compareRows(int i, int j) {
        for (int k = 0; k < keyColumns.length; k++) {
            ColumnBuffer buffer = buffers[keyColumns[k]];
            boolean iNull = buffer.isNull(i);
            boolean jNull = buffer.isNull(j);
            if (iNull || jNull) {
                if (iNull && jNull) {
                    continue;
                }
                return iNull ? 1 : -1;
            }
            int c = buffer.compare(i, j);
            if (c != 0) {
                return descending[k] ? -c : c;
            }
        }
        return 0;
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public String toString() {
        return "VectorSort" + keys;
    }
}
