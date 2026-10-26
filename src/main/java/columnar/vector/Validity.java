package columnar.vector;

/** A per-vector null bitmap. */
public final class Validity {

    private static final long ALL_VALID = -1L;

    private long[] words;
    private int capacity;
    private boolean mayHaveNulls;

    private Validity(int capacity) {
        reset(capacity);
    }

    /** A bitmap with every row valid. */
    public static Validity allValid(int capacity) {
        return new Validity(capacity);
    }

    /** Number of words needed to hold capacity bits. */
    static int wordCount(int capacity) {
        return (capacity + 63) >>> 6;
    }

    /** Reuses this bitmap for another batch, growing if needed. */
    public void reset(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("negative capacity: " + capacity);
        }
        int needed = wordCount(capacity);
        if (words == null || words.length < needed) {
            words = new long[needed];
        }
        java.util.Arrays.fill(words, 0, needed, ALL_VALID);
        this.capacity = capacity;
        this.mayHaveNulls = false;
    }

    public int capacity() {
        return capacity;
    }

    /** True when no null was written since reset. */
    public boolean mayHaveNulls() {
        return mayHaveNulls;
    }

    /** Whether a row holds a value. */
    public boolean isValid(int index) {
        return (words[index >>> 6] & (1L << (index & 63))) != 0;
    }

    public boolean isNull(int index) {
        return !isValid(index);
    }

    /** Marks a row null, latching mayHaveNulls(). */
    public void setNull(int index) {
        checkIndex(index);
        words[index >>> 6] &= ~(1L << (index & 63));
        mayHaveNulls = true;
    }

    /** Marks a row valid. */
    public void setValid(int index) {
        checkIndex(index);
        words[index >>> 6] |= 1L << (index & 63);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException("row " + index + " outside 0.." + (capacity - 1));
        }
    }
}
