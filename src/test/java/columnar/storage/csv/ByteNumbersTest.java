package columnar.storage.csv;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ByteNumbersTest {

    private static long asLong(String text) {
        byte[] b = text.getBytes(UTF_8);
        return ByteNumbers.parseLong(b, 0, b.length);
    }

    private static int asInt(String text) {
        byte[] b = text.getBytes(UTF_8);
        return ByteNumbers.parseInt(b, 0, b.length);
    }

    private static double asDouble(String text) {
        byte[] b = text.getBytes(UTF_8);
        return ByteNumbers.parseDouble(b, 0, b.length);
    }

    private static boolean asBoolean(String text) {
        byte[] b = text.getBytes(UTF_8);
        return ByteNumbers.parseBoolean(b, 0, b.length);
    }

    @Test
    @DisplayName("both long extremes parse, which is where a negate-at-the-end parser breaks")
    void parsesLongExtremes() {
        assertEquals(Long.MIN_VALUE, asLong("-9223372036854775808"));
        assertEquals(Long.MAX_VALUE, asLong("9223372036854775807"));
        assertEquals(0L, asLong("0"));
        assertEquals(0L, asLong("-0"));
        assertEquals(42L, asLong("+42"));
        assertEquals(-7L, asLong("-7"));
    }

    @Test
    @DisplayName("random longs agree with Long.parseLong")
    void matchesJdkOnRandomValues() {
        Random random = new Random(20261018L); // fixed seed: failures reproduce
        for (int i = 0; i < 20_000; i++) {
            long value = random.nextLong();
            String text = Long.toString(value);
            assertEquals(Long.parseLong(text), asLong(text), text);
        }
    }

    @Test
    @DisplayName("overflow is detected rather than wrapped around")
    void detectsOverflow() {
        assertThrows(NumberFormatException.class, () -> asLong("9223372036854775808"));
        assertThrows(NumberFormatException.class, () -> asLong("-9223372036854775809"));
        assertThrows(NumberFormatException.class, () -> asLong("99999999999999999999999"));
    }

    @Test
    @DisplayName("malformed integers fail instead of parsing a prefix")
    void rejectsMalformedIntegers() {
        assertThrows(NumberFormatException.class, () -> asLong(""));
        assertThrows(NumberFormatException.class, () -> asLong("-"));
        assertThrows(NumberFormatException.class, () -> asLong("+"));
        assertThrows(NumberFormatException.class, () -> asLong("12a"));
        assertThrows(NumberFormatException.class, () -> asLong(" 12"));
        assertThrows(NumberFormatException.class, () -> asLong("1.5"));
    }

    @Test
    @DisplayName("an int that would truncate is refused, not silently narrowed")
    void intRangeIsEnforced() {
        assertEquals(Integer.MIN_VALUE, asInt("-2147483648"));
        assertEquals(Integer.MAX_VALUE, asInt("2147483647"));
        assertThrows(NumberFormatException.class, () -> asInt("2147483648"));
        assertThrows(NumberFormatException.class, () -> asInt("-2147483649"));
    }

    @Test
    @DisplayName("doubles parse with exponents and keep negative zero")
    void parsesDoubles() {
        assertEquals(1.5, asDouble("1.5"));
        assertEquals(-0.07, asDouble("-0.07"));
        assertEquals(1000.0, asDouble("1e3"));
        assertEquals(24710.35, asDouble("24710.35"));
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(asDouble("-0.0")));
        assertThrows(NumberFormatException.class, () -> asDouble(""));
        assertThrows(NumberFormatException.class, () -> asDouble("x"));
    }

    @Test
    @DisplayName("booleans accept the usual spellings and reject anything else")
    void parsesBooleans() {
        for (String yes : new String[] {"t", "T", "1", "true", "TRUE", "True"}) {
            assertTrue(asBoolean(yes), yes);
        }
        for (String no : new String[] {"f", "F", "0", "false", "FALSE"}) {
            assertFalse(asBoolean(no), no);
        }
        assertThrows(IllegalArgumentException.class, () -> asBoolean("yes"));
        assertThrows(IllegalArgumentException.class, () -> asBoolean(""));
    }

    @Test
    @DisplayName("parsing reads a number from the middle of a record buffer")
    void parsesFromAnOffset() {
        byte[] record = "a|-123|b".getBytes(UTF_8);
        assertEquals(-123L, ByteNumbers.parseLong(record, 2, 4));
    }
}
