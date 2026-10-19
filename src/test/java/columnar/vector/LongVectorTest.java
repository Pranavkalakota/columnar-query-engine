package columnar.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.types.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LongVectorTest {

    @Test
    @DisplayName("the full long range round-trips, including both extremes")
    void storesFullRange() {
        LongVector v = new LongVector(4);
        v.set(0, Long.MIN_VALUE);
        v.set(1, Long.MAX_VALUE);
        v.set(2, 0L);
        v.setNull(3);
        assertEquals(Long.MIN_VALUE, v.get(0));
        assertEquals(Long.MAX_VALUE, v.get(1));
        assertEquals(0L, v.get(2));
        assertEquals(Long.MAX_VALUE, v.valueAt(1));
        assertNull(v.valueAt(3));
        assertEquals(DataType.INT64, v.type());
    }

    @Test
    @DisplayName("reset clears nulls and reuses the array when it is already big enough")
    void resetReuses() {
        LongVector v = new LongVector(8);
        long[] before = v.values();
        v.setNull(0);
        v.reset(4);
        assertFalse(v.mayHaveNulls());
        assertFalse(v.isNull(0));
        assertTrue(before == v.values(), "shrinking should not reallocate");
        v.reset(16);
        v.set(15, 5L);
        assertEquals(5L, v.get(15));
    }

    @Test
    @DisplayName("writes past capacity are refused")
    void boundsAreChecked() {
        LongVector v = new LongVector(2);
        assertThrows(IndexOutOfBoundsException.class, () -> v.set(2, 1L));
        assertThrows(IndexOutOfBoundsException.class, () -> v.valueAt(2));
    }
}
