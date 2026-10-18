package columnar.differential;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DuckDB, running in process, used as the correctness oracle.
 *
 * <p>Every phase of this engine is judged by "does it agree with DuckDB on
 * this query", so the oracle is the first thing built and the last thing
 * trusted blindly. It is a test-scope dependency on purpose: it must never be
 * reachable from the engine's runtime path, or the benchmark would be
 * measuring DuckDB.
 *
 * <p>Failures surface as an unchecked {@link OracleException}. That is a
 * deliberate choice rather than laziness about {@link SQLException}: the
 * equivalence matrix runs the same query across engines, batch sizes, thread
 * counts and rule toggles inside nested lambdas, and a checked exception makes
 * that code unreadable. A failing oracle is a broken test setup, not a
 * condition any caller can recover from.
 */
public final class DuckDbOracle implements AutoCloseable {

    private final Connection conn;

    private DuckDbOracle(Connection conn) {
        this.conn = conn;
    }

    /** Opens a private in-memory database. Nothing is shared between instances. */
    public static DuckDbOracle inMemory() {
        return open("jdbc:duckdb:");
    }

    /** Opens a database backed by a file, so generated data survives the JVM. */
    public static DuckDbOracle onDisk(Path dbFile) {
        return open("jdbc:duckdb:" + dbFile.toAbsolutePath());
    }

    private static DuckDbOracle open(String url) {
        try {
            return new DuckDbOracle(DriverManager.getConnection(url));
        } catch (SQLException e) {
            throw new OracleException("could not open " + url, e);
        }
    }

    /** Runs a query and materializes the whole result, normalized for comparison. */
    public ResultTable query(String sql) {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return ResultTable.of(rs);
        } catch (SQLException e) {
            throw new OracleException("query failed: " + sql, e);
        }
    }

    /** Runs a statement that returns no result set (DDL, COPY, CALL). */
    public void execute(String sql) {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new OracleException("execute failed: " + sql, e);
        }
    }

    /**
     * Row count straight from the oracle. Tests compare against this rather
     * than against a hard-coded constant, so that changing the scale factor
     * cannot leave a stale number asserting the wrong thing.
     */
    public long count(String table) {
        ResultTable t = query("SELECT count(*) FROM " + table);
        return (Long) t.rows().get(0).get(0);
    }

    /**
     * Exposes a Parquet file under a table name as a view, not a table, so the
     * bytes are read lazily. At SF1 copying lineitem into memory would cost
     * more than every query run against it.
     */
    public void registerParquet(String table, Path file) {
        execute("CREATE OR REPLACE VIEW %s AS SELECT * FROM read_parquet('%s')"
                .formatted(table, quote(file)));
    }

    /** Loads a DuckDB extension, downloading it on first use. */
    public void loadExtension(String name) {
        execute("INSTALL " + name);
        execute("LOAD " + name);
    }

    /** For callers that need raw JDBC, such as the TPC-H generator. */
    public Connection connection() {
        return conn;
    }

    /** Escapes a path for a SQL single-quoted string literal. */
    public static String quote(Path p) {
        return p.toAbsolutePath().toString().replace("'", "''");
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new OracleException("close failed", e);
        }
    }

    public static final class OracleException extends RuntimeException {
        OracleException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
