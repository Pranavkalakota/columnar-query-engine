package columnar.vector;

import columnar.types.DataType;

/** One column's worth of a batch. */
public abstract class ColumnVector {

    private final DataType type;
    private final Validity validity;
    private int capacity;

    protected ColumnVector(DataType type, int capacity) {
        if (type == null) {
            throw new IllegalArgumentException("a vector needs a type");
        }
        this.type = type;
        this.capacity = capacity;
        this.validity = Validity.allValid(capacity);
    }

    public final DataType type() {
        return type;
    }

    public final int capacity() {
        return capacity;
    }

    /** The null bitmap, for kernels that handle nulls directly. */
    public final Validity validity() {
        return validity;
    }

    public final boolean isNull(int index) {
        return validity.isNull(index);
    }

    public final void setNull(int index) {
        validity.setNull(index);
    }

    /** Checked once per batch to pick a no-null fast path. */
    public final boolean mayHaveNulls() {
        return validity.mayHaveNulls();
    }

    /** Reuses this vector for another batch. */
    public final void reset(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("negative capacity: " + capacity);
        }
        growValues(capacity);
        validity.reset(capacity);
        this.capacity = capacity;
    }

    /** Ensures the backing array holds at least capacity values. */
    protected abstract void growValues(int capacity);

    /** Boxed value at a row, or null. */
    public abstract Object valueAt(int index);

    protected final void checkIndex(int index) {
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException("row " + index + " outside 0.." + (capacity - 1));
        }
    }
}
