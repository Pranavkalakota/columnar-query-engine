package columnar.expr.vec;

import static java.nio.charset.StandardCharsets.UTF_8;

import columnar.expr.BinaryOp;
import columnar.vector.StringVector;
import columnar.vector.VectorBatch;
import java.util.Arrays;

/** Compares a string column to a constant, byte for byte. */
public final class StringCompare {

    private StringCompare() {
    }

    /** Only equality has a kernel for strings. */
    static BatchPredicate equalTo(int index, BinaryOp op, String constant) {
        if (op != BinaryOp.EQUAL) {
            throw new UnsupportedOperationException("no kernel for VARCHAR " + op);
        }
        return new Equals(index, constant.getBytes(UTF_8));
    }

    /** NULL and '' are both zero bytes, so validity decides. */
    public static int eq(StringVector v, byte[] c, int n, int[] out) {
        byte[] data = v.data();
        int[] offsets = v.offsets();
        boolean checkNulls = v.mayHaveNulls();
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (equalAt(data, offsets, i, c) && !(checkNulls && v.isNull(i))) {
                out[k++] = i;
            }
        }
        return k;
    }

    public static int eq(StringVector v, byte[] c, int[] sel, int n, int[] out) {
        byte[] data = v.data();
        int[] offsets = v.offsets();
        boolean checkNulls = v.mayHaveNulls();
        int k = 0;
        for (int i = 0; i < n; i++) {
            int r = sel[i];
            if (equalAt(data, offsets, r, c) && !(checkNulls && v.isNull(r))) {
                out[k++] = r;
            }
        }
        return k;
    }

    /** Lengths first, so most rows never compare a byte. */
    private static boolean equalAt(byte[] data, int[] offsets, int row, byte[] c) {
        int start = offsets[row];
        return offsets[row + 1] - start == c.length
                && Arrays.equals(data, start, start + c.length, c, 0, c.length);
    }

    private record Equals(int index, byte[] constant) implements BatchPredicate {

        @Override
        public int selectAll(VectorBatch batch, int n, int[] out) {
            return eq((StringVector) batch.column(index), constant, n, out);
        }

        @Override
        public int selectFrom(VectorBatch batch, int[] sel, int n, int[] out) {
            return eq((StringVector) batch.column(index), constant, sel, n, out);
        }
    }
}
