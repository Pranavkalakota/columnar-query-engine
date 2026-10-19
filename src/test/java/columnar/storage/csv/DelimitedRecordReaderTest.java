package columnar.storage.csv;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DelimitedRecordReaderTest {

    private static List<List<String>> read(DelimitedOptions options, String text, int bufferSize)
            throws IOException {
        List<List<String>> records = new ArrayList<>();
        try (DelimitedRecordReader reader = new DelimitedRecordReader(
                new ByteArrayInputStream(text.getBytes(UTF_8)), options, bufferSize)) {
            while (reader.nextRecord()) {
                List<String> fields = new ArrayList<>();
                for (int i = 0; i < reader.fieldCount(); i++) {
                    fields.add(new String(reader.data(), reader.fieldStart(i), reader.fieldLength(i), UTF_8));
                }
                records.add(fields);
            }
        }
        return records;
    }

    private static List<List<String>> tbl(String text) throws IOException {
        return read(DelimitedOptions.tbl(), text, 1 << 16);
    }

    private static List<List<String>> csv(String text) throws IOException {
        return read(DelimitedOptions.csv(), text, 1 << 16);
    }

    @Test
    @DisplayName("a .tbl line loses its trailing delimiter, so 17 pipes give 16 fields")
    void stripsTrailingDelimiter() throws IOException {
        assertEquals(List.of(List.of("1", "2", "3")), tbl("1|2|3|\n"));
        assertEquals(List.of(List.of("a", "", "c")), tbl("a||c|\n"));
        assertEquals(List.of(List.of("")), tbl("|\n"), "a lone delimiter is one empty field");
    }

    @Test
    @DisplayName("a .tbl line without its trailing delimiter is refused, not quietly accepted")
    void missingTrailingDelimiterFails() {
        IOException e = assertThrows(IOException.class, () -> tbl("1|2|3\n"));
        assertTrue(e.getMessage().contains("record 1"), e.getMessage());
    }

    @Test
    @DisplayName("both line endings work, with or without a final newline")
    void lineEndings() throws IOException {
        assertEquals(List.of(List.of("a"), List.of("b")), tbl("a|\nb|\n"));
        assertEquals(List.of(List.of("a"), List.of("b")), tbl("a|\r\nb|\r\n"));
        assertEquals(List.of(List.of("a"), List.of("b")), tbl("a|\nb|"), "no final newline");
        assertEquals(List.of(), tbl(""), "empty input has no records");
    }

    @Test
    @DisplayName("blank lines are skipped rather than read as a row of one empty field")
    void blankLinesSkipped() throws IOException {
        assertEquals(List.of(List.of("a"), List.of("b")), tbl("a|\n\n\nb|\n\n"));
    }

    @Test
    @DisplayName("a quoted field may contain a newline, which does not end the record")
    void quotedNewlineDoesNotEndTheRecord() throws IOException {
        assertEquals(List.of(List.of("a\nb", "c")), csv("\"a\nb\",c\n"));
        assertEquals(List.of(List.of("x"), List.of("y")), csv("x\ny\n"));
    }

    @Test
    @DisplayName("records and fields longer than the buffer still read, because the buffer grows")
    void growsForOversizedRecords() throws IOException {
        String longField = "z".repeat(5000);
        List<List<String>> records = read(DelimitedOptions.tbl(), "a|" + longField + "|\nb|c|\n", 16);
        assertEquals(2, records.size());
        assertEquals(List.of("a", longField), records.get(0));
        assertEquals(List.of("b", "c"), records.get(1), "the reader recovers after growing");
    }

    @Test
    @DisplayName("a quoted field spanning a buffer refill is still unescaped correctly")
    void quotedFieldAcrossRefill() throws IOException {
        String inner = "q\"\"".repeat(200);
        List<List<String>> records = read(DelimitedOptions.csv(), "\"" + inner + "\",tail\n", 16);
        assertEquals("q\"".repeat(200), records.get(0).get(0));
        assertEquals("tail", records.get(0).get(1));
    }

    @Test
    @DisplayName("multi-byte UTF-8 survives, including across a small buffer")
    void unicodeSurvives() throws IOException {
        String text = "漢字|🚀|\n";
        assertEquals(List.of(List.of("漢字", "🚀")), tbl(text));
        assertEquals(List.of(List.of("漢字", "🚀")),
                read(DelimitedOptions.tbl(), text, 16));
    }

    @Test
    @DisplayName("record numbers count from one, for error messages")
    void recordNumbersCountFromOne() throws IOException {
        try (DelimitedRecordReader reader = new DelimitedRecordReader(
                new ByteArrayInputStream("a|\nb|\n".getBytes(UTF_8)), DelimitedOptions.tbl())) {
            assertTrue(reader.nextRecord());
            assertEquals(1, reader.recordNumber());
            assertTrue(reader.nextRecord());
            assertEquals(2, reader.recordNumber());
            assertFalse(reader.nextRecord());
        }
    }

    @Test
    @DisplayName("a real TPC-H region.tbl line parses into its five-ish fields")
    void parsesATpchLine() throws IOException {
        String line = "0|AFRICA|lar deposits. blithely final packages cajole.|\n";
        assertEquals(List.of(List.of("0", "AFRICA", "lar deposits. blithely final packages cajole.")),
                tbl(line));
    }
}
