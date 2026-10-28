package columnar.exec.row;

import columnar.types.DataType;
import columnar.types.Dates;
import columnar.types.Schema;

/** A row of boxed values. */
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

    /** A DATE shows as a LocalDate, matching the tuned engine. */
    @Override
    public Object get(int column) {
        Object value = values[column];
        return value instanceof Integer day && schema.type(column) == DataType.DATE
                ? Dates.toLocalDate(day) : value;
    }
}
