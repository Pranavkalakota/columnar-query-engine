package columnar.exec.vec;

import columnar.vector.BooleanVector;
import columnar.vector.ColumnVector;
import columnar.vector.DoubleVector;
import columnar.vector.IntVector;
import columnar.vector.LongVector;
import columnar.vector.StringVector;

/** Copies chosen rows into the front of another vector. */
final class VectorGather {

    private VectorGather() {
    }

    /** Writes rows[from..from+n) of src into rows 0..n of out. */
    static void gather(ColumnVector src, int[] rows, int from, int n, ColumnVector out) {
        switch (src.type()) {
            case INT32, DATE -> {
                int[] s = ((IntVector) src).values();
                int[] t = ((IntVector) out).values();
                for (int r = 0; r < n; r++) {
                    t[r] = s[rows[from + r]];
                }
            }
            case INT64 -> {
                long[] s = ((LongVector) src).values();
                long[] t = ((LongVector) out).values();
                for (int r = 0; r < n; r++) {
                    t[r] = s[rows[from + r]];
                }
            }
            case DOUBLE -> {
                double[] s = ((DoubleVector) src).values();
                double[] t = ((DoubleVector) out).values();
                for (int r = 0; r < n; r++) {
                    t[r] = s[rows[from + r]];
                }
            }
            case BOOLEAN -> {
                BooleanVector s = (BooleanVector) src;
                for (int r = 0; r < n; r++) {
                    ((BooleanVector) out).set(r, s.get(rows[from + r]));
                }
            }
            case VARCHAR -> {
                gatherStrings((StringVector) src, rows, from, n, (StringVector) out);
                return;
            }
        }
        if (src.mayHaveNulls()) {
            for (int r = 0; r < n; r++) {
                if (src.isNull(rows[from + r])) {
                    out.setNull(r);
                }
            }
        }
    }

    /** Strings append in row order, NULLs included. */
    private static void gatherStrings(StringVector src, int[] rows, int from, int n, StringVector out) {
        byte[] data = src.data();
        int[] offsets = src.offsets();
        boolean checkNulls = src.mayHaveNulls();
        for (int r = 0; r < n; r++) {
            int row = rows[from + r];
            if (checkNulls && src.isNull(row)) {
                out.appendNull();
            } else {
                out.append(data, offsets[row], offsets[row + 1] - offsets[row]);
            }
        }
    }
}
