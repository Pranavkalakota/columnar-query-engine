package columnar.exec.row;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ObjectRowTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("i", DataType.INT32), Field.of("l", DataType.INT64),
            Field.of("d", DataType.DOUBLE), Field.of("b", DataType.BOOLEAN),
            Field.of("s", DataType.VARCHAR));

    @Test
    @DisplayName("values round-trip through the typed accessors")
    void roundTrip() {
        ObjectRow row = new ObjectRow(SCHEMA);
        row.set(0, 7);
        row.set(1, 8L);
        row.set(2, 1.5);
        row.set(3, true);
        row.set(4, "x");
        assertEquals(7, row.getInt(0));
        assertEquals(8L, row.getLong(1));
        assertEquals(1.5, row.getDouble(2));
        assertTrue(row.getBoolean(3));
        assertEquals("x", row.getString(4));
        assertEquals(SCHEMA, row.schema());
    }

    @Test
    @DisplayName("a column is null until something is written to it")
    void startsNull() {
        ObjectRow row = new ObjectRow(SCHEMA);
        for (int c = 0; c < SCHEMA.size(); c++) {
            assertTrue(row.isNull(c), "column " + c);
            assertNull(row.get(c), "column " + c);
        }
        row.set(0, 1);
        assertFalse(row.isNull(0));
        row.set(0, null);
        assertTrue(row.isNull(0), "writing null clears it again");
    }

    @Test
    @DisplayName("a numeric accessor converts across widths rather than refusing")
    void numericAccessorsConvert() {
        ObjectRow row = new ObjectRow(SCHEMA);
        row.set(1, 5);
        assertEquals(5L, row.getLong(1), "an Integer still reads as a long");
        row.set(2, 3L);
        assertEquals(3.0, row.getDouble(2), "a Long still reads as a double");
    }
}
