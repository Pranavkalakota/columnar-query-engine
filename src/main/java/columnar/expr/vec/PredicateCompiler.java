package columnar.expr.vec;

import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.types.DataType;
import columnar.types.Schema;
import columnar.vector.DoubleVector;
import columnar.vector.IntVector;
import columnar.vector.Validity;
import columnar.vector.VectorBatch;
import java.util.ArrayList;
import java.util.List;

/** Compiles a conjunction into a chain of kernels. */
public final class PredicateCompiler {

    private PredicateCompiler() {
    }

    /** Splits on AND; each conjunct must read column against literal. */
    public static List<BatchPredicate> compile(Expr predicate, Schema schema) {
        DataType type = predicate.type(schema);
        if (type != DataType.BOOLEAN) {
            throw new IllegalArgumentException("a filter needs a boolean predicate, got " + type);
        }
        List<Expr> conjuncts = new ArrayList<>();
        flatten(predicate, conjuncts);
        List<BatchPredicate> chain = new ArrayList<>(conjuncts.size());
        for (Expr conjunct : conjuncts) {
            chain.add(kernel(conjunct, schema));
        }
        return List.copyOf(chain);
    }

    /** True when every conjunct has a kernel behind it. */
    public static boolean supports(Expr predicate, Schema schema) {
        try {
            compile(predicate, schema);
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    private static void flatten(Expr expr, List<Expr> out) {
        if (expr instanceof Expr.Binary binary && binary.op() == BinaryOp.AND) {
            flatten(binary.left(), out);
            flatten(binary.right(), out);
            return;
        }
        out.add(expr);
    }

    private static BatchPredicate kernel(Expr expr, Schema schema) {
        if (!(expr instanceof Expr.Binary binary) || !binary.op().isComparison()) {
            throw new UnsupportedOperationException("not a comparison: " + expr);
        }
        BinaryOp op = binary.op();
        Expr left = binary.left();
        Expr right = binary.right();
        if (left instanceof Expr.Literal && right instanceof Expr.ColumnRef) {
            Expr swap = left;
            left = right;
            right = swap;
            op = mirror(op);
        }
        if (!(left instanceof Expr.ColumnRef ref) || !(right instanceof Expr.Literal literal)) {
            throw new UnsupportedOperationException("expected column against literal: " + expr);
        }
        if (literal.value() == null) {
            throw new UnsupportedOperationException("a NULL literal has no kernel: " + expr);
        }
        int index = schema.requireIndexOf(ref.name());
        return switch (schema.type(index)) {
            case DOUBLE -> new DoubleKernel(index, op, ((Number) literal.value()).doubleValue());
            case INT32, DATE -> new IntKernel(index, op, narrow(literal.value(), expr));
            default -> throw new UnsupportedOperationException("no kernel for " + schema.type(index));
        };
    }

    /** The same test with the operands exchanged. */
    private static BinaryOp mirror(BinaryOp op) {
        return switch (op) {
            case LESS -> BinaryOp.GREATER;
            case LESS_OR_EQUAL -> BinaryOp.GREATER_OR_EQUAL;
            case GREATER -> BinaryOp.LESS;
            case GREATER_OR_EQUAL -> BinaryOp.LESS_OR_EQUAL;
            default -> op;
        };
    }

    /** Refuses a literal an int column cannot hold exactly. */
    private static int narrow(Object value, Expr expr) {
        if (value instanceof Double d && d != Math.rint(d)) {
            throw new UnsupportedOperationException("fractional literal on an int column: " + expr);
        }
        long exact = ((Number) value).longValue();
        if (exact != (int) exact) {
            throw new UnsupportedOperationException("literal outside int range: " + expr);
        }
        return (int) exact;
    }

    private static void requireOp(BinaryOp op, BinaryOp... supported) {
        for (BinaryOp candidate : supported) {
            if (op == candidate) {
                return;
            }
        }
        throw new UnsupportedOperationException("no kernel for " + op);
    }

    /** A comparison on a double column. */
    private record DoubleKernel(int index, BinaryOp op, double constant) implements BatchPredicate {

        DoubleKernel {
            requireOp(op, BinaryOp.LESS, BinaryOp.LESS_OR_EQUAL, BinaryOp.GREATER_OR_EQUAL);
        }

        @Override
        public int selectAll(VectorBatch batch, int n, int[] out) {
            DoubleVector column = (DoubleVector) batch.column(index);
            double[] values = column.values();
            Validity nulls = column.validity();
            return switch (op) {
                case LESS -> DoubleCompare.lt(values, constant, nulls, n, out);
                case LESS_OR_EQUAL -> DoubleCompare.le(values, constant, nulls, n, out);
                default -> DoubleCompare.ge(values, constant, nulls, n, out);
            };
        }

        @Override
        public int selectFrom(VectorBatch batch, int[] sel, int n, int[] out) {
            DoubleVector column = (DoubleVector) batch.column(index);
            double[] values = column.values();
            Validity nulls = column.validity();
            return switch (op) {
                case LESS -> DoubleCompare.lt(values, constant, nulls, sel, n, out);
                case LESS_OR_EQUAL -> DoubleCompare.le(values, constant, nulls, sel, n, out);
                default -> DoubleCompare.ge(values, constant, nulls, sel, n, out);
            };
        }
    }

    /** A comparison on an int or date column. */
    private record IntKernel(int index, BinaryOp op, int constant) implements BatchPredicate {

        IntKernel {
            requireOp(op, BinaryOp.LESS, BinaryOp.GREATER_OR_EQUAL);
        }

        @Override
        public int selectAll(VectorBatch batch, int n, int[] out) {
            IntVector column = (IntVector) batch.column(index);
            int[] values = column.values();
            Validity nulls = column.validity();
            return op == BinaryOp.LESS
                    ? IntCompare.lt(values, constant, nulls, n, out)
                    : IntCompare.ge(values, constant, nulls, n, out);
        }

        @Override
        public int selectFrom(VectorBatch batch, int[] sel, int n, int[] out) {
            IntVector column = (IntVector) batch.column(index);
            int[] values = column.values();
            Validity nulls = column.validity();
            return op == BinaryOp.LESS
                    ? IntCompare.lt(values, constant, nulls, sel, n, out)
                    : IntCompare.ge(values, constant, nulls, sel, n, out);
        }
    }
}
