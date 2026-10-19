package columnar.vector;

import columnar.types.DataType;
import java.util.Arrays;

/**
 * A bitmap-backed vector for {@link DataType#BOOLEAN}: one bit per row.
 *
 * <p>A {@code boolean[]} would be simpler and eight times larger. The reason
 * for bits is three-valued logic. A SQL boolean needs two facts per row, its
 * value and whether it is known, and this vector already carries the second as
 * a validity bitmap. With both as {@code long[]}, {@code a AND b} over 64 rows
 * is a few word operations rather than 64 branches, and
 * {@code NULL AND FALSE = FALSE} falls out of masking instead of a per-row
 * special case.
 *
 * <p>Note this is not what a filter produces. A filter narrows a selection
 * vector of surviving row indexes; this type is for BOOLEAN column data and
 * for boolean expression results that something else then consumes.
 */
public final class BooleanVector extends ColumnVector {

    private long[] words;

    public BooleanVector(int capacity) {
        super(DataType.BOOLEAN, capacity);
        this.words = new long[Validity.wordCount(capacity)];
    }

    /** The backing words, for word-at-a-time boolean kernels. */
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

    /** Reallocates when too small, and clears every bit to false. */
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
