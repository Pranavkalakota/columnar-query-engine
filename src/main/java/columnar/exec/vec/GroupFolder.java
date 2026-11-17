package columnar.exec.vec;

import columnar.vector.DoubleVector;
import columnar.vector.VectorBatch;
import java.util.Arrays;

/** One aggregate's running state, one entry per group. */
abstract class GroupFolder {

    /** Makes room for every group seen so far. */
    abstract void ensure(int groups);

    abstract void fold(VectorBatch batch, int[] ordinals, int rows);

    /** The aggregate for a group, or null when nothing contributed. */
    abstract Object result(int group);

    static GroupFolder counter() {
        return new Counter();
    }

    /** SUM or AVG, over a column or a product. */
    static GroupFolder sums(int left, int right, boolean mean) {
        return new Sums(left, right, mean);
    }

    private static final class Counter extends GroupFolder {
        private long[] counts = new long[64];

        @Override
        void ensure(int groups) {
            if (groups > counts.length) {
                counts = Arrays.copyOf(counts, Math.max(groups, counts.length * 2));
            }
        }

        @Override
        void fold(VectorBatch batch, int[] ordinals, int rows) {
            for (int i = 0; i < rows; i++) {
                counts[ordinals[i]]++;
            }
        }

        @Override
        Object result(int group) {
            return counts[group];
        }
    }

    private static final class Sums extends GroupFolder {
        private final int left;
        private final int right;
        private final boolean mean;
        private double[] sums = new double[64];
        private long[] counts = new long[64];

        Sums(int left, int right, boolean mean) {
            this.left = left;
            this.right = right;
            this.mean = mean;
        }

        @Override
        void ensure(int groups) {
            if (groups > sums.length) {
                int size = Math.max(groups, sums.length * 2);
                sums = Arrays.copyOf(sums, size);
                counts = Arrays.copyOf(counts, size);
            }
        }

        @Override
        void fold(VectorBatch batch, int[] ordinals, int rows) {
            DoubleVector a = (DoubleVector) batch.column(left);
            DoubleVector b = right < 0 ? null : (DoubleVector) batch.column(right);
            double[] first = a.values();
            double[] second = b == null ? null : b.values();
            int[] sel = batch.selection();
            boolean checkNulls = a.mayHaveNulls() || (b != null && b.mayHaveNulls());
            for (int i = 0; i < rows; i++) {
                int row = sel == null ? i : sel[i];
                if (checkNulls && (a.isNull(row) || (b != null && b.isNull(row)))) {
                    continue;
                }
                int group = ordinals[i];
                sums[group] += second == null ? first[row] : first[row] * second[row];
                counts[group]++;
            }
        }

        @Override
        Object result(int group) {
            if (counts[group] == 0) {
                return null;
            }
            return mean ? sums[group] / counts[group] : sums[group];
        }
    }
}
