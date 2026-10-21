package columnar.exec.row;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.BooleanVector;
import columnar.vector.DoubleVector;
import columnar.vector.IntVector;
import columnar.vector.LongVector;
import columnar.vector.StringVector;
import columnar.vector.VectorBatch;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatchRowTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("i", DataType.INT32), Field.of("l", DataType.INT64),
            Field.of("d", DataType.DOUBLE), Field.of("b", DataType.BOOLEAN),
            Field.of("s", DataType.VARCHAR), Field.of("day", DataType.DATE));

    /** Two rows; the second has a NULL in every column. */
    private static VectorBatch batch() {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, 2);
        ((IntVector) batch.column(0)).set(0, 42);
        ((LongVector) batch.column(1)).set(0, 9_000_000_000L);
        ((DoubleVector) batch.column(2)).set(0, 1.5);
        ((BooleanVector) batch.column(3)).set(0, true);
        ((StringVector) batch.column(4)).append("PROMO");
        ((IntVector) batch.column(5)).set(0, 8766);
        for (int c = 0; c < SCHEMA.size(); c++) {
            if (c == 4) {
                ((StringVector) batch.column(c)).appendNull();
            } else {
                batch.column(c).setNull(1);
            }
        }
        batch.setRowCount(2);
        return batch;
    }

    @Test
    @DisplayName("typed accessors read each column without boxing")
    void typedAccessors() {
        BatchRow row = new BatchRow(batch());
        row.position(0);
        assertEquals(42, row.getInt(0));
        assertEquals(9_000_000_000L, row.getLong(1));
        assertEquals(1.5, row.getDouble(2));
        assertTrue(row.getBoolean(3));
        assertEquals("PROMO", row.getString(4));
        assertEquals(8766, row.getInt(5), "a DATE reads as its epoch day");
        assertEquals(SCHEMA, row.schema());
    }

    @Test
    @DisplayName("boxed access agrees with the typed accessors and turns a DATE into a LocalDate")
    void boxedAccessAgrees() {
        BatchRow row = new BatchRow(batch());
        row.position(0);
        assertEquals(42, row.get(0));
        assertEquals(9_000_000_000L, row.get(1));
        assertEquals(1.5, row.get(2));
        assertEquals(Boolean.TRUE, row.get(3));
        assertEquals("PROMO", row.get(4));
        assertEquals(LocalDate.of(1994, 1, 1), row.get(5));
    }

    @Test
    @DisplayName("nulls are visible on every column, and boxed access returns null")
    void nullsAreVisible() {
        BatchRow row = new BatchRow(batch());
        row.position(1);
        for (int c = 0; c < SCHEMA.size(); c++) {
            assertTrue(row.isNull(c), "column " + c);
            assertNull(row.get(c), "column " + c);
        }
        row.position(0);
        for (int c = 0; c < SCHEMA.size(); c++) {
            assertFalse(row.isNull(c), "column " + c);
        }
    }

    @Test
    @DisplayName("the cursor is reused, so values change as it moves rather than being snapshots")
    void cursorIsReused() {
        BatchRow row = new BatchRow(batch());
        row.position(0);
        assertEquals(42, row.getInt(0));
        assertEquals(0, row.position());
        row.position(1);
        assertTrue(row.isNull(0), "the same object now reads the next row");
        assertEquals(1, row.position());
    }

    @Test
    @DisplayName("reset points at another batch and rewinds the cursor")
    void resetRewinds() {
        BatchRow row = new BatchRow(batch());
        row.position(1);
        row.reset(batch());
        assertEquals(-1, row.position());
        row.position(0);
        assertEquals(42, row.getInt(0));
    }

    @Test
    @DisplayName("positioning outside the batch is refused")
    void positionIsBoundsChecked() {
        BatchRow row = new BatchRow(batch());
        assertThrows(IndexOutOfBoundsException.class, () -> row.position(2));
        assertThrows(IndexOutOfBoundsException.class, () -> row.position(-1));
    }
}
