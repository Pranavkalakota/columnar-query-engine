package columnar.types;

/** The types this engine understands. */
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

    /** True for the types backed by an int[] or long[], which includes DATE. */
    public boolean isIntegral() {
        return this == INT32 || this == INT64 || this == DATE;
    }

    /** Types arithmetic accepts. DATE is deliberately not one. */
    public boolean isNumeric() {
        return this == INT32 || this == INT64 || this == DOUBLE;
    }

    /** False only for VARCHAR. */
    public boolean isFixedWidth() {
        return byteWidth != VARIABLE_WIDTH;
    }

    /** Bytes per value for a fixed-width type. */
    public int byteWidth() {
        if (!isFixedWidth()) {
            throw new UnsupportedOperationException(this + " has no fixed byte width");
        }
        return byteWidth;
    }

    /** The common type two operands promote to. Throws when there is none. */
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

    /** Result type of SUM. Integers widen to INT64. */
    public DataType sumType() {
        if (!isNumeric()) {
            throw new IllegalArgumentException("SUM is not defined for " + this);
        }
        return this == DOUBLE ? DOUBLE : INT64;
    }

    /** Division always yields DOUBLE. */
    public DataType divideType(DataType other) {
        if (!isNumeric() || !other.isNumeric()) {
            throw new IllegalArgumentException("division is not defined for " + this + " and " + other);
        }
        return DOUBLE;
    }

    /** AVG is always DOUBLE, even over integers. */
    public DataType avgType() {
        if (!isNumeric()) {
            throw new IllegalArgumentException("AVG is not defined for " + this);
        }
        return DOUBLE;
    }
}
