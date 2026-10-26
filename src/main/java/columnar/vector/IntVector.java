package columnar.vector;

import columnar.types.DataType;
import java.time.LocalDate;

/** int[] storage, shared by INT32 and DATE. */
public final class IntVector extends ColumnVector {

    private int[] values;

    private IntVector(DataType type, int capacity) {
        super(type, capacity);
        this.values = new int[capacity];
    }

    public static IntVector ints(int capacity) {
        return new IntVector(DataType.INT32, capacity);
    }

    /** A DATE vector: days since 1970-01-01. */
    public static IntVector dates(int capacity) {
        return new IntVector(DataType.DATE, capacity);
    }

    /** The raw array. */
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

    /** Grows if needed. Does not preserve contents. */
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
