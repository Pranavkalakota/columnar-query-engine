package columnar.storage.csv;

import java.nio.charset.StandardCharsets;

/**
 * Parses numbers straight out of a UTF-8 byte range, with no intermediate
 * String, so loading a column does not allocate once per value.
 *
 * <p>The integer path is hand-rolled, using the JDK's trick of accumulating
 * negatively: {@code Long.MIN_VALUE} has no positive counterpart, so a parser
 * that builds a positive value and negates it cannot represent it. Overflow is
 * detected rather than wrapped, because a silently wrapped key is worse than a
 * failed load.
 *
 * <p>Doubles go through {@link Double#parseDouble} on a String, which does
 * allocate. Correct decimal-to-binary rounding is genuinely hard and getting it
 * slightly wrong would put every aggregate a few bits away from the oracle, so
 * this is the one place where the allocation is worth it. The loader is not on
 * the query hot path; data is loaded once and queried many times.
 */
public final class ByteNumbers {

    private ByteNumbers() {
    }

    public static long parseLong(byte[] bytes, int offset, int length) {
        if (length == 0) {
            throw new NumberFormatException("empty number");
        }
        int i = offset;
        int end = offset + length;
        boolean negative = bytes[i] == '-';
        if (negative || bytes[i] == '+') {
            i++;
        }
        if (i == end) {
            throw new NumberFormatException("a sign is not a number: " + text(bytes, offset, length));
        }
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multiplyLimit = limit / 10;
        long result = 0;
        while (i < end) {
            int digit = bytes[i++] - '0';
            if (digit < 0 || digit > 9) {
                throw new NumberFormatException("not a number: " + text(bytes, offset, length));
            }
            if (result < multiplyLimit) {
                throw new NumberFormatException("out of range for a long: " + text(bytes, offset, length));
            }
            result *= 10;
            if (result < limit + digit) {
                throw new NumberFormatException("out of range for a long: " + text(bytes, offset, length));
            }
            result -= digit;
        }
        return negative ? result : -result;
    }

    /** Parses an int, rejecting a value that would silently truncate. */
    public static int parseInt(byte[] bytes, int offset, int length) {
        long value = parseLong(bytes, offset, length);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new NumberFormatException("out of range for an int: " + text(bytes, offset, length));
        }
        return (int) value;
    }

    /** Parses a double. Allocates a String; see the class note for why. */
    public static double parseDouble(byte[] bytes, int offset, int length) {
        if (length == 0) {
            throw new NumberFormatException("empty number");
        }
        return Double.parseDouble(text(bytes, offset, length));
    }

    /**
     * Parses a boolean, accepting the spellings a delimited file is likely to
     * carry. Anything else fails rather than defaulting to false, which would
     * turn a typo into data.
     */
    public static boolean parseBoolean(byte[] bytes, int offset, int length) {
        if (length == 1) {
            byte b = bytes[offset];
            if (b == 't' || b == 'T' || b == '1') {
                return true;
            }
            if (b == 'f' || b == 'F' || b == '0') {
                return false;
            }
        }
        String value = text(bytes, offset, length);
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("not a boolean: " + value);
    }

    private static String text(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.UTF_8);
    }
}
