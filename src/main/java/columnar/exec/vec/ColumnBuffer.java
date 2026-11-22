package columnar.exec.vec;

import columnar.exec.sort.Ordering;
import columnar.types.DataType;
import columnar.vector.BooleanVector;
import columnar.vector.ColumnVector;
import columnar.vector.DoubleVector;
import columnar.vector.IntVector;
import columnar.vector.LongVector;
import java.util.Arrays;

/** A growable copy of one column. */
abstract class ColumnBuffer {

    protected boolean[] nulls = new boolean[1024];
    protected boolean anyNull;
    protected int size;

    static ColumnBuffer create(DataType type) {
        return switch (type) {
            case INT32, DATE -> new Ints();
            case INT64 -> new Longs();
            case DOUBLE -> new Doubles();
            case BOOLEAN -> new Booleans();
            case VARCHAR -> new StringColumnBuffer();
        };
    }

    boolean isNull(int i) {
        return nulls[i];
    }

    /** Copies the selected rows onto the end. */
    void appendAll(ColumnVector v, int[] sel, int n) {
        reserve(size + n);
        if (nulls.length < size + n) {
            nulls = Arrays.copyOf(nulls, Math.max(size + n, nulls.length * 2));
        }
        boolean checkNulls = v.mayHaveNulls();
        for (int i = 0; i < n; i++) {
            int row = sel == null ? i : sel[i];
            if (checkNulls && v.isNull(row)) {
                nulls[size] = true;
                anyNull = true;
            }
            copy(v, row, size);
            size++;
        }
    }

    /** Marks the NULL rows of a gathered batch. */
    protected void markNulls(int[] order, int from, int n, ColumnVector out) {
        for (int r = 0; r < n; r++) {
            if (nulls[order[from + r]]) {
                out.setNull(r);
            }
        }
    }

    /** Ensures room for this many entries. */
    abstract void reserve(int needed);

    abstract void copy(ColumnVector v, int row, int at);

    /** Compares two non-null entries. */
    abstract int compare(int i, int j);

    /** Writes entries order[from..from+n) into rows 0..n of out. */
    abstract void gather(int[] order, int from, int n, ColumnVector out);

    static int grown(int length, int needed) {
        return Math.max(needed, length * 2);
    }

    private static final class Ints extends ColumnBuffer {
        private int[] values = new int[1024];

        @Override
        void reserve(int needed) {
            if (values.length < needed) {
                values = Arrays.copyOf(values, grown(values.length, needed));
            }
        }

        @Override
        void copy(ColumnVector v, int row, int at) {
            values[at] = ((IntVector) v).get(row);
        }

        @Override
        int compare(int i, int j) {
            return Integer.compare(values[i], values[j]);
        }

        @Override
        void gather(int[] order, int from, int n, ColumnVector out) {
            int[] target = ((IntVector) out).values();
            for (int r = 0; r < n; r++) {
                target[r] = values[order[from + r]];
            }
            if (anyNull) {
                markNulls(order, from, n, out);
            }
        }
    }

    private static final class Longs extends ColumnBuffer {
        private long[] values = new long[1024];

        @Override
        void reserve(int needed) {
            if (values.length < needed) {
                values = Arrays.copyOf(values, grown(values.length, needed));
            }
        }

        @Override
        void copy(ColumnVector v, int row, int at) {
            values[at] = ((LongVector) v).get(row);
        }

        @Override
        int compare(int i, int j) {
            return Long.compare(values[i], values[j]);
        }

        @Override
        void gather(int[] order, int from, int n, ColumnVector out) {
            long[] target = ((LongVector) out).values();
            for (int r = 0; r < n; r++) {
                target[r] = values[order[from + r]];
            }
            if (anyNull) {
                markNulls(order, from, n, out);
            }
        }
    }

    private static final class Doubles extends ColumnBuffer {
        private double[] values = new double[1024];

        @Override
        void reserve(int needed) {
            if (values.length < needed) {
                values = Arrays.copyOf(values, grown(values.length, needed));
            }
        }

        @Override
        void copy(ColumnVector v, int row, int at) {
            values[at] = ((DoubleVector) v).get(row);
        }

        @Override
        int compare(int i, int j) {
            return Ordering.compareDoubles(values[i], values[j]);
        }

        @Override
        void gather(int[] order, int from, int n, ColumnVector out) {
            double[] target = ((DoubleVector) out).values();
            for (int r = 0; r < n; r++) {
                target[r] = values[order[from + r]];
            }
            if (anyNull) {
                markNulls(order, from, n, out);
            }
        }
    }

    private static final class Booleans extends ColumnBuffer {
        private boolean[] values = new boolean[1024];

        @Override
        void reserve(int needed) {
            if (values.length < needed) {
                values = Arrays.copyOf(values, grown(values.length, needed));
            }
        }

        @Override
        void copy(ColumnVector v, int row, int at) {
            values[at] = ((BooleanVector) v).get(row);
        }

        @Override
        int compare(int i, int j) {
            return Boolean.compare(values[i], values[j]);
        }

        @Override
        void gather(int[] order, int from, int n, ColumnVector out) {
            BooleanVector target = (BooleanVector) out;
            for (int r = 0; r < n; r++) {
                target.set(r, values[order[from + r]]);
            }
            if (anyNull) {
                markNulls(order, from, n, out);
            }
        }
    }
}
