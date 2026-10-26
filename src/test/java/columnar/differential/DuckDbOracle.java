package columnar.differential;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** DuckDB, running in process, used as the correctness oracle. */
public final class DuckDbOracle implements AutoCloseable {

    private final Connection conn;

    private DuckDbOracle(Connection conn) {
        this.conn = conn;
    }

    /** Opens a private in-memory database. Nothing is shared between instances. */
    public static DuckDbOracle inMemory() {
        return open("jdbc:duckdb:");
    }

    /** Opens a database backed by a file. */
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

    /** Runs a query and normalizes the result. */
    public ResultTable query(String sql) {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return ResultTable.of(rs);
        } catch (SQLException e) {
            throw new OracleException("query failed: " + sql, e);
        }
    }

    /** Runs a statement with no result set. */
    public void execute(String sql) {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new OracleException("execute failed: " + sql, e);
        }
    }

    /** Row count straight from the oracle. */
    public long count(String table) {
        ResultTable t = query("SELECT count(*) FROM " + table);
        return (Long) t.rows().get(0).get(0);
    }

    /** Registers a Parquet file as a view. */
    public void registerParquet(String table, Path file) {
        execute("CREATE OR REPLACE VIEW %s AS SELECT * FROM read_parquet('%s')"
                .formatted(table, quote(file)));
    }

    /** Loads a DuckDB extension, downloading it on first use. */
    public void loadExtension(String name) {
        execute("INSTALL " + name);
        execute("LOAD " + name);
    }

    /** Raw JDBC, for the TPC-H generator. */
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
