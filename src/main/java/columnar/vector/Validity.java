package columnar.vector;

/**
 * A per-vector null bitmap: one bit per row, {@code 1} meaning valid.
 *
 * <p>One bit per row rather than a {@code boolean[]}'s byte, so eight times as
 * many rows fit in a cache line. The bigger win is {@link #mayHaveNulls()}: a
 * kernel checks it once per batch and skips null handling entirely when it is
 * clear. {@code 1 = valid} matches Arrow and Parquet, so definition levels map
 * across without inversion.
 *
 * <p><b>mayHaveNulls is conservative.</b> It latches true on the first null
 * written and is never recomputed: clearing it would mean scanning the bitmap
 * to save a branch. It means "might", not "does", and a stale true only costs
 * a slower loop.
 */
public final class Validity {

    private static final long ALL_VALID = -1L;

    private long[] words;
    private int capacity;
    private boolean mayHaveNulls;

    private Validity(int capacity) {
        reset(capacity);
    }

    /** A bitmap of the given size with every row marked valid. */
    public static Validity allValid(int capacity) {
        return new Validity(capacity);
    }

    /** Number of words needed to hold {@code capacity} bits. */
    static int wordCount(int capacity) {
        return (capacity + 63) >>> 6;
    }

    /**
     * Reuses this bitmap for another batch, growing if needed and marking
     * everything valid again. Batches are recycled rather than reallocated so
     * the vector path makes no per-batch garbage, which the benchmark measures.
     */
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

    /** True when no null has been written since the last {@link #reset}. */
    public boolean mayHaveNulls() {
        return mayHaveNulls;
    }

    /**
     * Whether row {@code index} holds a value. Deliberately unchecked: it sits in
     * the innermost kernel loop, so bounds are the caller's job. The writers
     * below do check, running once per value where a clear message is worth it.
     */
    public boolean isValid(int index) {
        return (words[index >>> 6] & (1L << (index & 63))) != 0;
    }

    public boolean isNull(int index) {
        return !isValid(index);
    }

    /** Marks a row null, latching {@link #mayHaveNulls()}. */
    public void setNull(int index) {
        checkIndex(index);
        words[index >>> 6] &= ~(1L << (index & 63));
        mayHaveNulls = true;
    }

    /** Marks a row valid. Does not clear {@link #mayHaveNulls()}; see the class note. */
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
