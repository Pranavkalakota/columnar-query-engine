package columnar.expr.vec;

import columnar.vector.Validity;

/** Compares an int column to a constant, writing surviving rows. */
public final class IntCompare {

    private IntCompare() {
    }

    private static boolean valid(long[] words, int row) {
        return (words[row >>> 6] & (1L << (row & 63))) != 0;
    }

    public static int ge(int[] v, int c, Validity nulls, int n, int[] out) {
        int k = 0;
        if (!nulls.mayHaveNulls()) {
            for (int i = 0; i < n; i++) {
                if (v[i] >= c) {
                    out[k++] = i;
                }
            }
            return k;
        }
        long[] w = nulls.words();
        for (int i = 0; i < n; i++) {
            if (v[i] >= c && valid(w, i)) {
                out[k++] = i;
            }
        }
        return k;
    }

    public static int ge(int[] v, int c, Validity nulls, int[] sel, int n, int[] out) {
        int k = 0;
        if (!nulls.mayHaveNulls()) {
            for (int i = 0; i < n; i++) {
                int r = sel[i];
                if (v[r] >= c) {
                    out[k++] = r;
                }
            }
            return k;
        }
        long[] w = nulls.words();
        for (int i = 0; i < n; i++) {
            int r = sel[i];
            if (v[r] >= c && valid(w, r)) {
                out[k++] = r;
            }
        }
        return k;
    }

    public static int lt(int[] v, int c, Validity nulls, int n, int[] out) {
        int k = 0;
        if (!nulls.mayHaveNulls()) {
            for (int i = 0; i < n; i++) {
                if (v[i] < c) {
                    out[k++] = i;
                }
            }
            return k;
        }
        long[] w = nulls.words();
        for (int i = 0; i < n; i++) {
            if (v[i] < c && valid(w, i)) {
                out[k++] = i;
            }
        }
        return k;
    }

    public static int lt(int[] v, int c, Validity nulls, int[] sel, int n, int[] out) {
        int k = 0;
        if (!nulls.mayHaveNulls()) {
            for (int i = 0; i < n; i++) {
                int r = sel[i];
                if (v[r] < c) {
                    out[k++] = r;
                }
            }
            return k;
        }
        long[] w = nulls.words();
        for (int i = 0; i < n; i++) {
            int r = sel[i];
            if (v[r] < c && valid(w, r)) {
                out[k++] = r;
            }
        }
        return k;
    }
}
