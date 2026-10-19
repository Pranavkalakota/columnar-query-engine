package columnar.vector;

import columnar.types.DataType;

/**
 * A {@code double[]}-backed vector for {@link DataType#DOUBLE}.
 *
 * <p>Every TPC-H money and quantity column lands here, because DECIMAL is cast
 * to DOUBLE at load time. That has a consequence worth stating plainly: double
 * addition is not associative, so a sum depends on the order rows are visited,
 * and the vectorized and row engines will not agree bit for bit on large sums.
 * Result comparison uses a relative tolerance for exactly this reason.
 *
 * <p>Storage keeps NaN, the infinities, and {@code -0.0} exactly as written.
 * That matters later: {@code -0.0 == 0.0} is true while their bit patterns
 * differ, and NaN compares false against everything including itself, so the
 * zone maps in the file format cannot use plain min/max on a column that holds
 * either one.
 */
public final class DoubleVector extends ColumnVector {

    private double[] values;

    public DoubleVector(int capacity) {
        super(DataType.DOUBLE, capacity);
        this.values = new double[capacity];
    }

    /** The backing array, for kernels. Longer than {@code capacity} after a shrink. */
    public double[] values() {
        return values;
    }

    public double get(int index) {
        return values[index];
    }

    public void set(int index, double value) {
        checkIndex(index);
        values[index] = value;
    }

    /** Reallocates when the array is too small. Contents are not preserved. */
    @Override
    protected void growValues(int capacity) {
        if (values.length < capacity) {
            values = new double[capacity];
        }
    }

    @Override
    public Object valueAt(int index) {
        checkIndex(index);
        return isNull(index) ? null : values[index];
    }
}
