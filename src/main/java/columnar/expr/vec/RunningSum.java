package columnar.expr.vec;

import columnar.vector.Validity;

/** An ungrouped double sum, folded batch by batch. */
public final class RunningSum {

    private double sum;
    private long count;

    private static boolean valid(long[] words, int row) {
        return (words[row >>> 6] & (1L << (row & 63))) != 0;
    }

    public double sum() {
        return sum;
    }

    /** Rows that contributed, so NULL and zero stay distinct. */
    public long count() {
        return count;
    }

    /** NULL when nothing contributed, as SQL requires. */
    public Double result() {
        return count == 0 ? null : sum;
    }

    public void addColumn(double[] v, Validity nulls, int n) {
        double running = sum;
        if (!nulls.mayHaveNulls()) {
            for (int i = 0; i < n; i++) {
                running += v[i];
            }
            sum = running;
            count += n;
            return;
        }
        long[] w = nulls.words();
        long seen = count;
        for (int i = 0; i < n; i++) {
            if (valid(w, i)) {
                running += v[i];
                seen++;
            }
        }
        sum = running;
        count = seen;
    }

    public void addColumn(double[] v, Validity nulls, int[] sel, int n) {
        double running = sum;
        if (!nulls.mayHaveNulls()) {
            for (int i = 0; i < n; i++) {
                running += v[sel[i]];
            }
            sum = running;
            count += n;
            return;
        }
        long[] w = nulls.words();
        long seen = count;
        for (int i = 0; i < n; i++) {
            int r = sel[i];
            if (valid(w, r)) {
                running += v[r];
                seen++;
            }
        }
        sum = running;
        count = seen;
    }

    /** Sums a * b without materializing the product. */
    public void addProduct(double[] a, Validity an, double[] b, Validity bn, int n) {
        double running = sum;
        if (!an.mayHaveNulls() && !bn.mayHaveNulls()) {
            for (int i = 0; i < n; i++) {
                running += a[i] * b[i];
            }
            sum = running;
            count += n;
            return;
        }
        long[] aw = an.words();
        long[] bw = bn.words();
        long seen = count;
        for (int i = 0; i < n; i++) {
            if (valid(aw, i) && valid(bw, i)) {
                running += a[i] * b[i];
                seen++;
            }
        }
        sum = running;
        count = seen;
    }

    public void addProduct(double[] a, Validity an, double[] b, Validity bn, int[] sel, int n) {
        double running = sum;
        if (!an.mayHaveNulls() && !bn.mayHaveNulls()) {
            for (int i = 0; i < n; i++) {
                int r = sel[i];
                running += a[r] * b[r];
            }
            sum = running;
            count += n;
            return;
        }
        long[] aw = an.words();
        long[] bw = bn.words();
        long seen = count;
        for (int i = 0; i < n; i++) {
            int r = sel[i];
            if (valid(aw, r) && valid(bw, r)) {
                running += a[r] * b[r];
                seen++;
            }
        }
        sum = running;
        count = seen;
    }
}
