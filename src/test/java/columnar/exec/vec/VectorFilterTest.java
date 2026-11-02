package columnar.exec.vec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.exec.row.Row;
import columnar.exec.row.RowFilter;
import columnar.exec.row.RowScan;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.storage.InMemoryTable;
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

class VectorFilterTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("day", DataType.DATE),
            Field.of("discount", DataType.DOUBLE));

    /** Random rows, split into batches. */
    private static InMemoryTable table(Random random, int rows, int batchSize) {
        List<VectorBatch> batches = new ArrayList<>();
        for (int start = 0; start < rows; start += batchSize) {
            int size = Math.min(batchSize, rows - start);
            VectorBatch batch = VectorBatch.allocate(SCHEMA, batchSize);
            IntVector day = (IntVector) batch.column(0);
            DoubleVector discount = (DoubleVector) batch.column(1);
            for (int i = 0; i < size; i++) {
                day.set(i, 8000 + random.nextInt(2000));
                discount.set(i, random.nextInt(12) / 100.0);
                if (random.nextInt(10) == 0) {
                    batch.column(random.nextInt(2)).setNull(i);
                }
            }
            batch.setRowCount(size);
            batches.add(batch);
        }
        return InMemoryTable.of(SCHEMA, batches);
    }

    private static Expr rangePredicate() {
        return Expr.col("day").binary(BinaryOp.GREATER_OR_EQUAL, Expr.date("1992-01-01"))
                .binary(BinaryOp.AND, Expr.col("day").binary(BinaryOp.LESS, Expr.date("1995-01-01")))
                .binary(BinaryOp.AND, Expr.col("discount").binary(BinaryOp.GREATER_OR_EQUAL, Expr.lit(0.05)));
    }

    /** Every surviving discount, in order, from the vector engine. */
    private static List<Double> vectorAnswer(VectorOperator op) {
        List<Double> seen = new ArrayList<>();
        op.open();
        VectorBatch batch;
        while ((batch = op.next()) != null) {
            DoubleVector discount = (DoubleVector) batch.column(1);
            for (int i = 0; i < batch.selectedCount(); i++) {
                seen.add(discount.get(batch.rowIndex(i)));
            }
        }
        op.close();
        return seen;
    }

    private static List<Double> rowAnswer(InMemoryTable table, Expr predicate) {
        List<Double> seen = new ArrayList<>();
        RowFilter filter = new RowFilter(new RowScan(table), predicate);
        filter.open();
        Row row;
        while ((row = filter.next()) != null) {
            seen.add(row.getDouble(1));
        }
        filter.close();
        return seen;
    }

    @Test
    @DisplayName("the vector filter keeps exactly the rows the row filter keeps")
    void matchesRowFilter() {
        Random random = new Random(20261102L);
        for (int trial = 0; trial < 200; trial++) {
            InMemoryTable table = table(random, 1 + random.nextInt(300), 1 + random.nextInt(64));
            Expr predicate = rangePredicate();
            assertEquals(rowAnswer(table, predicate),
                    vectorAnswer(new VectorFilter(new VectorScan(table), predicate)), "trial " + trial);
        }
    }

    @Test
    @DisplayName("stacked filters compose, the second narrowing the first")
    void stacksOnAnotherFilter() {
        Random random = new Random(5L);
        InMemoryTable table = table(random, 500, 37);
        Expr first = Expr.col("day").binary(BinaryOp.GREATER_OR_EQUAL, Expr.date("1992-01-01"));
        Expr second = Expr.col("discount").binary(BinaryOp.LESS, Expr.lit(0.05));
        VectorOperator stacked = new VectorFilter(new VectorFilter(new VectorScan(table), first), second);
        assertEquals(rowAnswer(table, first.binary(BinaryOp.AND, second)), vectorAnswer(stacked));
    }

    @Test
    @DisplayName("a batch with no survivors is dropped, not handed on empty")
    void dropsEmptyBatches() {
        Random random = new Random(9L);
        InMemoryTable table = table(random, 200, 16);
        Expr impossible = Expr.col("discount").binary(BinaryOp.LESS, Expr.lit(-1.0));
        VectorFilter filter = new VectorFilter(new VectorScan(table), impossible);
        filter.open();
        assertNull(filter.next(), "nothing passes, so the stream ends at once");
        filter.close();
    }

    @Test
    @DisplayName("filtering narrows the table's own batch without copying it")
    void narrowsInPlace() {
        Random random = new Random(11L);
        InMemoryTable table = table(random, 64, 64);
        Expr predicate = Expr.col("discount").binary(BinaryOp.GREATER_OR_EQUAL, Expr.lit(0.0));
        VectorFilter filter = new VectorFilter(new VectorScan(table), predicate);
        filter.open();
        VectorBatch batch = filter.next();
        assertSame(table.batch(0), batch, "the filter hands back the table's batch");
        assertTrue(batch.hasSelection(), "narrowed by selection, not by copying");
        assertEquals(SCHEMA, filter.schema());
        filter.close();
    }

    @Test
    @DisplayName("the plan reads back as the predicate it was given")
    void describesItself() {
        Random random = new Random(3L);
        InMemoryTable table = table(random, 8, 8);
        Expr predicate = Expr.col("discount").binary(BinaryOp.LESS, Expr.lit(0.05));
        assertEquals("VectorFilter((discount < 0.05))",
                new VectorFilter(new VectorScan(table), predicate).toString());
    }
}
