package columnar.exec.vec;

import columnar.expr.vec.DoubleKernel;
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

    /** SUM or AVG over any compiled double expression. */
    static GroupFolder sums(DoubleKernel kernel, boolean mean) {
        return new Sums(kernel, mean);
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
        private final DoubleKernel kernel;
        private final boolean mean;
        private double[] sums = new double[64];
        private long[] counts = new long[64];
        private double[] values = new double[0];
        private boolean[] valid = new boolean[0];

        Sums(DoubleKernel kernel, boolean mean) {
            this.kernel = kernel;
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
            if (values.length < rows) {
                values = new double[rows];
                valid = new boolean[rows];
            }
            boolean all = kernel.evaluate(batch, batch.selection(), rows, values, valid);
            for (int i = 0; i < rows; i++) {
                if (all || valid[i]) {
                    int group = ordinals[i];
                    sums[group] += values[i];
                    counts[group]++;
                }
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
