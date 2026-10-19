package columnar.storage.csv;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FieldSplitterTest {

    private static List<String> split(DelimitedOptions options, String record) {
        byte[] buf = record.getBytes(UTF_8);
        FieldSplitter splitter = new FieldSplitter(options);
        splitter.split(buf, 0, buf.length);
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < splitter.count(); i++) {
            fields.add(new String(buf, splitter.start(i), splitter.length(i), UTF_8));
        }
        return fields;
    }

    private static List<String> csv(String record) {
        return split(DelimitedOptions.csv(), record);
    }

    private static List<String> pipes(String record) {
        return split(DelimitedOptions.tbl(), record);
    }

    @Test
    @DisplayName("plain fields split on the delimiter")
    void plainFields() {
        assertEquals(List.of("1", "two", "3"), csv("1,two,3"));
        assertEquals(List.of("1", "two", "3"), pipes("1|two|3"));
        assertEquals(List.of("only"), csv("only"));
    }

    @Test
    @DisplayName("empty fields are reported at any position, and an empty record is one field")
    void emptyFields() {
        assertEquals(List.of(""), csv(""), "zero-length range is one empty field, not zero");
        assertEquals(List.of("", "b", ""), csv(",b,"));
        assertEquals(List.of("", "", ""), csv(",,"));
        assertEquals(List.of("a", "", "c"), pipes("a||c"));
    }

    @Test
    @DisplayName("a quoted field may contain the delimiter")
    void quotedFieldHidesDelimiter() {
        assertEquals(List.of("a,b", "c"), csv("\"a,b\",c"));
        assertEquals(List.of("x", "y,z"), csv("x,\"y,z\""));
    }

    @Test
    @DisplayName("a doubled quote unescapes to one quote")
    void doubledQuoteUnescapes() {
        assertEquals(List.of("say \"hi\""), csv("\"say \"\"hi\"\"\""));
        assertEquals(List.of("\""), csv("\"\"\"\""));
        assertEquals(List.of("", "next"), csv("\"\",next"));
    }

    @Test
    @DisplayName("quoting is off for .tbl, so a quote is just a byte")
    void tblTreatsQuotesAsData() {
        assertEquals(List.of("\"a", "b\""), pipes("\"a|b\""));
    }

    @Test
    @DisplayName("multi-byte UTF-8 passes through, since it never collides with the delimiter")
    void unicodePassesThrough() {
        assertEquals(List.of("漢字", "🚀"), csv("漢字,🚀"));
    }

    @Test
    @DisplayName("malformed quoting is refused rather than guessed at")
    void malformedQuotingFails() {
        assertThrows(IllegalArgumentException.class, () -> csv("\"unterminated"));
        assertThrows(IllegalArgumentException.class, () -> csv("\"closed\"junk"));
    }

    @Test
    @DisplayName("the field arrays grow past their initial size")
    void growsBeyondInitialCapacity() {
        String record = String.join(",", java.util.Collections.nCopies(100, "f"));
        assertEquals(100, csv(record).size());
    }

    @Test
    @DisplayName("splitting reads a record out of the middle of a bigger buffer")
    void splitsAnInnerRange() {
        byte[] buf = "XXa,bYY".getBytes(UTF_8);
        FieldSplitter splitter = new FieldSplitter(DelimitedOptions.csv());
        splitter.split(buf, 2, 5);
        assertEquals(2, splitter.count());
        assertEquals("a", new String(buf, splitter.start(0), splitter.length(0), UTF_8));
        assertEquals("b", new String(buf, splitter.start(1), splitter.length(1), UTF_8));
    }
}
