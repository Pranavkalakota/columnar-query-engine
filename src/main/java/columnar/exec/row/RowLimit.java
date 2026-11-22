package columnar.exec.row;

import columnar.types.Schema;

/** Passes the first n rows, then stops pulling. */
public final class RowLimit implements RowOperator {

    private final RowOperator child;
    private final long limit;
    private long emitted;

    public RowLimit(RowOperator child, long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("a limit cannot be negative: " + limit);
        }
        this.child = child;
        this.limit = limit;
    }

    @Override
    public Schema schema() {
        return child.schema();
    }

    @Override
    public void open() {
        child.open();
        emitted = 0;
    }

    @Override
    public Row next() {
        if (emitted >= limit) {
            return null;
        }
        Row row = child.next();
        if (row != null) {
            emitted++;
        }
        return row;
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public String toString() {
        return "RowLimit(" + limit + ")";
    }
}
