package columnar.exec.row;

import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Row-at-a-time inner equi-join, the vector join's baseline. */
public final class RowHashJoin implements RowOperator {

    private final RowOperator probe;
    private final RowOperator build;
    private final List<String> buildKeys;
    private final int[] probeKeyColumns;
    private final int[] buildKeyColumns;
    private final DataType[] probeTypes;
    private final DataType[] buildTypes;
    private final Schema schema;
    private final ObjectRow out;
    private final Map<List<Object>, List<Object[]>> table = new LinkedHashMap<>();
    private Object[] probeValues;
    private List<Object[]> matches = List.of();
    private int cursor;
    private boolean built;

    public RowHashJoin(RowOperator probe, RowOperator build, List<String> probeKeys, List<String> buildKeys) {
        if (probeKeys.isEmpty() || probeKeys.size() != buildKeys.size()) {
            throw new IllegalArgumentException("a join needs matching key lists, got " + probeKeys + " and " + buildKeys);
        }
        this.probe = probe;
        this.build = build;
        this.buildKeys = List.copyOf(buildKeys);
        this.probeKeyColumns = resolve(probe.schema(), probeKeys);
        this.buildKeyColumns = resolve(build.schema(), buildKeys);
        this.probeTypes = types(probe.schema());
        this.buildTypes = types(build.schema());
        for (int k = 0; k < probeKeyColumns.length; k++) {
            DataType left = probeTypes[probeKeyColumns[k]];
            DataType right = buildTypes[buildKeyColumns[k]];
            if (left != right) {
                throw new UnsupportedOperationException("cannot join " + left + " with " + right);
            }
            if (left == DataType.DOUBLE || left == DataType.BOOLEAN) {
                throw new UnsupportedOperationException("cannot join on " + left);
            }
        }
        List<Field> fields = new ArrayList<>(probe.schema().fields());
        fields.addAll(build.schema().fields());
        this.schema = Schema.of(fields);
        this.out = new ObjectRow(schema);
    }

    private static int[] resolve(Schema schema, List<String> names) {
        int[] columns = new int[names.size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = schema.requireIndexOf(names.get(i));
        }
        return columns;
    }

    private static DataType[] types(Schema schema) {
        DataType[] types = new DataType[schema.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = schema.type(i);
        }
        return types;
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public void open() {
        probe.open();
        build.open();
        table.clear();
        matches = List.of();
        cursor = 0;
        built = false;
    }

    /** Builds first, then walks each probe row's matches. */
    @Override
    public Row next() {
        if (!built) {
            buildTable();
            built = true;
        }
        while (cursor >= matches.size()) {
            Row row = probe.next();
            if (row == null) {
                return null;
            }
            List<Object> key = probeKey(row);
            matches = key == null ? List.of() : table.getOrDefault(key, List.of());
            cursor = 0;
            if (!matches.isEmpty()) {
                probeValues = RowValues.copy(row, probeTypes);
            }
        }
        Object[] buildValues = matches.get(cursor++);
        for (int i = 0; i < probeValues.length; i++) {
            out.set(i, probeValues[i]);
        }
        for (int i = 0; i < buildValues.length; i++) {
            out.set(probeValues.length + i, buildValues[i]);
        }
        return out;
    }

    private void buildTable() {
        Row row;
        while ((row = build.next()) != null) {
            Object[] values = RowValues.copy(row, buildTypes);
            List<Object> key = key(values, buildKeyColumns);
            if (key != null) {
                table.computeIfAbsent(key, k -> new ArrayList<>()).add(values);
            }
        }
    }

    /** Reads only the key, so misses copy nothing. */
    private List<Object> probeKey(Row row) {
        List<Object> key = new ArrayList<>(probeKeyColumns.length);
        for (int column : probeKeyColumns) {
            Object value = RowValues.read(row, column, probeTypes[column]);
            if (value == null) {
                return null;
            }
            key.add(value);
        }
        return key;
    }

    /** A boxed key, or null when any part is NULL. */
    private static List<Object> key(Object[] values, int[] columns) {
        List<Object> key = new ArrayList<>(columns.length);
        for (int column : columns) {
            if (values[column] == null) {
                return null;
            }
            key.add(values[column]);
        }
        return key;
    }

    @Override
    public void close() {
        probe.close();
        build.close();
    }

    @Override
    public String toString() {
        return "RowHashJoin(on " + buildKeys + ")";
    }
}
