package columnar.exec.row;

import columnar.types.Schema;

/** A row whose values are boxed objects, for computed results and the boxed engine. */
public final class ObjectRow implements Row {

    private final Schema schema;
    private final Object[] values;

    public ObjectRow(Schema schema) {
        this.schema = schema;
        this.values = new Object[schema.size()];
    }

    public void set(int column, Object value) {
        values[column] = value;
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public boolean isNull(int column) {
        return values[column] == null;
    }

    @Override
    public int getInt(int column) {
        return ((Number) values[column]).intValue();
    }

    @Override
    public long getLong(int column) {
        return ((Number) values[column]).longValue();
    }

    @Override
    public double getDouble(int column) {
        return ((Number) values[column]).doubleValue();
    }

    @Override
    public boolean getBoolean(int column) {
        return (Boolean) values[column];
    }

    @Override
    public String getString(int column) {
        return (String) values[column];
    }

    @Override
    public Object get(int column) {
        return values[column];
    }
}
