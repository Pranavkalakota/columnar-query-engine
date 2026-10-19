package columnar.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.types.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DoubleVectorTest {

    @Test
    @DisplayName("NaN and both infinities are stored and read back unchanged")
    void storesNonFiniteValues() {
        DoubleVector v = new DoubleVector(4);
        v.set(0, Double.NaN);
        v.set(1, Double.POSITIVE_INFINITY);
        v.set(2, Double.NEGATIVE_INFINITY);
        v.setNull(3);
        assertTrue(Double.isNaN(v.get(0)));
        assertEquals(Double.POSITIVE_INFINITY, v.get(1));
        assertEquals(Double.NEGATIVE_INFINITY, v.get(2));
        assertNull(v.valueAt(3));
        assertEquals(DataType.DOUBLE, v.type());
    }

    @Test
    @DisplayName("negative zero keeps its bit pattern, even though it equals positive zero")
    void negativeZeroIsPreserved() {
        DoubleVector v = new DoubleVector(2);
        v.set(0, -0.0);
        v.set(1, 0.0);
        assertTrue(v.get(0) == v.get(1), "-0.0 == 0.0 is true in IEEE 754");
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(v.get(0)),
                "but the stored bits must still be those of -0.0");
        assertEquals(0L, Double.doubleToRawLongBits(v.get(1)));
    }

    @Test
    @DisplayName("a NaN is not null, and a null is not a NaN")
    void nanAndNullAreDifferent() {
        DoubleVector v = new DoubleVector(2);
        v.set(0, Double.NaN);
        v.setNull(1);
        assertFalse(v.isNull(0), "NaN is a value");
        assertTrue(Double.isNaN((Double) v.valueAt(0)));
        assertTrue(v.isNull(1));
        assertNull(v.valueAt(1));
    }

    @Test
    @DisplayName("reset clears nulls and can grow")
    void resetReuses() {
        DoubleVector v = new DoubleVector(4);
        v.setNull(0);
        v.reset(8);
        assertFalse(v.mayHaveNulls());
        v.set(7, 1.5);
        assertEquals(1.5, v.get(7));
    }
}
