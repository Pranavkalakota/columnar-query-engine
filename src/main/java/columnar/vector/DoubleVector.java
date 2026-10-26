package columnar.vector;

import columnar.types.DataType;

/** double[] storage for DOUBLE. */
public final class DoubleVector extends ColumnVector {

    private double[] values;

    public DoubleVector(int capacity) {
        super(DataType.DOUBLE, capacity);
        this.values = new double[capacity];
    }

    /** The raw array. */
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

    /** Grows if needed. Does not preserve contents. */
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
