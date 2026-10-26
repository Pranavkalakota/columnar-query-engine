package columnar.exec.row;

import columnar.types.Schema;
import columnar.vector.BooleanVector;
import columnar.vector.ColumnVector;
import columnar.vector.DoubleVector;
import columnar.vector.IntVector;
import columnar.vector.LongVector;
import columnar.vector.StringVector;
import columnar.vector.VectorBatch;

/** A reused cursor over one row of a batch. */
public final class BatchRow implements Row {

    private VectorBatch batch;
    private ColumnVector[] columns;
    private int row = -1;

    public BatchRow(VectorBatch batch) {
        reset(batch);
    }

    /** Points this cursor at another batch and rewinds it. */
    public void reset(VectorBatch batch) {
        this.batch = batch;
        this.columns = new ColumnVector[batch.schema().size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = batch.column(i);
        }
        this.row = -1;
    }

    /** Moves to a physical row of the batch. */
    public void position(int row) {
        if (row < 0 || row >= batch.rowCount()) {
            throw new IndexOutOfBoundsException("row " + row + " of " + batch.rowCount());
        }
        this.row = row;
    }

    public int position() {
        return row;
    }

    @Override
    public Schema schema() {
        return batch.schema();
    }

    @Override
    public boolean isNull(int column) {
        return columns[column].isNull(row);
    }

    @Override
    public int getInt(int column) {
        return ((IntVector) columns[column]).get(row);
    }

    @Override
    public long getLong(int column) {
        return ((LongVector) columns[column]).get(row);
    }

    @Override
    public double getDouble(int column) {
        return ((DoubleVector) columns[column]).get(row);
    }

    @Override
    public boolean getBoolean(int column) {
        return ((BooleanVector) columns[column]).get(row);
    }

    @Override
    public String getString(int column) {
        return ((StringVector) columns[column]).getString(row);
    }

    @Override
    public Object get(int column) {
        return columns[column].valueAt(row);
    }
}
