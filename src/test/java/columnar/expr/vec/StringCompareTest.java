package columnar.expr.vec;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import columnar.exec.row.BatchRow;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.expr.RowEvaluator;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.StringVector;
import columnar.vector.VectorBatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StringCompareTest {

    private static final Schema SCHEMA = Schema.of(Field.of("s", DataType.VARCHAR));
    private static final String[] POOL = {"", "BUILDING", "BUILDINGS", "MACHINERY", "b", "😀"};

    private static VectorBatch batch(Random random, int size) {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, size);
        StringVector s = (StringVector) batch.column(0);
        for (int i = 0; i < size; i++) {
            if (random.nextInt(6) == 0) {
                s.appendNull();
            } else {
                s.append(POOL[random.nextInt(POOL.length)]);
            }
        }
        batch.setRowCount(size);
        return batch;
    }

    /** What the row interpreter keeps, as row numbers. */
    private static int[] rowAnswer(Expr predicate, VectorBatch batch, int[] sel, int n) {
        RowEvaluator evaluator = new RowEvaluator(predicate, SCHEMA);
        BatchRow row = new BatchRow(batch);
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int r = sel == null ? i : sel[i];
            row.position(r);
            if (Boolean.TRUE.equals(evaluator.evaluate(row))) {
                kept.add(r);
            }
        }
        return kept.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] head(int[] a, int n) {
        int[] out = new int[n];
        System.arraycopy(a, 0, out, 0, n);
        return out;
    }

    @Test
    @DisplayName("equality agrees with the row interpreter on 2,000 batches")
    void matchesRowEvaluator() {
        Random random = new Random(20261121L);
        for (int trial = 0; trial < 2_000; trial++) {
            VectorBatch batch = batch(random, 1 + random.nextInt(40));
            String constant = POOL[random.nextInt(POOL.length)];
            Expr predicate = Expr.col("s").binary(BinaryOp.EQUAL, Expr.lit(constant));
            BatchPredicate kernel = PredicateCompiler.compile(predicate, SCHEMA).get(0);
            int rows = batch.rowCount();
            int[] out = new int[rows];
            assertArrayEquals(rowAnswer(predicate, batch, null, rows),
                    head(out, kernel.selectAll(batch, rows, out)), "dense, trial " + trial);
            int[] sel = new int[rows];
            int n = 0;
            for (int i = 0; i < rows; i++) {
                if (random.nextBoolean()) {
                    sel[n++] = i;
                }
            }
            assertArrayEquals(rowAnswer(predicate, batch, sel, n),
                    head(out, kernel.selectFrom(batch, sel, n, out)), "selected, trial " + trial);
        }
    }

    @Test
    @DisplayName("'' matches empty strings but never NULL, though both are zero bytes")
    void emptyIsNotNull() {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, 3);
        StringVector s = (StringVector) batch.column(0);
        s.append("");
        s.appendNull();
        s.append("");
        batch.setRowCount(3);
        int[] out = new int[3];
        assertEquals(2, StringCompare.eq(s, "".getBytes(UTF_8), 3, out));
        assertArrayEquals(new int[] {0, 2}, head(out, 2));
    }

    @Test
    @DisplayName("only equality compiles for strings")
    void refusesOrdering() {
        Expr less = Expr.col("s").binary(BinaryOp.LESS, Expr.lit("m"));
        assertThrows(UnsupportedOperationException.class, () -> PredicateCompiler.compile(less, SCHEMA));
    }
}
