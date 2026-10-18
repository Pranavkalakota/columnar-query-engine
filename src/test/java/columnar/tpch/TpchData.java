package columnar.tpch;

import columnar.differential.DuckDbOracle;
import columnar.differential.ResultTable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates TPC-H data with DuckDB's {@code tpch} extension and writes it out
 * as Parquet and as pipe-delimited {@code .tbl}, which are the two sources the
 * engine has to read.
 *
 * <p><b>DECIMAL is cast to DOUBLE on the way out.</b> TPC-H's money and
 * quantity columns are {@code DECIMAL(15,2)} and v1 of this engine has no
 * DECIMAL type. If the engine read doubles while the oracle read decimals,
 * every comparison would carry a representation difference that says nothing
 * about correctness. The cost is that double addition is not associative, so
 * sums depend on evaluation order, which is why comparison uses a relative
 * tolerance.
 *
 * <p><b>The cast list is discovered, not hand-written.</b> Enumerating the
 * decimal columns by hand is a list that silently rots the first time DuckDB's
 * TPC-H schema changes, and the failure would look like an engine bug.
 */
public final class TpchData {

    /** The eight TPC-H tables, as DuckDB's dbgen names them. */
    public static final List<String> TABLES = List.of(
            "customer", "lineitem", "nation", "orders", "part", "partsupp", "region", "supplier");

    private TpchData() {
    }

    /**
     * Generates one scale factor into {@code outDir}, returning each table's
     * row count as reported by DuckDB.
     *
     * <p>The counts are returned rather than written down in a constant
     * somewhere: later tests assert their own row counts against these, and a
     * hard-coded number would keep asserting the old scale factor's answer
     * after someone changed the scale factor.
     */
    public static Map<String, Long> generate(double scaleFactor, Path outDir) {
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create " + outDir, e);
        }
        Map<String, Long> rowCounts = new LinkedHashMap<>();
        try (DuckDbOracle oracle = DuckDbOracle.inMemory()) {
            oracle.loadExtension("tpch");
            oracle.execute("CALL dbgen(sf=" + plain(scaleFactor) + ")");
            for (String table : TABLES) {
                String projection = castProjection(oracle, table);
                writeParquet(oracle, table, projection, outDir.resolve(table + ".parquet"));
                writeTbl(oracle, table, projection, outDir.resolve(table + ".tbl"));
                rowCounts.put(table, oracle.count(table));
            }
        }
        return rowCounts;
    }

    private static void writeParquet(DuckDbOracle oracle, String table, String projection, Path out) {
        oracle.execute("COPY (SELECT %s FROM %s) TO '%s' (FORMAT PARQUET)"
                .formatted(projection, table, DuckDbOracle.quote(out)));
    }

    /**
     * Real dbgen puts a trailing delimiter at the end of every line, so a
     * 16-column row ends in {@code |}. Appending an empty column reproduces
     * that exactly. It would be easier to emit files without it, but then the
     * loader would never be tested against the format it actually has to read.
     */
    private static void writeTbl(DuckDbOracle oracle, String table, String projection, Path out) {
        oracle.execute("COPY (SELECT %s, '' AS trailing_delimiter FROM %s) TO '%s'"
                .formatted(projection, table, DuckDbOracle.quote(out))
                + " (FORMAT CSV, DELIMITER '|', HEADER false, QUOTE '')");
    }

    /** Builds a select list that casts every DECIMAL column to DOUBLE and leaves the rest alone. */
    static String castProjection(DuckDbOracle oracle, String table) {
        ResultTable columns = oracle.query(
                "SELECT column_name, data_type FROM information_schema.columns"
                        + " WHERE table_name = '" + table + "' ORDER BY ordinal_position");
        if (columns.rowCount() == 0) {
            throw new IllegalStateException("no such table in the oracle: " + table);
        }
        StringBuilder sb = new StringBuilder();
        for (List<Object> column : columns.rows()) {
            String name = (String) column.get(0);
            String type = (String) column.get(1);
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(type.startsWith("DECIMAL")
                    ? "CAST(%s AS DOUBLE) AS %s".formatted(name, name)
                    : name);
        }
        return sb.toString();
    }

    /** Formats a scale factor without an exponent, which dbgen will not parse. */
    static String plain(double scaleFactor) {
        return BigDecimal.valueOf(scaleFactor).stripTrailingZeros().toPlainString();
    }

    /** Entry point for benchmarks/scripts/gen_tpch.sh. */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: TpchData <scaleFactor> <outputDir>");
            System.exit(2);
        }
        double sf = Double.parseDouble(args[0]);
        Map<String, Long> counts = generate(sf, Path.of(args[1]));
        System.out.println("scale factor " + plain(sf) + " written to " + args[1]);
        counts.forEach((table, rows) -> System.out.printf("  %-10s %,12d rows%n", table, rows));
    }
}
