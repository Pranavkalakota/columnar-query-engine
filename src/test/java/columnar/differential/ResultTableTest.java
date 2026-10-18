package columnar.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResultTableTest {

    @Test
    @DisplayName("every integral width collapses to Long")
    void integralsCollapseToLong() {
        assertEquals(7L, ResultTable.normalize((byte) 7));
        assertEquals(7L, ResultTable.normalize((short) 7));
        assertEquals(7L, ResultTable.normalize(7));
        assertEquals(7L, ResultTable.normalize(7L));
    }

    @Test
    @DisplayName("float widens to double, double is left alone")
    void approximatesCollapseToDouble() {
        assertEquals(0.5, ResultTable.normalize(0.5f));
        assertEquals(0.5, ResultTable.normalize(0.5d));
    }

    @Test
    @DisplayName("an exact-integer BigDecimal stays integral instead of losing mantissa bits")
    void exactDecimalStaysLong() {
        // 2^62 + 1 is not representable as a double. If this normalized to
        // Double the comparator would call it equal to 2^62, which would hide
        // a real SUM(INT64) bug.
        BigDecimal big = new BigDecimal("4611686018427387905");
        assertEquals(4611686018427387905L, ResultTable.normalize(big));
    }

    @Test
    @DisplayName("BigInteger collapses to Long, because DuckDB SUM returns HUGEINT")
    void bigIntegerCollapsesToLong() {
        assertEquals(3L, ResultTable.normalize(BigInteger.valueOf(3)));
        assertEquals(Long.MAX_VALUE, ResultTable.normalize(BigInteger.valueOf(Long.MAX_VALUE)));
        // Beyond a long there is nothing exact left to preserve.
        assertInstanceOf(Double.class,
                ResultTable.normalize(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN)));
    }

    @Test
    @DisplayName("a fractional BigDecimal becomes a double")
    void fractionalDecimalBecomesDouble() {
        assertEquals(12.34, ResultTable.normalize(new BigDecimal("12.34")));
    }

    @Test
    @DisplayName("a BigDecimal too large for a long falls back to double")
    void hugeDecimalFallsBackToDouble() {
        Object v = ResultTable.normalize(new BigDecimal("99999999999999999999999999"));
        assertInstanceOf(Double.class, v);
    }

    @Test
    @DisplayName("sql.Date becomes LocalDate; pre-1970 survives")
    void datesBecomeLocalDate() {
        assertEquals(LocalDate.of(1969, 7, 20),
                ResultTable.normalize(java.sql.Date.valueOf(LocalDate.of(1969, 7, 20))));
        assertEquals(LocalDate.of(1970, 1, 1), ResultTable.normalize(LocalDate.of(1970, 1, 1)));
    }

    @Test
    @DisplayName("null stays null and strings and booleans pass through")
    void passThrough() {
        assertNull(ResultTable.normalize(null));
        assertEquals("PROMO", ResultTable.normalize("PROMO"));
        assertEquals(Boolean.TRUE, ResultTable.normalize(true));
    }

    @Test
    @DisplayName("the list factory normalizes every cell")
    void factoryNormalizesCells() {
        ResultTable t = ResultTable.of(List.of("a", "b"),
                List.of(List.of(1, 2.5f), List.of(3L, new BigDecimal("4"))));
        assertEquals(List.of(1L, 2.5d), t.rows().get(0));
        assertEquals(List.of(3L, 4L), t.rows().get(1));
        assertEquals(2, t.columnCount());
        assertEquals(2, t.rowCount());
    }
}
