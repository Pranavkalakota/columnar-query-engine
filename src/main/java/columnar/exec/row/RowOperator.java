package columnar.exec.row;

import columnar.types.Schema;

/** One operator in the row-at-a-time pipeline. */
public interface RowOperator extends AutoCloseable {

    /** The schema of the rows this operator produces. */
    Schema schema();

    void open();

    /** The next row, or null at end of stream. */
    Row next();

    @Override
    void close();
}
