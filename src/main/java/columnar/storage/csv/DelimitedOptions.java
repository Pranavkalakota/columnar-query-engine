package columnar.storage.csv;

/**
 * How to split a delimited file into records and fields.
 *
 * <p>One type covers both formats this engine loads, because the hard part,
 * finding record and field boundaries in a byte stream, is the same for each.
 *
 * @param delimiter the byte between fields
 * @param quote the quote byte, or {@link #NO_QUOTE} when the format has none
 * @param trailingDelimiter whether every record ends with a delimiter
 */
public record DelimitedOptions(byte delimiter, byte quote, boolean trailingDelimiter) {

    /** Marker for a format without quoting, so no byte is treated as special. */
    public static final byte NO_QUOTE = 0;

    public DelimitedOptions {
        if (delimiter == '\n' || delimiter == '\r') {
            throw new IllegalArgumentException("a line ending cannot be the delimiter");
        }
        if (quote != NO_QUOTE && quote == delimiter) {
            throw new IllegalArgumentException("the quote and the delimiter must differ");
        }
    }

    /**
     * TPC-H {@code .tbl}: pipe-delimited, no quoting, and a trailing delimiter
     * on every line, so a 16-column row carries 17 pipes.
     */
    public static DelimitedOptions tbl() {
        return new DelimitedOptions((byte) '|', NO_QUOTE, true);
    }

    /** Ordinary CSV: comma-delimited, double-quoted, {@code ""} escaping a quote. */
    public static DelimitedOptions csv() {
        return new DelimitedOptions((byte) ',', (byte) '"', false);
    }

    public boolean quoted() {
        return quote != NO_QUOTE;
    }

    /** A copy with a different delimiter, for pipe-separated files that are otherwise CSV. */
    public DelimitedOptions withDelimiter(char delimiter) {
        return new DelimitedOptions((byte) delimiter, quote, trailingDelimiter);
    }
}
