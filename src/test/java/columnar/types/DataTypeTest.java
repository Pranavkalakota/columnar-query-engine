package columnar.types;

import static columnar.types.DataType.BOOLEAN;
import static columnar.types.DataType.DATE;
import static columnar.types.DataType.DOUBLE;
import static columnar.types.DataType.INT32;
import static columnar.types.DataType.INT64;
import static columnar.types.DataType.VARCHAR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataTypeTest {

    @Test
    @DisplayName("a type widened with itself is unchanged")
    void widenIdentity() {
        for (DataType t : DataType.values()) {
            assertEquals(t, DataType.widen(t, t), t.toString());
        }
    }

    @Test
    @DisplayName("INT32 with INT64 widens to INT64, and any integer with DOUBLE to DOUBLE")
    void widenNumeric() {
        assertEquals(INT64, DataType.widen(INT32, INT64));
        assertEquals(INT64, DataType.widen(INT64, INT32));
        assertEquals(DOUBLE, DataType.widen(INT32, DOUBLE));
        assertEquals(DOUBLE, DataType.widen(DOUBLE, INT64));
    }

    @Test
    @DisplayName("there is no common type across non-numeric kinds, so widening fails loudly")
    void widenRejectsIncompatible() {
        assertThrows(IllegalArgumentException.class, () -> DataType.widen(DATE, INT32));
        assertThrows(IllegalArgumentException.class, () -> DataType.widen(BOOLEAN, INT32));
        assertThrows(IllegalArgumentException.class, () -> DataType.widen(VARCHAR, DOUBLE));
    }

    @Test
    @DisplayName("DATE is integral but not numeric, so it cannot reach arithmetic")
    void dateIsIntegralButNotNumeric() {
        assertTrue(DATE.isIntegral());
        assertFalse(DATE.isNumeric());
        assertThrows(IllegalArgumentException.class, DATE::sumType);
    }

    @Test
    @DisplayName("only VARCHAR has no fixed width, and asking for one throws")
    void byteWidths() {
        assertEquals(4, INT32.byteWidth());
        assertEquals(8, INT64.byteWidth());
        assertEquals(8, DOUBLE.byteWidth());
        assertEquals(1, BOOLEAN.byteWidth());
        assertEquals(4, DATE.byteWidth());
        assertFalse(VARCHAR.isFixedWidth());
        assertThrows(UnsupportedOperationException.class, VARCHAR::byteWidth);
    }

    @Test
    @DisplayName("SUM widens integers to INT64; AVG and division are always DOUBLE")
    void aggregateAndDivisionTypes() {
        assertEquals(INT64, INT32.sumType());
        assertEquals(INT64, INT64.sumType());
        assertEquals(DOUBLE, DOUBLE.sumType());
        assertEquals(DOUBLE, INT32.avgType());
        assertEquals(DOUBLE, INT32.divideType(INT32));
        assertThrows(IllegalArgumentException.class, () -> VARCHAR.divideType(INT32));
    }
}
