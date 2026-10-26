package columnar.exec.row;

import columnar.types.Schema;

/** One row, read without boxing. */
public interface Row {

    Schema schema();

    boolean isNull(int column);

    int getInt(int column);
    long getLong(int column);
    double getDouble(int column);
    boolean getBoolean(int column);
    String getString(int column);

    /** Boxed access, for the boxed engine and for materializing results. */
    Object get(int column);
}
