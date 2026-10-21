package columnar.expr;

/** The binary operators the engine understands. */
public enum BinaryOp {
    ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/"),
    EQUAL("="), NOT_EQUAL("<>"), LESS("<"), LESS_OR_EQUAL("<="),
    GREATER(">"), GREATER_OR_EQUAL(">="),
    AND("AND"), OR("OR");

    private final String symbol;

    BinaryOp(String symbol) {
        this.symbol = symbol;
    }

    public boolean isArithmetic() {
        return this == ADD || this == SUBTRACT || this == MULTIPLY || this == DIVIDE;
    }

    public boolean isComparison() {
        return this == EQUAL || this == NOT_EQUAL || this == LESS
                || this == LESS_OR_EQUAL || this == GREATER || this == GREATER_OR_EQUAL;
    }

    public boolean isLogical() {
        return this == AND || this == OR;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
