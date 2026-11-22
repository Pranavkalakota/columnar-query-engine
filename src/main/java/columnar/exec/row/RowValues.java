package columnar.exec.row;

import columnar.types.DataType;

/** The one way every row operator reads values. */
final class RowValues {

    private RowValues() {
    }

    /** DATE stays an int, so getInt works downstream. */
    static Object read(Row row, int column, DataType type) {
        if (row.isNull(column)) {
            return null;
        }
        return switch (type) {
            case INT32, DATE -> row.getInt(column);
            case INT64 -> row.getLong(column);
            case DOUBLE -> row.getDouble(column);
            case BOOLEAN -> row.getBoolean(column);
            case VARCHAR -> row.getString(column);
        };
    }

    /** A copy of every column, since cursors get reused. */
    static Object[] copy(Row row, DataType[] types) {
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            values[i] = read(row, i, types[i]);
        }
        return values;
    }
}
