package columnar.vector;

import columnar.types.Field;
import columnar.types.Schema;

/** A batch of rows: one ColumnVector per column, plus an optional selection vector. */
public final class VectorBatch {

    private final Schema schema;
    private final ColumnVector[] columns;
    private int capacity;
    private int rowCount;
    private int[] selection;
    private int selectionSize;

    private VectorBatch(Schema schema, ColumnVector[] columns, int capacity) {
        this.schema = schema;
        this.columns = columns;
        this.capacity = capacity;
    }

    /** Allocates a batch whose vectors match a schema. */
    public static VectorBatch allocate(Schema schema, int capacity) {
        ColumnVector[] columns = new ColumnVector[schema.size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = vectorFor(schema.field(i), capacity);
        }
        return new VectorBatch(schema, columns, capacity);
    }

    private static ColumnVector vectorFor(Field field, int capacity) {
        return switch (field.type()) {
            case INT32 -> IntVector.ints(capacity);
            case DATE -> IntVector.dates(capacity);
            case INT64 -> new LongVector(capacity);
            case DOUBLE -> new DoubleVector(capacity);
            case BOOLEAN -> new BooleanVector(capacity);
            case VARCHAR -> new StringVector(capacity);
        };
    }

    public Schema schema() {
        return schema;
    }

    public ColumnVector column(int index) {
        return columns[index];
    }

    public ColumnVector column(String name) {
        return columns[schema.requireIndexOf(name)];
    }

    /** How many rows this batch can hold. */
    public int capacity() {
        return capacity;
    }

    /** Rows materialized in the vectors, ignoring any selection. */
    public int rowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        if (rowCount < 0 || rowCount > capacity) {
            throw new IllegalArgumentException("row count " + rowCount + " outside capacity " + capacity);
        }
        this.rowCount = rowCount;
    }

    /** Rows to visit: the selection size, or every row when unfiltered. */
    public int selectedCount() {
        return selection == null ? rowCount : selectionSize;
    }

    public boolean hasSelection() {
        return selection != null;
    }

    /** The selection, or null when unfiltered. Only selectedCount() entries are live. */
    public int[] selection() {
        return selection;
    }

    /** Narrows this batch. Only the size is checked; the producer owns index validity. */
    public void select(int[] selection, int size) {
        if (selection == null) {
            throw new IllegalArgumentException("use clearSelection() to drop a selection");
        }
        if (size < 0 || size > selection.length) {
            throw new IllegalArgumentException("selection size " + size + " exceeds array length");
        }
        this.selection = selection;
        this.selectionSize = size;
    }

    /** Drops the selection so every materialized row is visible again. */
    public void clearSelection() {
        this.selection = null;
        this.selectionSize = 0;
    }

    /** Maps a logical position to the physical row it refers to. */
    public int rowIndex(int position) {
        if (position < 0 || position >= selectedCount()) {
            throw new IndexOutOfBoundsException("position " + position + " of " + selectedCount());
        }
        return selection == null ? position : selection[position];
    }

    /** Reuses this batch, resetting every vector and dropping the selection. */
    public void reset(int capacity) {
        for (ColumnVector column : columns) {
            column.reset(capacity);
        }
        this.capacity = capacity;
        this.rowCount = 0;
        clearSelection();
    }
}
