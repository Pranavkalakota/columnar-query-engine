package columnar.storage.csv;

/** How to split a delimited file into records and fields. */
public record DelimitedOptions(byte delimiter, byte quote, boolean trailingDelimiter) {

    /** Marker for a format without quoting. */
    public static final byte NO_QUOTE = 0;

    public DelimitedOptions {
        if (delimiter == '\n' || delimiter == '\r') {
            throw new IllegalArgumentException("a line ending cannot be the delimiter");
        }
        if (quote != NO_QUOTE && quote == delimiter) {
            throw new IllegalArgumentException("the quote and the delimiter must differ");
        }
    }

    /** TPC-H .tbl: pipe-delimited, unquoted, trailing delimiter on every line. */
    public static DelimitedOptions tbl() {
        return new DelimitedOptions((byte) '|', NO_QUOTE, true);
    }

    /** Ordinary CSV: comma-delimited, double-quoted, "" escaping a quote. */
    public static DelimitedOptions csv() {
        return new DelimitedOptions((byte) ',', (byte) '"', false);
    }

    public boolean quoted() {
        return quote != NO_QUOTE;
    }

    /** A copy with a different delimiter. */
    public DelimitedOptions withDelimiter(char delimiter) {
        return new DelimitedOptions((byte) delimiter, quote, trailingDelimiter);
    }
}
