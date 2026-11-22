package columnar.exec.sort;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderingTest {

    @Test
    @DisplayName("NaN sorts above infinity, and -0.0 ties with 0.0")
    void doubles() {
        assertTrue(Ordering.compareDoubles(Double.NaN, Double.POSITIVE_INFINITY) > 0);
        assertTrue(Ordering.compareDoubles(Double.NEGATIVE_INFINITY, -1e300) < 0);
        assertEquals(0, Ordering.compareDoubles(Double.NaN, Double.NaN));
        assertEquals(0, Ordering.compareDoubles(-0.0, 0.0), "SQL treats the zeros as equal");
        assertTrue(Double.compare(-0.0, 0.0) < 0, "which Double.compare does not");
    }

    @Test
    @DisplayName("strings order by code point, where compareTo gets it wrong")
    void codePointsNotUtf16() {
        String fullwidthTilde = "～";
        String emoji = "😀";
        assertTrue(Ordering.compareStrings(fullwidthTilde, emoji) < 0, "DuckDB's order");
        assertTrue(fullwidthTilde.compareTo(emoji) > 0, "compareTo disagrees");
        assertTrue(Ordering.compareStrings("ab", "abc") < 0, "a prefix sorts first");
        assertEquals(0, Ordering.compareStrings("", ""));
    }

    @Test
    @DisplayName("the string and UTF-8 byte comparisons agree on 20,000 random pairs")
    void stringAndBytesAgree() {
        Random random = new Random(11L);
        int[] alphabet = {'a', 'b', 'Z', 0xE9, 0x4E2D, 0xFF5E, 0x1F600, 0x10348};
        for (int trial = 0; trial < 20_000; trial++) {
            String a = randomString(random, alphabet);
            String b = randomString(random, alphabet);
            byte[] x = a.getBytes(UTF_8);
            byte[] y = b.getBytes(UTF_8);
            assertEquals(Integer.signum(Ordering.compareStrings(a, b)),
                    Ordering.compareUtf8(x, 0, x.length, y, 0, y.length), a + " vs " + b);
        }
    }

    private static String randomString(Random random, int[] alphabet) {
        StringBuilder out = new StringBuilder();
        int length = random.nextInt(5);
        for (int i = 0; i < length; i++) {
            out.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
        }
        return out.toString();
    }
}
