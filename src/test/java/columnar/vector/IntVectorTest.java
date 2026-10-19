package columnar.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.types.DataType;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntVectorTest {

    @Test
    @DisplayName("values round-trip and nulls are tracked separately from the data")
    void storesValuesAndNulls() {
        IntVector v = IntVector.ints(4);
        v.set(0, 42);
        v.set(1, -7);
        v.setNull(2);
        assertEquals(42, v.get(0));
        assertEquals(-7, v.get(1));
        assertTrue(v.isNull(2));
        assertFalse(v.isNull(0));
        assertEquals(42, v.valueAt(0));
        assertNull(v.valueAt(2));
        assertEquals(DataType.INT32, v.type());
    }

    @Test
    @DisplayName("a DATE vector shares int storage, so the epoch is 0 and earlier is negative")
    void datesAreDaysSinceEpoch() {
        IntVector v = IntVector.dates(3);
        v.set(0, 0);
        v.set(1, -1);
        v.set(2, 19723);
        assertEquals(DataType.DATE, v.type());
        assertEquals(LocalDate.of(1970, 1, 1), v.valueAt(0));
        assertEquals(LocalDate.of(1969, 12, 31), v.valueAt(1));
        assertEquals(LocalDate.of(2024, 1, 1), v.valueAt(2));
    }

    @Test
    @DisplayName("reset clears nulls, resizes, and reuses the array when it is big enough")
    void resetReuses() {
        IntVector v = IntVector.ints(8);
        int[] before = v.values();
        v.setNull(0);
        v.reset(4);
        assertEquals(4, v.capacity());
        assertFalse(v.mayHaveNulls());
        assertFalse(v.isNull(0));
        assertSameArray(before, v.values());
        v.reset(64);
        assertEquals(64, v.capacity());
        v.set(63, 9);
        assertEquals(9, v.get(63));
    }

    private static void assertSameArray(int[] expected, int[] actual) {
        assertTrue(expected == actual, "shrinking should not reallocate");
    }

    @Test
    @DisplayName("writes past capacity are refused, including after a shrink")
    void boundsAreChecked() {
        IntVector v = IntVector.ints(64);
        v.reset(2);
        assertThrows(IndexOutOfBoundsException.class, () -> v.set(2, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> v.valueAt(2));
        assertThrows(IndexOutOfBoundsException.class, () -> v.set(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> v.reset(-1));
    }
}
