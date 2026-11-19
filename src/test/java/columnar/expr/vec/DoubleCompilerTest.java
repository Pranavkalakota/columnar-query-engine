package columnar.expr.vec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.exec.row.BatchRow;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.expr.RowEvaluator;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.DoubleVector;
import columnar.vector.IntVector;
import columnar.vector.VectorBatch;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DoubleCompilerTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("price", DataType.DOUBLE),
            Field.of("discount", DataType.DOUBLE),
            Field.of("tax", DataType.DOUBLE),
            Field.of("part", DataType.INT32));

    private static Expr col(String name) {
        return Expr.col(name);
    }

    /** The two Q1 shapes, plus simpler ones around them. */
    private static List<Expr> shapes() {
        Expr discPrice = col("price").binary(BinaryOp.MULTIPLY,
                Expr.lit(1.0).binary(BinaryOp.SUBTRACT, col("discount")));
        return List.of(
                col("price"),
                Expr.lit(2.5),
                col("price").binary(BinaryOp.MULTIPLY, col("discount")),
                col("price").binary(BinaryOp.ADD, col("tax")),
                col("price").binary(BinaryOp.DIVIDE, col("discount")),
                discPrice,
                discPrice.binary(BinaryOp.MULTIPLY,
                        Expr.lit(1.0).binary(BinaryOp.ADD, col("tax"))));
    }

    private static VectorBatch batch(Random random, int size, boolean nulls) {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, size);
        DoubleVector price = (DoubleVector) batch.column(0);
        DoubleVector discount = (DoubleVector) batch.column(1);
        DoubleVector tax = (DoubleVector) batch.column(2);
        IntVector part = (IntVector) batch.column(3);
        for (int i = 0; i < size; i++) {
            price.set(i, random.nextInt(100_000) / 100.0);
            discount.set(i, random.nextInt(12) / 100.0);
            tax.set(i, random.nextInt(9) / 100.0);
            part.set(i, random.nextInt(50));
            if (nulls && random.nextInt(8) == 0) {
                batch.column(random.nextInt(3)).setNull(i);
            }
        }
        batch.setRowCount(size);
        return batch;
    }

    /** What the row interpreter says, row by row. */
    private static Double rowAnswer(Expr expr, VectorBatch batch, int row) {
        BatchRow cursor = new BatchRow(batch);
        cursor.position(row);
        return (Double) new RowEvaluator(expr, batch.schema()).evaluate(cursor);
    }

    private static void check(Expr expr, VectorBatch batch, int[] sel, int n, String where) {
        DoubleKernel kernel = DoubleCompiler.compile(expr, batch.schema());
        double[] out = new double[Math.max(1, n)];
        boolean[] valid = new boolean[Math.max(1, n)];
        boolean all = kernel.evaluate(batch, sel, n, out, valid);
        for (int i = 0; i < n; i++) {
            int row = sel == null ? i : sel[i];
            Double expected = rowAnswer(expr, batch, row);
            boolean isValid = all || valid[i];
            assertEquals(expected != null, isValid, where + " validity at " + i);
            if (expected != null) {
                assertEquals(expected, out[i], where + " value at " + i);
            }
        }
    }

    @Test
    @DisplayName("every shape matches the row evaluator on 500 random batches")
    void matchesRowEvaluator() {
        Random random = new Random(20261118L);
        for (int trial = 0; trial < 500; trial++) {
            VectorBatch batch = batch(random, 1 + random.nextInt(50), trial % 2 == 0);
            int rows = batch.rowCount();
            int[] sel = new int[rows];
            int n = 0;
            for (int i = 0; i < rows; i++) {
                if (random.nextInt(3) > 0) {
                    sel[n++] = i;
                }
            }
            for (Expr expr : shapes()) {
                check(expr, batch, null, rows, "trial " + trial + " dense " + expr);
                check(expr, batch, sel, n, "trial " + trial + " selected " + expr);
            }
        }
    }

    @Test
    @DisplayName("a null anywhere in the tree makes the whole row null")
    void nullsPropagate() {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, 3);
        DoubleVector price = (DoubleVector) batch.column(0);
        DoubleVector discount = (DoubleVector) batch.column(1);
        DoubleVector tax = (DoubleVector) batch.column(2);
        for (int i = 0; i < 3; i++) {
            price.set(i, 100.0);
            discount.set(i, 0.1);
            tax.set(i, 0.05);
        }
        batch.column(1).setNull(1);
        batch.column(2).setNull(2);
        batch.setRowCount(3);
        Expr expr = col("price")
                .binary(BinaryOp.MULTIPLY, Expr.lit(1.0).binary(BinaryOp.SUBTRACT, col("discount")))
                .binary(BinaryOp.MULTIPLY, Expr.lit(1.0).binary(BinaryOp.ADD, col("tax")));
        DoubleKernel kernel = DoubleCompiler.compile(expr, SCHEMA);
        double[] out = new double[3];
        boolean[] valid = new boolean[3];
        assertFalse(kernel.evaluate(batch, null, 3, out, valid), "nulls present");
        assertTrue(valid[0], "row 0 has no nulls");
        assertFalse(valid[1], "a null discount kills the row");
        assertFalse(valid[2], "a null tax kills the row too");
        assertEquals(100.0 * 0.9 * 1.05, out[0]);
    }

    @Test
    @DisplayName("a column with no nulls reports every row valid without touching the mask")
    void noNullsTakesTheFastPath() {
        Random random = new Random(5L);
        VectorBatch batch = batch(random, 16, false);
        DoubleKernel kernel = DoubleCompiler.compile(
                col("price").binary(BinaryOp.MULTIPLY, col("discount")), SCHEMA);
        boolean[] valid = new boolean[16];
        assertTrue(kernel.evaluate(batch, null, 16, new double[16], valid), "no nulls anywhere");
        assertFalse(valid[0], "the mask stays untouched on the fast path");
    }

    @Test
    @DisplayName("dividing by zero gives infinity, as DuckDB does")
    void divideByZero() {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, 1);
        ((DoubleVector) batch.column(0)).set(0, 1.0);
        ((DoubleVector) batch.column(1)).set(0, 0.0);
        ((DoubleVector) batch.column(2)).set(0, 0.0);
        batch.setRowCount(1);
        DoubleKernel kernel = DoubleCompiler.compile(
                col("price").binary(BinaryOp.DIVIDE, col("discount")), SCHEMA);
        double[] out = new double[1];
        assertTrue(kernel.evaluate(batch, null, 1, out, new boolean[1]));
        assertEquals(Double.POSITIVE_INFINITY, out[0]);
    }

    @Test
    @DisplayName("shapes without a kernel are refused at compile time")
    void refusesUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> DoubleCompiler.compile(col("part"), SCHEMA));
        assertThrows(UnsupportedOperationException.class,
                () -> DoubleCompiler.compile(col("part").binary(BinaryOp.ADD, col("part")), SCHEMA));
        assertThrows(UnsupportedOperationException.class,
                () -> DoubleCompiler.compile(Expr.nul(DataType.DOUBLE), SCHEMA));
        assertThrows(UnsupportedOperationException.class, () -> DoubleCompiler.compile(
                col("price").binary(BinaryOp.LESS, Expr.lit(1.0)), SCHEMA));
        assertFalse(DoubleCompiler.supports(col("part"), SCHEMA));
        assertTrue(DoubleCompiler.supports(col("price").binary(BinaryOp.MULTIPLY, col("tax")), SCHEMA));
    }
}
