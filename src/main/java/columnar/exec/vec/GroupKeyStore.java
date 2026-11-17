package columnar.exec.vec;

import columnar.types.DataType;
import columnar.vector.ColumnVector;
import columnar.vector.IntVector;
import columnar.vector.LongVector;
import columnar.vector.StringVector;
import columnar.vector.VectorBatch;
import java.util.Arrays;

/** One key column's values, one entry per group. */
abstract class GroupKeyStore {

    protected final int column;
    protected boolean[] nulls = new boolean[64];
    protected int count;

    protected GroupKeyStore(int column) {
        this.column = column;
    }

    /** Doubles are refused: equality makes a poor key. */
    static GroupKeyStore create(DataType type, int column) {
        return switch (type) {
            case INT32, DATE -> new Ints(type, column);
            case INT64 -> new Longs(column);
            case VARCHAR -> new Strings(column);
            default -> throw new UnsupportedOperationException("cannot group by " + type);
        };
    }

    abstract int hash(VectorBatch batch, int row);

    abstract boolean matches(VectorBatch batch, int row, int group);

    abstract void append(VectorBatch batch, int row);

    abstract void writeTo(int group, ColumnVector out, int row);

    /** Records whether this group's key is NULL, and grows. */
    protected void appendNull(boolean isNull) {
        if (count >= nulls.length) {
            nulls = Arrays.copyOf(nulls, nulls.length * 2);
        }
        nulls[count++] = isNull;
    }

    private static final class Ints extends GroupKeyStore {
        private final DataType type;
        private int[] values = new int[64];

        Ints(DataType type, int column) {
            super(column);
            this.type = type;
        }

        @Override
        int hash(VectorBatch batch, int row) {
            IntVector v = (IntVector) batch.column(column);
            return v.isNull(row) ? 0 : v.get(row);
        }

        @Override
        boolean matches(VectorBatch batch, int row, int group) {
            IntVector v = (IntVector) batch.column(column);
            boolean isNull = v.isNull(row);
            return isNull == nulls[group] && (isNull || v.get(row) == values[group]);
        }

        @Override
        void append(VectorBatch batch, int row) {
            IntVector v = (IntVector) batch.column(column);
            if (count >= values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[count] = v.get(row);
            appendNull(v.isNull(row));
        }

        @Override
        void writeTo(int group, ColumnVector out, int row) {
            if (nulls[group]) {
                out.setNull(row);
            } else {
                ((IntVector) out).set(row, values[group]);
            }
        }

        @Override
        public String toString() {
            return type.toString();
        }
    }

    private static final class Longs extends GroupKeyStore {
        private long[] values = new long[64];

        Longs(int column) {
            super(column);
        }

        @Override
        int hash(VectorBatch batch, int row) {
            LongVector v = (LongVector) batch.column(column);
            return v.isNull(row) ? 0 : Long.hashCode(v.get(row));
        }

        @Override
        boolean matches(VectorBatch batch, int row, int group) {
            LongVector v = (LongVector) batch.column(column);
            boolean isNull = v.isNull(row);
            return isNull == nulls[group] && (isNull || v.get(row) == values[group]);
        }

        @Override
        void append(VectorBatch batch, int row) {
            LongVector v = (LongVector) batch.column(column);
            if (count >= values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[count] = v.get(row);
            appendNull(v.isNull(row));
        }

        @Override
        void writeTo(int group, ColumnVector out, int row) {
            if (nulls[group]) {
                out.setNull(row);
            } else {
                ((LongVector) out).set(row, values[group]);
            }
        }
    }

    private static final class Strings extends GroupKeyStore {
        private byte[] pool = new byte[512];
        private int[] offsets = new int[65];

        Strings(int column) {
            super(column);
        }

        @Override
        int hash(VectorBatch batch, int row) {
            StringVector v = (StringVector) batch.column(column);
            if (v.isNull(row)) {
                return 0;
            }
            byte[] data = v.data();
            int[] bounds = v.offsets();
            int hash = 1;
            for (int i = bounds[row]; i < bounds[row + 1]; i++) {
                hash = hash * 31 + data[i];
            }
            return hash;
        }

        @Override
        boolean matches(VectorBatch batch, int row, int group) {
            StringVector v = (StringVector) batch.column(column);
            boolean isNull = v.isNull(row);
            if (isNull != nulls[group]) {
                return false;
            }
            if (isNull) {
                return true;
            }
            int[] bounds = v.offsets();
            int length = bounds[row + 1] - bounds[row];
            if (length != offsets[group + 1] - offsets[group]) {
                return false;
            }
            return Arrays.equals(v.data(), bounds[row], bounds[row + 1],
                    pool, offsets[group], offsets[group + 1]);
        }

        @Override
        void append(VectorBatch batch, int row) {
            StringVector v = (StringVector) batch.column(column);
            int[] bounds = v.offsets();
            int length = v.isNull(row) ? 0 : bounds[row + 1] - bounds[row];
            if (count + 1 >= offsets.length) {
                offsets = Arrays.copyOf(offsets, offsets.length * 2);
            }
            int start = offsets[count];
            if (pool.length < start + length) {
                pool = Arrays.copyOf(pool, Math.max(start + length, pool.length * 2));
            }
            if (length > 0) {
                System.arraycopy(v.data(), bounds[row], pool, start, length);
            }
            offsets[count + 1] = start + length;
            appendNull(v.isNull(row));
        }

        @Override
        void writeTo(int group, ColumnVector out, int row) {
            StringVector target = (StringVector) out;
            if (nulls[group]) {
                target.appendNull();
            } else {
                target.append(pool, offsets[group], offsets[group + 1] - offsets[group]);
            }
        }
    }
}
