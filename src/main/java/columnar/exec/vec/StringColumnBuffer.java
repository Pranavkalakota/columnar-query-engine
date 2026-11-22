package columnar.exec.vec;

import columnar.exec.sort.Ordering;
import columnar.vector.ColumnVector;
import columnar.vector.StringVector;
import java.util.Arrays;

/** Bytes in one pool, so comparing never decodes a String. */
final class StringColumnBuffer extends ColumnBuffer {
    private byte[] pool = new byte[16 * 1024];
    private int[] offsets = new int[1025];

    @Override
    void reserve(int needed) {
        if (offsets.length < needed + 1) {
            offsets = Arrays.copyOf(offsets, grown(offsets.length, needed + 1));
        }
    }

    @Override
    void copy(ColumnVector v, int row, int at) {
        StringVector strings = (StringVector) v;
        int[] bounds = strings.offsets();
        int length = strings.isNull(row) ? 0 : bounds[row + 1] - bounds[row];
        int start = offsets[at];
        if (pool.length < start + length) {
            pool = Arrays.copyOf(pool, grown(pool.length, start + length));
        }
        System.arraycopy(strings.data(), bounds[row], pool, start, length);
        offsets[at + 1] = start + length;
    }

    @Override
    int compare(int i, int j) {
        return Ordering.compareUtf8(pool, offsets[i], offsets[i + 1], pool, offsets[j], offsets[j + 1]);
    }

    /** Appends in row order, as a StringVector requires. */
    @Override
    void gather(int[] order, int from, int n, ColumnVector out) {
        StringVector target = (StringVector) out;
        for (int r = 0; r < n; r++) {
            int i = order[from + r];
            if (anyNull && nulls[i]) {
                target.appendNull();
            } else {
                target.append(pool, offsets[i], offsets[i + 1] - offsets[i]);
            }
        }
    }
}
