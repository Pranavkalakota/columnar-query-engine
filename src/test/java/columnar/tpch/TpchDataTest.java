package columnar.tpch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.differential.DuckDbOracle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TpchDataTest {

    @Test
    @DisplayName("a scale factor formats without an exponent, which dbgen cannot parse")
    void scaleFactorIsPlain() {
        assertEquals("0.01", TpchData.plain(0.01));
        assertEquals("0.1", TpchData.plain(0.1));
        assertEquals("1", TpchData.plain(1.0));
        assertFalse(TpchData.plain(0.001).contains("E"));
    }

    @Test
    @DisplayName("the projection casts DECIMAL columns and leaves every other type alone")
    void projectionCastsOnlyDecimals() {
        try (DuckDbOracle oracle = DuckDbOracle.inMemory()) {
            oracle.execute("CREATE TABLE t (a INTEGER, b DECIMAL(15,2), c VARCHAR, d DATE)");
            assertEquals("a, CAST(b AS DOUBLE) AS b, c, d", TpchData.castProjection(oracle, "t"));
        }
    }

    @Test
    @DisplayName("generation writes all eight tables as both Parquet and .tbl")
    void generatesEveryTableInBothFormats(@TempDir Path dir) throws IOException {
        Map<String, Long> counts = TpchData.generate(0.01, dir);
        assertEquals(TpchData.TABLES.size(), counts.size());
        for (String table : TpchData.TABLES) {
            assertTrue(Files.size(dir.resolve(table + ".parquet")) > 0, table + ".parquet");
            assertTrue(Files.size(dir.resolve(table + ".tbl")) > 0, table + ".tbl");
            assertTrue(counts.get(table) > 0, table + " row count");
        }
        // nation and region are fixed by the spec at every scale factor.
        assertEquals(25L, counts.get("nation"));
        assertEquals(5L, counts.get("region"));
    }

    @Test
    @DisplayName("every .tbl line ends with a trailing delimiter, as real dbgen emits")
    void tblLinesCarryTrailingDelimiter(@TempDir Path dir) throws IOException {
        TpchData.generate(0.01, dir);
        try (Stream<String> lines = Files.lines(dir.resolve("region.tbl"))) {
            List<String> all = lines.toList();
            assertEquals(5, all.size());
            assertTrue(all.stream().allMatch(l -> l.endsWith("|")), all.toString());
        }
    }

    @Test
    @DisplayName("money columns land on disk as DOUBLE, not DECIMAL")
    void decimalsBecomeDoublesOnDisk(@TempDir Path dir) {
        TpchData.generate(0.01, dir);
        try (DuckDbOracle oracle = DuckDbOracle.inMemory()) {
            oracle.registerParquet("lineitem", dir.resolve("lineitem.parquet"));
            List<Object> types = oracle.query(
                    "SELECT column_type FROM (DESCRIBE SELECT * FROM lineitem)"
                            + " WHERE column_name = 'l_extendedprice'").rows().get(0);
            assertEquals("DOUBLE", types.get(0));
        }
    }
}
