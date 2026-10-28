package columnar.exec.row;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import columnar.storage.InMemoryTable;
import columnar.storage.csv.DelimitedOptions;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoxedRowScanTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("k", DataType.INT64), Field.of("d", DataType.DOUBLE),
            Field.of("s", DataType.VARCHAR), Field.of("day", DataType.DATE));

    private static InMemoryTable table(int rows, int batchSize) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            text.append(i).append(',').append(i % 3 == 0 ? "" : String.valueOf(i * 0.5))
                    .append(",s").append(i).append(",1994-01-0").append(i % 9 + 1).append('\n');
        }
        return InMemoryTable.load(new ByteArrayInputStream(text.toString().getBytes(UTF_8)),
                SCHEMA, DelimitedOptions.csv(), batchSize);
    }

    private static List<List<Object>> drain(RowOperator op) {
        List<List<Object>> seen = new ArrayList<>();
        op.open();
        Row row;
        while ((row = op.next()) != null) {
            seen.add(Arrays.asList(row.get(0), row.get(1), row.get(2), row.get(3)));
        }
        op.close();
        return seen;
    }

    @Test
    @DisplayName("the boxed and tuned scans produce identical values")
    void enginesAgree() throws IOException {
        for (int batchSize : new int[] {1, 3, 8, 64}) {
            List<List<Object>> boxed = drain(new BoxedRowScan(table(20, batchSize)));
            List<List<Object>> tuned = drain(new RowScan(table(20, batchSize)));
            assertEquals(tuned, boxed, "batch " + batchSize);
        }
    }

    @Test
    @DisplayName("every row comes back exactly once, at every batch size and row count")
    void everyRowExactlyOnce() throws IOException {
        for (int batchSize : new int[] {1, 2, 3, 7, 64}) {
            for (int rows : new int[] {0, 1, batchSize - 1, batchSize, batchSize + 1, 2 * batchSize + 1}) {
                List<List<Object>> seen = drain(new BoxedRowScan(table(rows, batchSize)));
                String where = "batch " + batchSize + " rows " + rows;
                assertEquals(rows, seen.size(), where);
                for (int i = 0; i < rows; i++) {
                    assertEquals((long) i, seen.get(i).get(0), where + " at " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("a fresh row is allocated per row, unlike the tuned cursor")
    void allocatesPerRow() throws IOException {
        BoxedRowScan boxed = new BoxedRowScan(table(3, 2));
        boxed.open();
        Row first = boxed.next();
        Row second = boxed.next();
        assertNotSame(first, second, "the boxed engine allocates; that is the cost measured");
        assertEquals(0L, first.get(0), "the earlier row keeps its own values");
        assertEquals(1L, second.get(0));
        boxed.close();
    }

    @Test
    @DisplayName("a DATE stores as an epoch day but shows as a LocalDate")
    void dateStoresAsEpochDayShowsAsDate() throws IOException {
        BoxedRowScan boxed = new BoxedRowScan(table(1, 4));
        RowScan tuned = new RowScan(table(1, 4));
        boxed.open();
        tuned.open();
        Row b = boxed.next();
        Row t = tuned.next();
        assertEquals(8766, b.getInt(3), "the evaluator reads the epoch day");
        assertEquals(t.getInt(3), b.getInt(3));
        assertEquals(LocalDate.of(1994, 1, 1), b.get(3), "boxed access shows a date");
        assertEquals(t.get(3), b.get(3), "and both engines agree");
        boxed.close();
        tuned.close();
    }

    @Test
    @DisplayName("next before open is refused, and the schema comes from the table")
    void mustBeOpened() throws IOException {
        BoxedRowScan boxed = new BoxedRowScan(table(2, 2));
        assertThrows(IllegalStateException.class, boxed::next);
        assertEquals(SCHEMA, boxed.schema());
    }
}
