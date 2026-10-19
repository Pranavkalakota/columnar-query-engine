package columnar.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.types.DataType;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BooleanVectorTest {

    @Test
    @DisplayName("bits round-trip and a false is distinct from a null")
    void storesBitsAndNulls() {
        BooleanVector v = new BooleanVector(3);
        v.set(0, true);
        v.set(1, false);
        v.setNull(2);
        assertTrue(v.get(0));
        assertFalse(v.get(1));
        assertEquals(Boolean.TRUE, v.valueAt(0));
        assertEquals(Boolean.FALSE, v.valueAt(1), "false is a value, not an absence");
        assertNull(v.valueAt(2));
        assertEquals(DataType.BOOLEAN, v.type());
    }

    @Test
    @DisplayName("random patterns across word boundaries match a boolean[] reference")
    void matchesReferenceAcrossWordBoundaries() {
        Random random = new Random(20261017L); // fixed seed: failures reproduce
        for (int size : new int[] {1, 63, 64, 65, 127, 128, 129, 1000}) {
            BooleanVector v = new BooleanVector(size);
            boolean[] expected = new boolean[size];
            for (int i = 0; i < size; i++) {
                expected[i] = random.nextBoolean();
                v.set(i, expected[i]);
            }
            for (int i = 0; i < size; i++) {
                assertEquals(expected[i], v.get(i), "size " + size + " row " + i);
            }
        }
    }

    @Test
    @DisplayName("setting a bit back to false clears it, rather than leaving it set")
    void clearingWorks() {
        BooleanVector v = new BooleanVector(70);
        v.set(65, true);
        assertTrue(v.get(65));
        v.set(65, false);
        assertFalse(v.get(65), "a bit in the second word must clear too");
    }

    @Test
    @DisplayName("reset clears every bit, so a reused vector does not leak the last batch")
    void resetClearsBits() {
        BooleanVector v = new BooleanVector(128);
        for (int i = 0; i < 128; i++) {
            v.set(i, true);
        }
        v.setNull(0);
        v.reset(128);
        assertFalse(v.mayHaveNulls());
        for (int i = 0; i < 128; i++) {
            assertFalse(v.get(i), "row " + i + " should be false after reset");
        }
    }

    @Test
    @DisplayName("writes past capacity are refused")
    void boundsAreChecked() {
        BooleanVector v = new BooleanVector(2);
        assertThrows(IndexOutOfBoundsException.class, () -> v.set(2, true));
        assertThrows(IndexOutOfBoundsException.class, () -> v.valueAt(2));
    }
}
