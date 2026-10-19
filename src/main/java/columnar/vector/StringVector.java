package columnar.vector;

import columnar.types.DataType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** VARCHAR storage: one UTF-8 byte pool plus offsets. Append in row order. */
public final class StringVector extends ColumnVector {

    private static final int DEFAULT_BYTES_PER_VALUE = 16;
    private static final byte[] EMPTY = new byte[0];
    private byte[] data;
    private int[] offsets;
    private int written;

    public StringVector(int capacity) {
        this(capacity, Math.max(16, capacity * DEFAULT_BYTES_PER_VALUE));
    }

    public StringVector(int capacity, int initialBytes) {
        super(DataType.VARCHAR, capacity);
        this.data = new byte[Math.max(1, initialBytes)];
        this.offsets = new int[capacity + 1];
        this.written = 0;
    }

    /** The byte pool. Only bytes below offsets[rowCount()] are live. */
    public byte[] data() {
        return data;
    }

    /** Row boundaries, valid through index rowCount(). */
    public int[] offsets() {
        return offsets;
    }

    /** How many rows have been appended since the last reset. */
    public int rowCount() {
        return written;
    }

    /** Appends a value from a byte range. */
    public void append(byte[] utf8, int offset, int length) {
        if (written >= capacity()) {
            throw new IndexOutOfBoundsException("appending row " + written + " past capacity " + capacity());
        }
        ensureBytes(offsets[written] + length);
        System.arraycopy(utf8, offset, data, offsets[written], length);
        offsets[written + 1] = offsets[written] + length;
        written++;
    }

    /** Convenience for tests and loaders that already hold a String. */
    public void append(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        append(utf8, 0, utf8.length);
    }

    /** Appends a NULL, still advancing the offsets. */
    public void appendNull() {
        int row = written;
        append(EMPTY, 0, 0);
        setNull(row);
    }

    /** The byte length of a row. Zero for both an empty string and a NULL. */
    public int byteLength(int index) {
        checkRow(index);
        return offsets[index + 1] - offsets[index];
    }

    /** Decodes a row. Allocates, so not for the vector path. */
    public String getString(int index) {
        checkRow(index);
        return new String(data, offsets[index], byteLength(index), StandardCharsets.UTF_8);
    }

    /** Rejects rows never appended, whose offsets still hold the previous batch. */
    private void checkRow(int index) {
        if (index < 0 || index >= written) {
            throw new IndexOutOfBoundsException("row " + index + " of " + written + " appended");
        }
    }

    private void ensureBytes(int needed) {
        if (data.length < needed) {
            data = Arrays.copyOf(data, Math.max(needed, data.length * 2));
        }
    }

    /** Reallocates the offsets when too small and restarts the byte pool. */
    @Override
    protected void growValues(int capacity) {
        if (offsets.length < capacity + 1) {
            offsets = new int[capacity + 1];
        }
        offsets[0] = 0;
        written = 0;
    }

    @Override
    public Object valueAt(int index) {
        checkRow(index);
        return isNull(index) ? null : getString(index);
    }
}
