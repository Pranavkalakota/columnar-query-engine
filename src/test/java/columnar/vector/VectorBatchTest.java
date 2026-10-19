package columnar.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorBatchTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("a", DataType.INT32), Field.of("b", DataType.INT64), Field.of("c", DataType.DOUBLE),
            Field.of("d", DataType.BOOLEAN), Field.of("e", DataType.VARCHAR), Field.of("f", DataType.DATE));

    @Test
    @DisplayName("every type maps to its backing vector, with DATE sharing int storage")
    void allocatesAVectorPerType() {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, 8);
        assertInstanceOf(IntVector.class, batch.column("a"));
        assertInstanceOf(LongVector.class, batch.column("b"));
        assertInstanceOf(DoubleVector.class, batch.column("c"));
        assertInstanceOf(BooleanVector.class, batch.column("d"));
        assertInstanceOf(StringVector.class, batch.column("e"));
        assertInstanceOf(IntVector.class, batch.column("f"));
        assertEquals(DataType.DATE, batch.column("f").type());
    }

    @Test
    @DisplayName("without a selection every materialized row is visible")
    void unfilteredBatchShowsEveryRow() {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, 8);
        batch.setRowCount(5);
        assertFalse(batch.hasSelection());
        assertEquals(5, batch.rowCount());
        assertEquals(5, batch.selectedCount());
        assertEquals(3, batch.rowIndex(3), "position maps to itself when unfiltered");
    }

    @Test
    @DisplayName("a selection narrows the batch without touching the column data")
    void selectionNarrowsWithoutCopying() {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, 8);
        batch.setRowCount(8);
        IntVector a = (IntVector) batch.column("a");
        for (int i = 0; i < 8; i++) {
            a.set(i, i * 10);
        }
        batch.select(new int[] {1, 4, 7, 0, 0, 0, 0, 0}, 3);
        assertEquals(3, batch.selectedCount());
        assertEquals(8, batch.rowCount(), "the vectors still hold every row");
        assertEquals(40, a.get(batch.rowIndex(1)), "position 1 refers to physical row 4");
        assertThrows(IndexOutOfBoundsException.class, () -> batch.rowIndex(3));
    }

    @Test
    @DisplayName("an empty selection is not end of stream: the rows are still there")
    void emptySelectionIsNotEndOfStream() {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, 4);
        batch.setRowCount(4);
        batch.select(new int[4], 0);
        assertEquals(0, batch.selectedCount(), "a filter rejected every row");
        assertEquals(4, batch.rowCount(), "but the batch still holds four rows");
        assertTrue(batch.hasSelection());
    }

    @Test
    @DisplayName("clearSelection and reset both restore the full view")
    void clearingAndResetting() {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, 8);
        batch.setRowCount(8);
        batch.select(new int[] {2}, 1);
        batch.clearSelection();
        assertFalse(batch.hasSelection());
        assertEquals(8, batch.selectedCount());
        batch.select(new int[] {2}, 1);
        batch.reset(4);
        assertFalse(batch.hasSelection());
        assertEquals(0, batch.rowCount());
        assertEquals(4, batch.column("a").capacity());
    }

    @Test
    @DisplayName("bad row counts and selection sizes are refused")
    void validatesSizes() {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, 4);
        assertThrows(IllegalArgumentException.class, () -> batch.setRowCount(5));
        assertThrows(IllegalArgumentException.class, () -> batch.setRowCount(-1));
        assertThrows(IllegalArgumentException.class, () -> batch.select(new int[2], 3));
        assertThrows(IllegalArgumentException.class, () -> batch.select(null, 0));
    }
}
