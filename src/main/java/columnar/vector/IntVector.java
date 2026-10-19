package columnar.vector;

import columnar.types.DataType;
import java.time.LocalDate;

/**
 * An {@code int[]}-backed vector, used for both {@link DataType#INT32} and
 * {@link DataType#DATE}.
 *
 * <p>A DATE is days since 1970-01-01, negative before it, so it needs no
 * storage of its own. Sharing the array means date filters compile to the same
 * integer comparison kernels as any other int column, which is the whole
 * reason to represent dates this way rather than as objects.
 */
public final class IntVector extends ColumnVector {

    private int[] values;

    private IntVector(DataType type, int capacity) {
        super(type, capacity);
        this.values = new int[capacity];
    }

    public static IntVector ints(int capacity) {
        return new IntVector(DataType.INT32, capacity);
    }

    /** A DATE vector, stored as days since the epoch. */
    public static IntVector dates(int capacity) {
        return new IntVector(DataType.DATE, capacity);
    }

    /** The backing array, for kernels. Longer than {@code capacity} after a shrink. */
    public int[] values() {
        return values;
    }

    public int get(int index) {
        return values[index];
    }

    public void set(int index, int value) {
        checkIndex(index);
        values[index] = value;
    }

    /** Reallocates when the array is too small. Contents are not preserved. */
    @Override
    protected void growValues(int capacity) {
        if (values.length < capacity) {
            values = new int[capacity];
        }
    }

    @Override
    public Object valueAt(int index) {
        checkIndex(index);
        if (isNull(index)) {
            return null;
        }
        return type() == DataType.DATE ? LocalDate.ofEpochDay(values[index]) : values[index];
    }
}
