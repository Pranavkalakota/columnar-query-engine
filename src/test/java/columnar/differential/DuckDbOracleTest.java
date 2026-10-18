package columnar.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuckDbOracleTest {

    private DuckDbOracle oracle;

    @BeforeEach
    void setUp() {
        oracle = DuckDbOracle.inMemory();
    }

    @AfterEach
    void tearDown() {
        oracle.close();
    }

    @Test
    @DisplayName("a query comes back normalized, so INT32 is a Long and DECIMAL is a Double")
    void queryIsNormalized() {
        ResultTable t = oracle.query(
                "SELECT CAST(1 AS INTEGER) AS i, CAST(2.5 AS DECIMAL(5,2)) AS d, 'x' AS s");
        assertEquals(List.of(1L, 2.5d, "x"), t.rows().get(0));
        assertEquals(List.of("i", "d", "s"), t.columnNames());
    }

    @Test
    @DisplayName("NULL survives as null rather than as a zero")
    void nullsSurvive() {
        ResultTable t = oracle.query("SELECT CAST(NULL AS INTEGER) AS i, 0 AS z");
        assertNull(t.rows().get(0).get(0));
        assertEquals(0L, t.rows().get(0).get(1));
    }

    @Test
    @DisplayName("count comes from the oracle, not from a hard-coded number")
    void countsRows() {
        oracle.execute("CREATE TABLE t AS SELECT * FROM range(7)");
        assertEquals(7L, oracle.count("t"));
    }

    @Test
    @DisplayName("bad SQL fails loudly with the statement in the message")
    void badSqlThrows() {
        DuckDbOracle.OracleException e =
                assertThrows(DuckDbOracle.OracleException.class, () -> oracle.query("SELECT * FROM nope"));
        assertTrue(e.getMessage().contains("SELECT * FROM nope"));
    }

    @Test
    @DisplayName("a Parquet file registers as a view and reads back identically")
    void registersParquet(@TempDir Path dir) {
        Path file = dir.resolve("nums.parquet");
        oracle.execute("COPY (SELECT i, i * 1.5 AS half FROM range(4) t(i)) TO '%s' (FORMAT PARQUET)"
                .formatted(DuckDbOracle.quote(file)));
        oracle.registerParquet("nums", file);
        assertEquals(4L, oracle.count("nums"));
        assertEquals(List.of(2L, 3.0d), oracle.query("SELECT * FROM nums WHERE i = 2").rows().get(0));
    }

    @Test
    @DisplayName("a quote in a path is escaped rather than ending the literal")
    void quoteEscapesSingleQuotes() {
        assertTrue(DuckDbOracle.quote(Path.of("/tmp/it's/x.parquet")).contains("it''s"));
    }
}
