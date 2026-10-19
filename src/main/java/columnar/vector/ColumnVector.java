package columnar.vector;

import columnar.types.DataType;

/**
 * One column's worth of a batch: a primitive array plus a null bitmap.
 *
 * <p>Not {@code ColumnVector<T>}. Generics erase to {@code Object}, so a
 * generic vector would box every value, which is the exact overhead this
 * engine exists to remove. Instead the base owns null handling and capacity
 * and each subclass exposes its raw primitive array, so a kernel casts once
 * per batch and then runs a tight loop over {@code int[]} or {@code double[]}.
 * Type dispatch happens once per batch rather than once per row because the
 * binder has already resolved every expression's type at plan time.
 *
 * <p>Vectors are mutable and recycled through {@link #reset}. Allocating a
 * fresh array per batch would put the vector path back into the allocator,
 * which is half of what makes the row path slow.
 */
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

    /** Checked once per batch by every kernel to pick a no-null fast path. */
    public final boolean mayHaveNulls() {
        return validity.mayHaveNulls();
    }

    /**
     * Reuses this vector for another batch, growing if needed and clearing
     * every null. Subclasses grow their own storage through
     * {@link #growValues}.
     */
    public final void reset(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("negative capacity: " + capacity);
        }
        growValues(capacity);
        validity.reset(capacity);
        this.capacity = capacity;
    }

    /** Ensures the backing array holds at least {@code capacity} values. */
    protected abstract void growValues(int capacity);

    /**
     * The value at a row as a boxed object, or {@code null}.
     *
     * <p>For the boxed row engine, result materialization, and tests only.
     * Nothing on the vector path may call this: it allocates per value, which
     * is the overhead being measured against.
     */
    public abstract Object valueAt(int index);

    protected final void checkIndex(int index) {
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException("row " + index + " outside 0.." + (capacity - 1));
        }
    }
}
