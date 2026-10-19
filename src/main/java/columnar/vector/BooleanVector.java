package columnar.vector;

import columnar.types.DataType;
import java.util.Arrays;

/** BOOLEAN storage, one bit per row. */
public final class BooleanVector extends ColumnVector {

    private long[] words;

    public BooleanVector(int capacity) {
        super(DataType.BOOLEAN, capacity);
        this.words = new long[Validity.wordCount(capacity)];
    }

    /** The raw words. */
    public long[] words() {
        return words;
    }

    public boolean get(int index) {
        return (words[index >>> 6] & (1L << (index & 63))) != 0;
    }

    public void set(int index, boolean value) {
        checkIndex(index);
        if (value) {
            words[index >>> 6] |= 1L << (index & 63);
        } else {
            words[index >>> 6] &= ~(1L << (index & 63));
        }
    }

    /** Grows if needed, and clears every bit. */
    @Override
    protected void growValues(int capacity) {
        int needed = Validity.wordCount(capacity);
        if (words.length < needed) {
            words = new long[needed];
        } else {
            Arrays.fill(words, 0, needed, 0L);
        }
    }

    @Override
    public Object valueAt(int index) {
        checkIndex(index);
        return isNull(index) ? null : get(index);
    }
}
