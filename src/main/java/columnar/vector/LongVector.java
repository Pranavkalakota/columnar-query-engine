package columnar.vector;

import columnar.types.DataType;

/**
 * A {@code long[]}-backed vector for {@link DataType#INT64}.
 *
 * <p>Also the result type of {@code SUM} over any integer column, which is why
 * an aggregate's accumulator array is a {@code long[]} even when the input is
 * an {@code int[]}: summing a few million ints overflows an int long before it
 * overflows the answer.
 */
public final class LongVector extends ColumnVector {

    private long[] values;

    public LongVector(int capacity) {
        super(DataType.INT64, capacity);
        this.values = new long[capacity];
    }

    /** The backing array, for kernels. Longer than {@code capacity} after a shrink. */
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

    /** Reallocates when the array is too small. Contents are not preserved. */
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
