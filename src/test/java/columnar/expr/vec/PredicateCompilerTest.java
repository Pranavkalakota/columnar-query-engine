package columnar.expr.vec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PredicateCompilerTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("shipdate", DataType.DATE),
            Field.of("discount", DataType.DOUBLE),
            Field.of("quantity", DataType.DOUBLE),
            Field.of("linenumber", DataType.INT32));

    /** The Q6 shape, five conjuncts. */
    private static Expr q6() {
        Expr dateLow = Expr.col("shipdate").binary(BinaryOp.GREATER_OR_EQUAL, Expr.date("1994-01-01"));
        Expr dateHigh = Expr.col("shipdate").binary(BinaryOp.LESS, Expr.date("1995-01-01"));
        Expr low = Expr.col("discount").binary(BinaryOp.GREATER_OR_EQUAL, Expr.lit(0.05));
        Expr high = Expr.col("discount").binary(BinaryOp.LESS_OR_EQUAL, Expr.lit(0.07));
        Expr cap = Expr.col("quantity").binary(BinaryOp.LESS, Expr.lit(24.0));
        return dateLow.binary(BinaryOp.AND, dateHigh).binary(BinaryOp.AND, low)
                .binary(BinaryOp.AND, high).binary(BinaryOp.AND, cap);
    }

    /** Runs a chain over a batch, narrowing in place. */
    private static int[] run(List<BatchPredicate> chain, VectorBatch batch) {
        int[] sel = new int[batch.rowCount()];
        int n = chain.get(0).selectAll(batch, batch.rowCount(), sel);
        for (int i = 1; i < chain.size(); i++) {
            n = chain.get(i).selectFrom(batch, sel, n, sel);
        }
        int[] out = new int[n];
        System.arraycopy(sel, 0, out, 0, n);
        return out;
    }

    /** What the row engine says about the same predicate. */
    private static int[] rowAnswer(Expr predicate, VectorBatch batch) {
        RowEvaluator evaluator = new RowEvaluator(predicate, batch.schema());
        BatchRow row = new BatchRow(batch);
        List<Integer> passed = new ArrayList<>();
        for (int i = 0; i < batch.rowCount(); i++) {
            row.position(i);
            if (Boolean.TRUE.equals(evaluator.evaluate(row))) {
                passed.add(i);
            }
        }
        return passed.stream().mapToInt(Integer::intValue).toArray();
    }

    private static VectorBatch randomBatch(Random random, int size) {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, size);
        IntVector shipdate = (IntVector) batch.column(0);
        DoubleVector discount = (DoubleVector) batch.column(1);
        DoubleVector quantity = (DoubleVector) batch.column(2);
        IntVector linenumber = (IntVector) batch.column(3);
        for (int i = 0; i < size; i++) {
            shipdate.set(i, 8000 + random.nextInt(2000));
            discount.set(i, random.nextInt(12) / 100.0);
            quantity.set(i, 1 + random.nextInt(50));
            linenumber.set(i, 1 + random.nextInt(7));
            if (random.nextInt(12) == 0) {
                batch.column(random.nextInt(4)).setNull(i);
            }
        }
        batch.setRowCount(size);
        return batch;
    }

    @Test
    @DisplayName("the Q6 predicate splits into five kernels")
    void flattensConjunction() {
        assertEquals(5, PredicateCompiler.compile(q6(), SCHEMA).size());
        assertTrue(PredicateCompiler.supports(q6(), SCHEMA));
    }

    @Test
    @DisplayName("the kernel chain agrees with the row evaluator on 2,000 batches")
    void agreesWithRowEngine() {
        Random random = new Random(20261102L);
        Expr predicate = q6();
        List<BatchPredicate> chain = PredicateCompiler.compile(predicate, SCHEMA);
        for (int trial = 0; trial < 2_000; trial++) {
            VectorBatch batch = randomBatch(random, 1 + random.nextInt(70));
            assertArrayEquals(rowAnswer(predicate, batch), run(chain, batch), "trial " + trial);
        }
    }

    @Test
    @DisplayName("a literal on the left is mirrored, not refused")
    void mirrorsLiteralOnLeft() {
        Expr written = Expr.lit(24.0).binary(BinaryOp.GREATER, Expr.col("quantity"));
        Random random = new Random(7L);
        VectorBatch batch = randomBatch(random, 64);
        List<BatchPredicate> chain = PredicateCompiler.compile(written, SCHEMA);
        assertArrayEquals(rowAnswer(written, batch), run(chain, batch));
    }

    @Test
    @DisplayName("a fractional literal on an int column is refused, never truncated")
    void refusesFractionalLiteral() {
        Expr expr = Expr.col("linenumber").binary(BinaryOp.LESS, Expr.lit(2.5));
        assertThrows(UnsupportedOperationException.class, () -> PredicateCompiler.compile(expr, SCHEMA));
        assertFalse(PredicateCompiler.supports(expr, SCHEMA));
    }

    @Test
    @DisplayName("a literal wider than int is refused")
    void refusesWideLiteral() {
        Expr expr = Expr.col("linenumber").binary(BinaryOp.GREATER_OR_EQUAL, Expr.lit(5_000_000_000L));
        assertThrows(UnsupportedOperationException.class, () -> PredicateCompiler.compile(expr, SCHEMA));
    }

    @Test
    @DisplayName("operators and shapes without a kernel are refused")
    void refusesUnsupported() {
        Expr equal = Expr.col("discount").binary(BinaryOp.EQUAL, Expr.lit(0.05));
        assertThrows(UnsupportedOperationException.class, () -> PredicateCompiler.compile(equal, SCHEMA));
        Expr twoColumns = Expr.col("discount").binary(BinaryOp.LESS, Expr.col("quantity"));
        assertThrows(UnsupportedOperationException.class, () -> PredicateCompiler.compile(twoColumns, SCHEMA));
        Expr nullLiteral = Expr.col("discount").binary(BinaryOp.LESS, Expr.nul(DataType.DOUBLE));
        assertThrows(UnsupportedOperationException.class, () -> PredicateCompiler.compile(nullLiteral, SCHEMA));
        assertFalse(PredicateCompiler.supports(twoColumns, SCHEMA));
    }

    @Test
    @DisplayName("a non-boolean predicate is rejected outright")
    void rejectsNonBoolean() {
        Expr arithmetic = Expr.col("discount").binary(BinaryOp.MULTIPLY, Expr.lit(2.0));
        assertThrows(IllegalArgumentException.class, () -> PredicateCompiler.compile(arithmetic, SCHEMA));
    }
}
