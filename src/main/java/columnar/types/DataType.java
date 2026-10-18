package columnar.types;

/**
 * The types this engine understands.
 *
 * <p>An enum rather than a sealed interface, deliberately. Every v1 type is
 * unparameterized, so there is nothing for a record to carry, and an enum gives
 * exhaustive {@code switch} for free. Adding {@code DECIMAL(precision, scale)}
 * would need parameters and force a sealed interface; that is the later price.
 *
 * <p>{@link #DATE} is days since 1970-01-01 in an {@code int}, negative before
 * 1970, so it shares {@code IntVector} with {@link #INT32}. It is integral but
 * deliberately not numeric: comparing two dates is meaningful, multiplying them
 * is not, and excluding it from promotion makes the binder reject that.
 */
public enum DataType {

    INT32(4),
    INT64(8),
    DOUBLE(8),
    BOOLEAN(1),
    DATE(4),
    VARCHAR(-1);

    /** Width marker for a type whose values have no fixed size. */
    private static final int VARIABLE_WIDTH = -1;

    private final int byteWidth;

    DataType(int byteWidth) {
        this.byteWidth = byteWidth;
    }

    /** True for the types backed by an {@code int[]} or {@code long[]}, which includes DATE. */
    public boolean isIntegral() {
        return this == INT32 || this == INT64 || this == DATE;
    }

    /** True for the types arithmetic accepts. DATE is excluded on purpose; see the class note. */
    public boolean isNumeric() {
        return this == INT32 || this == INT64 || this == DOUBLE;
    }

    /** False only for VARCHAR, whose values have no fixed size on disk or in memory. */
    public boolean isFixedWidth() {
        return byteWidth != VARIABLE_WIDTH;
    }

    /**
     * Bytes per value for a fixed-width type.
     *
     * <p>Throws for VARCHAR rather than returning a sentinel, because a
     * sentinel would flow into a size calculation and produce a plausible
     * wrong number instead of a stack trace. Callers check
     * {@link #isFixedWidth()} first.
     */
    public int byteWidth() {
        if (!isFixedWidth()) {
            throw new UnsupportedOperationException(this + " has no fixed byte width");
        }
        return byteWidth;
    }

    /**
     * The common type two operands are promoted to, or an exception when there
     * is none.
     *
     * <p>The rules: a type with itself is unchanged, INT32 with INT64 widens to
     * INT64, and any integer with DOUBLE widens to DOUBLE. Everything else is
     * an error, which is what keeps {@code date * boolean} from reaching a
     * kernel that has no idea what to do with it.
     */
    public static DataType widen(DataType left, DataType right) {
        if (left == right) {
            return left;
        }
        if (left.isNumeric() && right.isNumeric()) {
            if (left == DOUBLE || right == DOUBLE) {
                return DOUBLE;
            }
            return INT64;
        }
        throw new IllegalArgumentException("no common type for " + left + " and " + right);
    }

    /**
     * The result type of {@code SUM} over this type.
     *
     * <p>{@code SUM(INT32)} returns INT64 because summing a few million ints
     * overflows an int long before it overflows the answer. That is a
     * correctness rule, not an optimization.
     *
     * <p><b>Known divergence from the oracle.</b> DuckDB returns {@code SUM}
     * over any integral type as HUGEINT, which this engine has no type for, so
     * {@code SUM(INT64)} is INT64 here. The two agree for every sum that fits
     * in a long; past that DuckDB is right and this overflows.
     */
    public DataType sumType() {
        if (!isNumeric()) {
            throw new IllegalArgumentException("SUM is not defined for " + this);
        }
        return this == DOUBLE ? DOUBLE : INT64;
    }

    /**
     * The result type of division, which is always DOUBLE.
     *
     * <p>Checked against the oracle, not assumed: DuckDB evaluates {@code 1/2}
     * as {@code 0.5} rather than as integer division, and {@code 1/0} as
     * {@code Infinity} rather than an error. Both are pinned by a test.
     */
    public DataType divideType(DataType other) {
        if (!isNumeric() || !other.isNumeric()) {
            throw new IllegalArgumentException("division is not defined for " + this + " and " + other);
        }
        return DOUBLE;
    }

    /** {@code AVG} is always DOUBLE, even over integers. */
    public DataType avgType() {
        if (!isNumeric()) {
            throw new IllegalArgumentException("AVG is not defined for " + this);
        }
        return DOUBLE;
    }
}
