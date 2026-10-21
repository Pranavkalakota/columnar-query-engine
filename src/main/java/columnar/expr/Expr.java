package columnar.expr;

import columnar.types.DataType;
import columnar.types.Schema;
import java.time.LocalDate;

/** An expression tree. Immutable, and shared by every execution engine. */
public sealed interface Expr {

    /** The type this expression produces against a schema, or an error if it cannot be typed. */
    DataType type(Schema schema);

    static ColumnRef col(String name) {
        return new ColumnRef(name);
    }

    static Literal lit(long value) {
        return new Literal(value, DataType.INT64);
    }

    static Literal lit(double value) {
        return new Literal(value, DataType.DOUBLE);
    }

    static Literal lit(String value) {
        return new Literal(value, DataType.VARCHAR);
    }

    static Literal lit(boolean value) {
        return new Literal(value, DataType.BOOLEAN);
    }

    /** A DATE literal, stored as days since 1970-01-01 like every other date. */
    static Literal date(String isoDate) {
        return new Literal((int) LocalDate.parse(isoDate).toEpochDay(), DataType.DATE);
    }

    /** A NULL of a known type, which the three-valued logic needs to stay typed. */
    static Literal nul(DataType type) {
        return new Literal(null, type);
    }

    default Binary binary(BinaryOp op, Expr right) {
        return new Binary(op, this, right);
    }

    /** A reference to a column by name, resolved against the scan's schema. */
    record ColumnRef(String name) implements Expr {
        public ColumnRef {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a column reference needs a name");
            }
        }

        @Override
        public DataType type(Schema schema) {
            return schema.field(schema.requireIndexOf(name)).type();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A constant. A null value is a typed NULL, not an absence of type. */
    record Literal(Object value, DataType type) implements Expr {
        public Literal {
            if (type == null) {
                throw new IllegalArgumentException("a literal needs a type");
            }
        }

        @Override
        public DataType type(Schema schema) {
            return type;
        }

        @Override
        public String toString() {
            return value == null ? "NULL" : String.valueOf(value);
        }
    }

    /** Two operands and an operator. */
    record Binary(BinaryOp op, Expr left, Expr right) implements Expr {
        public Binary {
            if (op == null || left == null || right == null) {
                throw new IllegalArgumentException("a binary expression needs an operator and both sides");
            }
        }

        @Override
        public DataType type(Schema schema) {
            DataType l = left.type(schema);
            DataType r = right.type(schema);
            if (op.isLogical()) {
                require(l == DataType.BOOLEAN && r == DataType.BOOLEAN, op + " needs booleans, got " + l + " and " + r);
                return DataType.BOOLEAN;
            }
            if (op.isComparison()) {
                require(comparable(l, r), "cannot compare " + l + " with " + r);
                return DataType.BOOLEAN;
            }
            return op == BinaryOp.DIVIDE ? l.divideType(r) : DataType.widen(l, r);
        }

        /** Comparison allows any two numerics, or two values of the same type. */
        private static boolean comparable(DataType l, DataType r) {
            return l == r || (l.isNumeric() && r.isNumeric());
        }

        private static void require(boolean ok, String message) {
            if (!ok) {
                throw new IllegalArgumentException(message);
            }
        }

        @Override
        public String toString() {
            return "(" + left + " " + op + " " + right + ")";
        }
    }
}
