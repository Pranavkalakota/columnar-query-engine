package columnar.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import columnar.types.DataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the SQL semantics this engine has to reproduce, by asking the oracle
 * instead of assuming.
 *
 * <p>These are not tests of DuckDB. They are a written record of the behaviour
 * {@link DataType} and the expression engine are built to match, placed where a
 * DuckDB upgrade that changes one of them fails loudly rather than quietly
 * turning every later differential test red for reasons nobody can find.
 */
class TypeSemanticsOracleTest {

    private DuckDbOracle oracle;

    @BeforeEach
    void setUp() {
        oracle = DuckDbOracle.inMemory();
    }

    @AfterEach
    void tearDown() {
        oracle.close();
    }

    private String typeOf(String expression) {
        return (String) oracle.query("SELECT typeof(" + expression + ")").rows().get(0).get(0);
    }

    private Object value(String expression) {
        return oracle.query("SELECT " + expression).rows().get(0).get(0);
    }

    @Test
    @DisplayName("arithmetic promotion matches DataType.widen")
    void promotionMatchesWiden() {
        assertEquals("INTEGER", typeOf("CAST(1 AS INTEGER) + CAST(1 AS INTEGER)"));
        assertEquals("BIGINT", typeOf("CAST(1 AS INTEGER) + CAST(1 AS BIGINT)"));
        assertEquals("DOUBLE", typeOf("CAST(1 AS INTEGER) + CAST(1 AS DOUBLE)"));

        assertEquals(DataType.INT32, DataType.widen(DataType.INT32, DataType.INT32));
        assertEquals(DataType.INT64, DataType.widen(DataType.INT32, DataType.INT64));
        assertEquals(DataType.DOUBLE, DataType.widen(DataType.INT32, DataType.DOUBLE));
    }

    @Test
    @DisplayName("division is DOUBLE, so 1/2 is 0.5 and 1/0 is Infinity rather than an error")
    void divisionIsAlwaysDouble() {
        assertEquals("DOUBLE", typeOf("CAST(1 AS INTEGER) / CAST(2 AS INTEGER)"));
        assertEquals(0.5, value("CAST(1 AS INTEGER) / CAST(2 AS INTEGER)"));
        assertEquals(Double.POSITIVE_INFINITY, value("CAST(1 AS INTEGER) / CAST(0 AS INTEGER)"));
        assertEquals(DataType.DOUBLE, DataType.INT32.divideType(DataType.INT32));
    }

    @Test
    @DisplayName("SUM over integers is HUGEINT in the oracle, which this engine answers as INT64")
    void sumOverIntegersIsHugeIntInTheOracle() {
        assertEquals("HUGEINT", typeOf("(SELECT SUM(i) FROM (SELECT CAST(1 AS INTEGER) i) t)"));
        assertEquals("HUGEINT", typeOf("(SELECT SUM(i) FROM (SELECT CAST(1 AS BIGINT) i) t)"));
        // The engine has no 128-bit type. Normalization collapses HUGEINT to a
        // long when it fits, so the two agree on every sum that fits.
        assertEquals(DataType.INT64, DataType.INT32.sumType());
        assertEquals(3L, value("(SELECT SUM(i) FROM (SELECT CAST(3 AS INTEGER) i) t)"));
    }

    @Test
    @DisplayName("AVG is DOUBLE over integers and COUNT is BIGINT")
    void avgAndCountTypes() {
        assertEquals("DOUBLE", typeOf("(SELECT AVG(i) FROM (SELECT CAST(1 AS INTEGER) i) t)"));
        assertEquals("BIGINT", typeOf("(SELECT COUNT(*) FROM range(1))"));
        assertEquals(DataType.DOUBLE, DataType.INT32.avgType());
    }

    @Test
    @DisplayName("SUM of no non-null rows is NULL while COUNT is 0")
    void emptyAggregates() {
        assertNull(value("(SELECT SUM(i) FROM (SELECT CAST(NULL AS INTEGER) i) t)"));
        assertEquals(0L, value("(SELECT COUNT(i) FROM (SELECT CAST(NULL AS INTEGER) i) t)"));
        assertNull(value("(SELECT SUM(i) FROM (SELECT 1 i) t WHERE false)"));
        assertEquals(0L, value("(SELECT COUNT(*) FROM (SELECT 1 i) t WHERE false)"));
    }

    @Test
    @DisplayName("three-valued logic: NULL AND FALSE is FALSE, NULL AND TRUE is NULL")
    void threeValuedLogic() {
        assertEquals(false, value("NULL AND false"));
        assertNull(value("NULL AND true"));
        assertEquals(true, value("NULL OR true"));
        assertNull(value("NULL OR false"));
        assertNull(value("NOT NULL"));
        assertNull(value("NULL = NULL"));
    }

    @Test
    @DisplayName("a date is not a number, so arithmetic on it is rejected on both sides")
    void datesAreNotNumbers() {
        assertEquals("DATE", typeOf("DATE '2024-01-01'"));
        assertThrows(DuckDbOracle.OracleException.class, () -> value("DATE '2024-01-01' * 2"));
        assertThrows(IllegalArgumentException.class, DataType.DATE::sumType);
    }
}
