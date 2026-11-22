package columnar.exec.vec;

import columnar.types.DataType;
import columnar.vector.ColumnVector;
import columnar.vector.IntVector;
import columnar.vector.LongVector;
import columnar.vector.StringVector;
import java.util.Arrays;

/** One key column's values, one entry per group. */
abstract class GroupKeyStore {

    protected boolean[] nulls = new boolean[64];
    protected int count;

    /** Doubles are refused: equality makes a poor key. */
    static GroupKeyStore create(DataType type) {
        return switch (type) {
            case INT32, DATE -> new Ints(type);
            case INT64 -> new Longs();
            case VARCHAR -> new Strings();
            default -> throw new UnsupportedOperationException("cannot group by " + type);
        };
    }

    abstract int hash(ColumnVector vector, int row);

    abstract boolean matches(ColumnVector vector, int row, int group);

    abstract void append(ColumnVector vector, int row);

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

        Ints(DataType type) {
            this.type = type;
        }

        @Override
        int hash(ColumnVector vector, int row) {
            IntVector v = (IntVector) vector;
            return v.isNull(row) ? 0 : v.get(row);
        }

        @Override
        boolean matches(ColumnVector vector, int row, int group) {
            IntVector v = (IntVector) vector;
            boolean isNull = v.isNull(row);
            return isNull == nulls[group] && (isNull || v.get(row) == values[group]);
        }

        @Override
        void append(ColumnVector vector, int row) {
            IntVector v = (IntVector) vector;
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

        @Override
        int hash(ColumnVector vector, int row) {
            LongVector v = (LongVector) vector;
            return v.isNull(row) ? 0 : Long.hashCode(v.get(row));
        }

        @Override
        boolean matches(ColumnVector vector, int row, int group) {
            LongVector v = (LongVector) vector;
            boolean isNull = v.isNull(row);
            return isNull == nulls[group] && (isNull || v.get(row) == values[group]);
        }

        @Override
        void append(ColumnVector vector, int row) {
            LongVector v = (LongVector) vector;
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

        @Override
        int hash(ColumnVector vector, int row) {
            StringVector v = (StringVector) vector;
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
        boolean matches(ColumnVector vector, int row, int group) {
            StringVector v = (StringVector) vector;
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
        void append(ColumnVector vector, int row) {
            StringVector v = (StringVector) vector;
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
