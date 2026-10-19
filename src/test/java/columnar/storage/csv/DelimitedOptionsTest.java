package columnar.storage.csv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DelimitedOptionsTest {

    @Test
    @DisplayName("the .tbl preset is pipe-delimited, unquoted, with a trailing delimiter")
    void tblPreset() {
        DelimitedOptions options = DelimitedOptions.tbl();
        assertEquals((byte) '|', options.delimiter());
        assertTrue(options.trailingDelimiter());
        assertFalse(options.quoted(), "dbgen does not quote, so a quote is ordinary data");
    }

    @Test
    @DisplayName("the CSV preset is comma-delimited and quoted, with no trailing delimiter")
    void csvPreset() {
        DelimitedOptions options = DelimitedOptions.csv();
        assertEquals((byte) ',', options.delimiter());
        assertEquals((byte) '"', options.quote());
        assertTrue(options.quoted());
        assertFalse(options.trailingDelimiter());
    }

    @Test
    @DisplayName("withDelimiter keeps the quoting rules, for pipe-separated files that quote")
    void withDelimiterKeepsQuoting() {
        DelimitedOptions options = DelimitedOptions.csv().withDelimiter('|');
        assertEquals((byte) '|', options.delimiter());
        assertTrue(options.quoted());
        assertFalse(options.trailingDelimiter());
    }

    @Test
    @DisplayName("a line ending cannot be the delimiter, since it ends the record")
    void rejectsLineEndingDelimiter() {
        assertThrows(IllegalArgumentException.class,
                () -> new DelimitedOptions((byte) '\n', DelimitedOptions.NO_QUOTE, false));
        assertThrows(IllegalArgumentException.class,
                () -> new DelimitedOptions((byte) '\r', DelimitedOptions.NO_QUOTE, false));
    }

    @Test
    @DisplayName("the quote and the delimiter cannot be the same byte")
    void rejectsAmbiguousQuote() {
        assertThrows(IllegalArgumentException.class,
                () -> new DelimitedOptions((byte) ',', (byte) ',', false));
    }
}
