package columnar.storage.csv;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Splits a delimited byte stream into records and fields. */
public final class DelimitedRecordReader implements Closeable {

    private static final int DEFAULT_BUFFER = 1 << 16;

    private final InputStream in;
    private final DelimitedOptions options;

    private byte[] buf;
    private int bufLen;
    private int pos;
    private boolean eof;

    private final FieldSplitter fields;
    private long recordNumber;

    public DelimitedRecordReader(InputStream in, DelimitedOptions options) {
        this(in, options, DEFAULT_BUFFER);
    }

    public DelimitedRecordReader(InputStream in, DelimitedOptions options, int bufferSize) {
        this.in = in;
        this.options = options;
        this.fields = new FieldSplitter(options);
        this.buf = new byte[Math.max(16, bufferSize)];
    }

    /** The shared buffer the current record's fields point into. */
    public byte[] data() {
        return buf;
    }

    public int fieldCount() {
        return fields.count();
    }

    public int fieldStart(int index) {
        return fields.start(index);
    }

    public int fieldLength(int index) {
        return fields.length(index);
    }

    /** 1-based number of the record just read, for error messages. */
    public long recordNumber() {
        return recordNumber;
    }

    /** Advances to the next non-empty record. */
    public boolean nextRecord() throws IOException {
        while (true) {
            int end = findRecordEnd();
            if (end < 0) {
                return false;
            }
            int start = pos;
            int stop = end;
            pos = end < bufLen ? end + 1 : bufLen;
            if (stop > start && buf[stop - 1] == '\r') {
                stop--;
            }
            if (stop == start) {
                continue;
            }
            if (options.trailingDelimiter()) {
                if (buf[stop - 1] != options.delimiter()) {
                    throw new IOException("record %d does not end with '%c'"
                            .formatted(recordNumber + 1, (char) options.delimiter()));
                }
                stop--;
            }
            recordNumber++;
            try {
                fields.split(buf, start, stop);
            } catch (IllegalArgumentException e) {
                throw new IOException("record " + recordNumber + ": " + e.getMessage(), e);
            }
            return true;
        }
    }

    /** Newline index, bufLen at EOF, -1 when spent. */
    private int findRecordEnd() throws IOException {
        int i = pos;
        boolean inQuotes = false;
        boolean quoted = options.quoted();
        while (true) {
            if (i == bufLen) {
                if (eof) {
                    return pos == bufLen ? -1 : bufLen;
                }
                i -= fill();
                continue;
            }
            byte b = buf[i];
            if (quoted && b == options.quote()) {
                inQuotes = !inQuotes;
            } else if (!inQuotes && b == '\n') {
                return i;
            }
            i++;
        }
    }

    /** Compacts, grows if needed, then reads. */
    private int fill() throws IOException {
        int shift = pos;
        if (shift > 0) {
            System.arraycopy(buf, shift, buf, 0, bufLen - shift);
            bufLen -= shift;
            pos = 0;
        }
        if (bufLen == buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
        }
        int read = in.read(buf, bufLen, buf.length - bufLen);
        if (read < 0) {
            eof = true;
        } else {
            bufLen += read;
        }
        return shift;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
