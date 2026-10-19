package columnar.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StringVectorTest {

    @Test
    @DisplayName("values round-trip, including multi-byte UTF-8 where bytes outnumber characters")
    void roundTripsIncludingUnicode() {
        StringVector v = new StringVector(3);
        v.append("DELIVER IN PERSON");
        v.append("é漢🚀");
        v.append("");
        assertEquals("DELIVER IN PERSON", v.getString(0));
        assertEquals("é漢🚀", v.getString(1));
        assertEquals(9, v.byteLength(1), "2 + 3 + 4 UTF-8 bytes, not 4 chars");
        assertEquals(3, v.rowCount());
    }

    @Test
    @DisplayName("an empty string and a NULL are both zero bytes, told apart only by validity")
    void emptyStringIsNotNull() {
        StringVector v = new StringVector(2);
        v.append("");
        v.appendNull();
        assertEquals(0, v.byteLength(0));
        assertEquals(0, v.byteLength(1));
        assertFalse(v.isNull(0));
        assertTrue(v.isNull(1));
        assertEquals("", v.valueAt(0));
        assertNull(v.valueAt(1));
    }

    @Test
    @DisplayName("the byte pool grows for values far larger than the initial budget")
    void bytePoolGrows() {
        StringVector v = new StringVector(3, 4);
        String big = "x".repeat(10_000);
        v.append("a");
        v.append(big);
        v.append("z");
        assertEquals("a", v.getString(0));
        assertEquals(big, v.getString(1));
        assertEquals("z", v.getString(2), "a value after a reallocation is still intact");
    }

    @Test
    @DisplayName("reset restarts appending, and a row never written is refused not guessed")
    void resetRestartsAndStaleReadsFail() {
        StringVector v = new StringVector(4);
        v.append("first");
        v.append("second");
        v.reset(4);
        assertEquals(0, v.rowCount());
        assertFalse(v.mayHaveNulls());
        assertThrows(IndexOutOfBoundsException.class, () -> v.getString(0),
                "offsets still hold the old batch, so this must fail rather than return stale bytes");
        v.append("fresh");
        assertEquals("fresh", v.getString(0));
    }

    @Test
    @DisplayName("appending past capacity is refused")
    void appendPastCapacityFails() {
        StringVector v = new StringVector(1);
        v.append("a");
        assertThrows(IndexOutOfBoundsException.class, () -> v.append("b"));
    }
}
