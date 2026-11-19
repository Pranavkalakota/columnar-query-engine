package columnar.expr.vec;

import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.types.DataType;
import columnar.types.Schema;
import columnar.vector.DoubleVector;
import columnar.vector.VectorBatch;

/** Compiles an arithmetic expression into a batch kernel. */
public final class DoubleCompiler {

    private DoubleCompiler() {
    }

    public static DoubleKernel compile(Expr expr, Schema schema) {
        DataType type = expr.type(schema);
        if (type != DataType.DOUBLE) {
            throw new UnsupportedOperationException("expected a double expression, got " + type);
        }
        return node(expr, schema);
    }

    /** True when the whole expression has kernels behind it. */
    public static boolean supports(Expr expr, Schema schema) {
        try {
            compile(expr, schema);
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    private static DoubleKernel node(Expr expr, Schema schema) {
        if (expr instanceof Expr.Literal literal) {
            if (literal.value() == null) {
                throw new UnsupportedOperationException("a NULL literal has no kernel");
            }
            return new Constant(((Number) literal.value()).doubleValue());
        }
        if (expr instanceof Expr.ColumnRef ref) {
            int index = schema.requireIndexOf(ref.name());
            if (schema.type(index) != DataType.DOUBLE) {
                throw new UnsupportedOperationException("expected a double column: " + ref);
            }
            return new Column(index);
        }
        if (expr instanceof Expr.Binary binary && binary.op().isArithmetic()) {
            requireDouble(binary, schema);
            return new Arith(binary.op(), node(binary.left(), schema), node(binary.right(), schema));
        }
        throw new UnsupportedOperationException("no kernel for " + expr);
    }

    /** Integer arithmetic is refused rather than widened silently. */
    private static void requireDouble(Expr.Binary binary, Schema schema) {
        if (binary.type(schema) != DataType.DOUBLE) {
            throw new UnsupportedOperationException("not a double subexpression: " + binary);
        }
    }

    private record Constant(double value) implements DoubleKernel {

        @Override
        public boolean evaluate(VectorBatch batch, int[] sel, int n, double[] out, boolean[] valid) {
            java.util.Arrays.fill(out, 0, n, value);
            return true;
        }
    }

    private record Column(int index) implements DoubleKernel {

        @Override
        public boolean evaluate(VectorBatch batch, int[] sel, int n, double[] out, boolean[] valid) {
            DoubleVector column = (DoubleVector) batch.column(index);
            double[] values = column.values();
            if (sel == null) {
                System.arraycopy(values, 0, out, 0, n);
            } else {
                for (int i = 0; i < n; i++) {
                    out[i] = values[sel[i]];
                }
            }
            if (!column.mayHaveNulls()) {
                return true;
            }
            for (int i = 0; i < n; i++) {
                valid[i] = !column.isNull(sel == null ? i : sel[i]);
            }
            return false;
        }
    }

    private static final class Arith implements DoubleKernel {

        private final BinaryOp op;
        private final DoubleKernel left;
        private final DoubleKernel right;
        private double[] scratch = new double[0];
        private boolean[] scratchValid = new boolean[0];

        Arith(BinaryOp op, DoubleKernel left, DoubleKernel right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean evaluate(VectorBatch batch, int[] sel, int n, double[] out, boolean[] valid) {
            if (scratch.length < n) {
                scratch = new double[n];
                scratchValid = new boolean[n];
            }
            boolean leftAll = left.evaluate(batch, sel, n, out, valid);
            boolean rightAll = right.evaluate(batch, sel, n, scratch, scratchValid);
            combine(n, out);
            if (leftAll && rightAll) {
                return true;
            }
            if (leftAll) {
                System.arraycopy(scratchValid, 0, valid, 0, n);
            } else if (!rightAll) {
                for (int i = 0; i < n; i++) {
                    valid[i] &= scratchValid[i];
                }
            }
            return false;
        }

        private void combine(int n, double[] out) {
            switch (op) {
                case ADD -> {
                    for (int i = 0; i < n; i++) {
                        out[i] += scratch[i];
                    }
                }
                case SUBTRACT -> {
                    for (int i = 0; i < n; i++) {
                        out[i] -= scratch[i];
                    }
                }
                case MULTIPLY -> {
                    for (int i = 0; i < n; i++) {
                        out[i] *= scratch[i];
                    }
                }
                default -> {
                    for (int i = 0; i < n; i++) {
                        out[i] /= scratch[i];
                    }
                }
            }
        }

        @Override
        public String toString() {
            return "(" + left + " " + op + " " + right + ")";
        }
    }
}
