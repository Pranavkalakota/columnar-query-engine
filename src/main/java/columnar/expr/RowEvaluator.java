package columnar.expr;

import columnar.exec.row.Row;
import columnar.types.DataType;
import columnar.types.Schema;
import java.util.IdentityHashMap;
import java.util.Map;

/** Interprets an expression one row at a time. */
public final class RowEvaluator {

    private record Column(int index, DataType type) {}
    private final Expr expr;
    private final DataType type;
    private final Map<Expr, DataType> nodeTypes = new IdentityHashMap<>();
    private final Map<Expr, Column> columns = new IdentityHashMap<>();

    public RowEvaluator(Expr expr, Schema schema) {
        this.expr = expr;
        this.type = expr.type(schema);
        resolve(expr, schema);
    }

    public DataType type() {
        return type;
    }

    private void resolve(Expr e, Schema schema) {
        nodeTypes.put(e, e.type(schema));
        switch (e) {
            case Expr.ColumnRef c -> {
                int index = schema.requireIndexOf(c.name());
                columns.put(c, new Column(index, schema.type(index)));
            }
            case Expr.Binary b -> {
                resolve(b.left(), schema);
                resolve(b.right(), schema);
            }
            case Expr.Literal ignored -> { }
        }
    }

    /** Evaluates against a row, returning null for SQL NULL. */
    public Object evaluate(Row row) {
        return eval(expr, row);
    }

    private Object eval(Expr e, Row row) {
        return switch (e) {
            case Expr.Literal l -> l.value();
            case Expr.ColumnRef c -> read(columns.get(c), row);
            case Expr.Binary b -> evalBinary(b, row);
        };
    }

    /** A DATE reads as its epoch day. */
    private static Object read(Column c, Row row) {
        if (row.isNull(c.index())) {
            return null;
        }
        return switch (c.type()) {
            case INT32, DATE -> row.getInt(c.index());
            case INT64 -> row.getLong(c.index());
            case DOUBLE -> row.getDouble(c.index());
            case BOOLEAN -> row.getBoolean(c.index());
            case VARCHAR -> row.getString(c.index());
        };
    }

    private Object evalBinary(Expr.Binary b, Row row) {
        if (b.op().isLogical()) {
            return evalLogical(b, row);
        }
        Object l = eval(b.left(), row);
        Object r = eval(b.right(), row);
        if (l == null || r == null) {
            return null;
        }
        return b.op().isComparison() ? compare(b.op(), l, r) : arithmetic(b.op(), nodeTypes.get(b), l, r);
    }

    /** Three-valued AND and OR. */
    private Object evalLogical(Expr.Binary b, Row row) {
        boolean and = b.op() == BinaryOp.AND;
        Object l = eval(b.left(), row);
        if (and ? Boolean.FALSE.equals(l) : Boolean.TRUE.equals(l)) {
            return !and;
        }
        Object r = eval(b.right(), row);
        if (and ? Boolean.FALSE.equals(r) : Boolean.TRUE.equals(r)) {
            return !and;
        }
        return (l == null || r == null) ? null : and;
    }

    private static Object compare(BinaryOp op, Object l, Object r) {
        int c;
        if (l instanceof Number ln && r instanceof Number rn) {
            c = (l instanceof Double || r instanceof Double)
                    ? Double.compare(ln.doubleValue(), rn.doubleValue())
                    : Long.compare(ln.longValue(), rn.longValue());
        } else if (l instanceof String ls && r instanceof String rs) {
            c = ls.compareTo(rs);
        } else if (l instanceof Boolean lb && r instanceof Boolean rb) {
            c = Boolean.compare(lb, rb);
        } else {
            throw new IllegalArgumentException("cannot compare " + l + " with " + r);
        }
        return switch (op) {
            case EQUAL -> c == 0;
            case NOT_EQUAL -> c != 0;
            case LESS -> c < 0;
            case LESS_OR_EQUAL -> c <= 0;
            case GREATER -> c > 0;
            default -> c >= 0;
        };
    }

    /** Division always lands in the double branch. */
    private static Object arithmetic(BinaryOp op, DataType type, Object l, Object r) {
        if (type == DataType.DOUBLE) {
            double a = ((Number) l).doubleValue();
            double b = ((Number) r).doubleValue();
            return switch (op) {
                case ADD -> a + b;
                case SUBTRACT -> a - b;
                case MULTIPLY -> a * b;
                default -> a / b;
            };
        }
        long a = ((Number) l).longValue();
        long b = ((Number) r).longValue();
        long value = switch (op) {
            case ADD -> a + b;
            case SUBTRACT -> a - b;
            default -> a * b;
        };
        return type == DataType.INT32 ? (Object) (int) value : (Object) value;
    }
}
