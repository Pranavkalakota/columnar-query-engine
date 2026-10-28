package columnar.exec.row;

import columnar.storage.InMemoryTable;
import columnar.types.DataType;
import columnar.types.Schema;
import columnar.vector.BooleanVector;
import columnar.vector.ColumnVector;
import columnar.vector.DoubleVector;
import columnar.vector.IntVector;
import columnar.vector.LongVector;
import columnar.vector.StringVector;
import columnar.vector.VectorBatch;

/** The textbook baseline: a fresh boxed row per row. */
public final class BoxedRowScan implements RowOperator {

    private final InMemoryTable table;
    private int batchIndex;
    private int rowInBatch;
    private boolean opened;

    public BoxedRowScan(InMemoryTable table) {
        this.table = table;
    }

    @Override
    public Schema schema() {
        return table.schema();
    }

    @Override
    public void open() {
        batchIndex = 0;
        rowInBatch = 0;
        opened = true;
    }

    /** Allocates a row each call, the cost measured. */
    @Override
    public Row next() {
        if (!opened) {
            throw new IllegalStateException("open() before next()");
        }
        Schema schema = table.schema();
        while (batchIndex < table.batchCount()) {
            VectorBatch batch = table.batch(batchIndex);
            if (rowInBatch < batch.rowCount()) {
                ObjectRow row = new ObjectRow(schema);
                for (int c = 0; c < schema.size(); c++) {
                    row.set(c, box(batch.column(c), rowInBatch, schema.type(c)));
                }
                rowInBatch++;
                return row;
            }
            batchIndex++;
            rowInBatch = 0;
        }
        return null;
    }

    /** A DATE boxes as its epoch day. */
    private static Object box(ColumnVector column, int row, DataType type) {
        if (column.isNull(row)) {
            return null;
        }
        return switch (type) {
            case INT32, DATE -> ((IntVector) column).get(row);
            case INT64 -> ((LongVector) column).get(row);
            case DOUBLE -> ((DoubleVector) column).get(row);
            case BOOLEAN -> ((BooleanVector) column).get(row);
            case VARCHAR -> ((StringVector) column).getString(row);
        };
    }

    @Override
    public void close() {
        opened = false;
    }
}
