package columnar.exec.row;

import columnar.exec.sort.Ordering;
import columnar.exec.sort.SortKey;
import columnar.types.DataType;
import columnar.types.Schema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Row-at-a-time ORDER BY: buffers every row, then sorts. */
public final class RowSort implements RowOperator {

    private final RowOperator child;
    private final List<SortKey> keys;
    private final DataType[] types;
    private final int[] keyColumns;
    private final boolean[] descending;
    private final ObjectRow out;
    private final List<Object[]> rows = new ArrayList<>();
    private int cursor;
    private boolean drained;

    public RowSort(RowOperator child, List<SortKey> keys) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("a sort needs at least one key");
        }
        this.child = child;
        this.keys = List.copyOf(keys);
        Schema schema = child.schema();
        this.types = new DataType[schema.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = schema.type(i);
        }
        this.keyColumns = new int[this.keys.size()];
        this.descending = new boolean[this.keys.size()];
        for (int k = 0; k < keyColumns.length; k++) {
            keyColumns[k] = schema.requireIndexOf(this.keys.get(k).column());
            descending[k] = this.keys.get(k).descending();
        }
        this.out = new ObjectRow(schema);
    }

    @Override
    public Schema schema() {
        return child.schema();
    }

    @Override
    public void open() {
        child.open();
        rows.clear();
        cursor = 0;
        drained = false;
    }

    /** Sorts on the first call, then hands rows out. */
    @Override
    public Row next() {
        if (!drained) {
            drain();
            rows.sort(this::compareRows);
            drained = true;
        }
        if (cursor >= rows.size()) {
            return null;
        }
        Object[] values = rows.get(cursor++);
        for (int i = 0; i < values.length; i++) {
            out.set(i, values[i]);
        }
        return out;
    }

    /** Copies each row, since the child reuses its cursor. */
    private void drain() {
        Row row;
        while ((row = child.next()) != null) {
            rows.add(RowValues.copy(row, types));
        }
    }

    private int compareRows(Object[] a, Object[] b) {
        for (int k = 0; k < keyColumns.length; k++) {
            int column = keyColumns[k];
            Object x = a[column];
            Object y = b[column];
            if (x == null || y == null) {
                if (x == null && y == null) {
                    continue;
                }
                return x == null ? 1 : -1;
            }
            int c = compareValues(types[column], x, y);
            if (c != 0) {
                return descending[k] ? -c : c;
            }
        }
        return 0;
    }

    private static int compareValues(DataType type, Object x, Object y) {
        return switch (type) {
            case INT32, DATE -> Integer.compare((Integer) x, (Integer) y);
            case INT64 -> Long.compare((Long) x, (Long) y);
            case DOUBLE -> Ordering.compareDoubles((Double) x, (Double) y);
            case BOOLEAN -> Boolean.compare((Boolean) x, (Boolean) y);
            case VARCHAR -> Ordering.compareStrings((String) x, (String) y);
        };
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public String toString() {
        return "RowSort" + keys;
    }
}
