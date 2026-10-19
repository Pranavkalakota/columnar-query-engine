package columnar.storage.csv;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.VectorBatch;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DelimitedLoaderTest {

    private static final Schema ONE_LONG = Schema.of(Field.of("k", DataType.INT64));

    private static final Schema EVERY_TYPE = Schema.of(
            Field.of("i", DataType.INT32), Field.of("l", DataType.INT64), Field.of("d", DataType.DOUBLE),
            Field.of("b", DataType.BOOLEAN), Field.of("s", DataType.VARCHAR), Field.of("day", DataType.DATE));

    /** Loads every batch and flattens the boxed values, so nothing can be double counted. */
    private static List<List<Object>> loadAll(Schema schema, String text, int batchSize) throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        VectorBatch batch = VectorBatch.allocate(schema, batchSize);
        try (DelimitedLoader loader = new DelimitedLoader(
                new ByteArrayInputStream(text.getBytes(UTF_8)), schema, DelimitedOptions.tbl())) {
            while (loader.loadInto(batch)) {
                for (int row = 0; row < batch.rowCount(); row++) {
                    List<Object> values = new ArrayList<>();
                    for (int column = 0; column < schema.size(); column++) {
                        values.add(batch.column(column).valueAt(row));
                    }
                    rows.add(values);
                }
            }
        }
        return rows;
    }

    @Test
    @DisplayName("every row arrives exactly once, at every batch size and row count")
    void loadsEveryRowExactlyOnce() throws IOException {
        // Row counts straddle the batch boundary, which is where a loader
        // drops or repeats a row.
        for (int batchSize : new int[] {1, 2, 3, 7, 64}) {
            for (int rowCount : new int[] {0, 1, batchSize - 1, batchSize, batchSize + 1,
                    2 * batchSize, 2 * batchSize + 1}) {
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < rowCount; i++) {
                    text.append(i).append("|\n");
                }
                List<List<Object>> rows = loadAll(ONE_LONG, text.toString(), batchSize);
                String where = "batch " + batchSize + " rows " + rowCount;
                assertEquals(rowCount, rows.size(), where);
                for (int i = 0; i < rowCount; i++) {
                    assertEquals((long) i, rows.get(i).get(0), where + " at " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("every type parses out of the byte buffer")
    void parsesEveryType() throws IOException {
        List<List<Object>> rows = loadAll(EVERY_TYPE,
                "-7|9223372036854775807|24710.35|t|DELIVER IN PERSON|1996-03-13|\n", 4);
        assertEquals(List.of(-7, 9223372036854775807L, 24710.35, true, "DELIVER IN PERSON",
                LocalDate.of(1996, 3, 13)), rows.get(0));
    }

    @Test
    @DisplayName("an empty field is NULL, matching the oracle, for strings as well as numbers")
    void emptyFieldIsNull() throws IOException {
        List<List<Object>> rows = loadAll(EVERY_TYPE, "||||||\n1|2|3.0|f|x|1970-01-01|\n", 4);
        for (Object value : rows.get(0)) {
            assertNull(value, "every empty field should be NULL");
        }
        // The row after an all-NULL row still lands, proving the string
        // vector's offsets kept advancing through the NULL.
        assertEquals("x", rows.get(1).get(4));
        assertEquals(LocalDate.of(1970, 1, 1), rows.get(1).get(5));
    }

    @Test
    @DisplayName("a record with the wrong field count is refused, not padded")
    void fieldCountMismatchFails() {
        IOException e = assertThrows(IOException.class, () -> loadAll(ONE_LONG, "1|2|\n", 4));
        assertTrue(e.getMessage().contains("2 fields"), e.getMessage());
    }

    @Test
    @DisplayName("a parse failure names the record and the column rather than substituting zero")
    void parseErrorNamesRecordAndColumn() {
        IOException e = assertThrows(IOException.class,
                () -> loadAll(ONE_LONG, "1|\n2|\nnotanumber|\n", 4));
        assertTrue(e.getMessage().contains("record 3"), e.getMessage());
        assertTrue(e.getMessage().contains("'k'"), e.getMessage());
    }

    @Test
    @DisplayName("a header row is skipped when the format has one")
    void skipsHeader() throws IOException {
        VectorBatch batch = VectorBatch.allocate(ONE_LONG, 4);
        try (DelimitedLoader loader = new DelimitedLoader(
                new ByteArrayInputStream("k|\n11|\n".getBytes(UTF_8)),
                ONE_LONG, DelimitedOptions.tbl(), true, 1 << 16)) {
            assertTrue(loader.loadInto(batch));
            assertEquals(1, batch.rowCount());
            assertEquals(11L, batch.column(0).valueAt(0));
            assertFalse(loader.loadInto(batch));
            assertEquals(1, loader.rowsRead());
        }
    }
}
