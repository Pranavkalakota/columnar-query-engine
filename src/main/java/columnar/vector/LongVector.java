package columnar.vector;

import columnar.types.DataType;

/** long[] storage for INT64. */
public final class LongVector extends ColumnVector {

    private long[] values;

    public LongVector(int capacity) {
        super(DataType.INT64, capacity);
        this.values = new long[capacity];
    }

    /** The raw array. */
    public long[] values() {
        return values;
    }

    public long get(int index) {
        return values[index];
    }

    public void set(int index, long value) {
        checkIndex(index);
        values[index] = value;
    }

    /** Grows if needed. Does not preserve contents. */
    @Override
    protected void growValues(int capacity) {
        if (values.length < capacity) {
            values = new long[capacity];
        }
    }

    @Override
    public Object valueAt(int index) {
        checkIndex(index);
        return isNull(index) ? null : values[index];
    }
}
