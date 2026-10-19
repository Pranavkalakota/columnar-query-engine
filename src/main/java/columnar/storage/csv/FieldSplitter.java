package columnar.storage.csv;

import java.util.Arrays;

/** Splits one already-framed record into field ranges. */
public final class FieldSplitter {

    private final DelimitedOptions options;
    private int[] start = new int[16];
    private int[] length = new int[16];
    private int count;

    public FieldSplitter(DelimitedOptions options) {
        this.options = options;
    }

    public int count() {
        return count;
    }

    public int start(int index) {
        return start[index];
    }

    public int length(int index) {
        return length[index];
    }

    /** Splits buf[from, to), line ending and any trailing delimiter already removed. */
    public void split(byte[] buf, int from, int to) {
        count = 0;
        int i = from;
        while (true) {
            if (options.quoted() && i < to && buf[i] == options.quote()) {
                i = readQuoted(buf, i, to);
            } else {
                int fieldFrom = i;
                while (i < to && buf[i] != options.delimiter()) {
                    i++;
                }
                add(fieldFrom, i - fieldFrom);
            }
            if (i >= to) {
                return;
            }
            i++;
            if (i == to) {
                add(to, 0);
                return;
            }
        }
    }

    /** Reads a quoted field, unescaping a doubled quote in place. */
    private int readQuoted(byte[] buf, int openQuote, int to) {
        int contentStart = openQuote + 1;
        int write = contentStart;
        int read = contentStart;
        while (read < to) {
            byte b = buf[read];
            if (b != options.quote()) {
                buf[write++] = b;
                read++;
                continue;
            }
            if (read + 1 < to && buf[read + 1] == options.quote()) {
                buf[write++] = options.quote();
                read += 2;
                continue;
            }
            add(contentStart, write - contentStart);
            read++;
            if (read < to && buf[read] != options.delimiter()) {
                throw new IllegalArgumentException("text after a closing quote");
            }
            return read;
        }
        throw new IllegalArgumentException("record ends inside a quoted field");
    }

    private void add(int fieldStart, int fieldLength) {
        if (count == start.length) {
            start = Arrays.copyOf(start, count * 2);
            length = Arrays.copyOf(length, count * 2);
        }
        start[count] = fieldStart;
        length[count] = fieldLength;
        count++;
    }
}
