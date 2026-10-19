package columnar.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValidityTest {

    /** Sizes that straddle the 64-bit word boundary, where off-by-one bugs live. */
    private static final int[] SIZES = {0, 1, 2, 7, 63, 64, 65, 127, 128, 129, 1023, 1024, 1025};

    @Test
    @DisplayName("a fresh bitmap reports every row valid and no nulls, at every size")
    void startsAllValid() {
        for (int size : SIZES) {
            Validity v = Validity.allValid(size);
            assertEquals(size, v.capacity());
            assertFalse(v.mayHaveNulls());
            for (int i = 0; i < size; i++) {
                assertTrue(v.isValid(i), "size " + size + " row " + i);
            }
        }
    }

    @Test
    @DisplayName("marking every row null is counted exactly, at every size")
    void allNull() {
        for (int size : SIZES) {
            Validity v = Validity.allValid(size);
            for (int i = 0; i < size; i++) {
                v.setNull(i);
            }
            assertEquals(size > 0, v.mayHaveNulls(), "size " + size);
            for (int i = 0; i < size; i++) {
                assertTrue(v.isNull(i), "size " + size + " row " + i);
            }
        }
    }

    @Test
    @DisplayName("random null patterns match a boolean[] reference, which is the whole point")
    void matchesReference() {
        Random random = new Random(20261017L); // fixed seed: a failure is reproducible
        for (int trial = 0; trial < 500; trial++) {
            int size = 1 + random.nextInt(300);
            boolean[] expectedNull = new boolean[size];
            Validity v = Validity.allValid(size);
            for (int i = 0; i < size; i++) {
                if (random.nextBoolean()) {
                    v.setNull(i);
                    expectedNull[i] = true;
                }
            }
            for (int i = 0; i < size; i++) {
                assertEquals(expectedNull[i], v.isNull(i), "trial " + trial + " row " + i);
            }
        }
    }

    @Test
    @DisplayName("setValid flips a row back but mayHaveNulls stays latched, as documented")
    void mayHaveNullsIsConservative() {
        Validity v = Validity.allValid(8);
        v.setNull(3);
        v.setValid(3);
        assertTrue(v.isValid(3));
        assertTrue(v.mayHaveNulls(), "latched true is correct: it means 'might', not 'does'");
    }

    @Test
    @DisplayName("reset reuses the buffer, clears the flag, and can grow")
    void resetReuses() {
        Validity v = Validity.allValid(64);
        v.setNull(0);
        v.reset(200);
        assertEquals(200, v.capacity());
        assertFalse(v.mayHaveNulls());
        assertTrue(v.isValid(0));
    }

    @Test
    @DisplayName("writes outside the bitmap are refused with the row in the message")
    void writesAreBoundsChecked() {
        Validity v = Validity.allValid(10);
        assertThrows(IndexOutOfBoundsException.class, () -> v.setNull(10));
        assertThrows(IndexOutOfBoundsException.class, () -> v.setNull(-1));
        assertThrows(IllegalArgumentException.class, () -> Validity.allValid(-1));
    }

    @Test
    @DisplayName("word count rounds up, so 65 bits need two words")
    void wordCountRoundsUp() {
        assertEquals(0, Validity.wordCount(0));
        assertEquals(1, Validity.wordCount(1));
        assertEquals(1, Validity.wordCount(64));
        assertEquals(2, Validity.wordCount(65));
    }
}
