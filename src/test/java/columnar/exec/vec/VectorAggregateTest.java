package columnar.exec.vec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import columnar.exec.agg.Aggregate;
import columnar.exec.row.Row;
import columnar.exec.row.RowAggregate;
import columnar.exec.row.RowFilter;
import columnar.exec.row.RowOperator;
import columnar.exec.row.RowScan;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.storage.InMemoryTable;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.DoubleVector;
import columnar.vector.IntVector;
import columnar.vector.LongVector;
import columnar.vector.VectorBatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorAggregateTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("day", DataType.DATE),
            Field.of("price", DataType.DOUBLE),
            Field.of("discount", DataType.DOUBLE),
            Field.of("qty", DataType.INT64));

    private static InMemoryTable table(Random random, int rows, int batchSize, boolean nulls) {
        List<VectorBatch> batches = new ArrayList<>();
        for (int start = 0; start < rows; start += batchSize) {
            int size = Math.min(batchSize, rows - start);
            VectorBatch batch = VectorBatch.allocate(SCHEMA, batchSize);
            IntVector day = (IntVector) batch.column(0);
            DoubleVector price = (DoubleVector) batch.column(1);
            DoubleVector discount = (DoubleVector) batch.column(2);
            LongVector qty = (LongVector) batch.column(3);
            for (int i = 0; i < size; i++) {
                day.set(i, 8000 + random.nextInt(2000));
                price.set(i, random.nextInt(100_000) / 100.0);
                discount.set(i, random.nextInt(12) / 100.0);
                qty.set(i, 1 + random.nextInt(50));
                if (nulls && random.nextInt(10) == 0) {
                    batch.column(1 + random.nextInt(2)).setNull(i);
                }
            }
            batch.setRowCount(size);
            batches.add(batch);
        }
        return InMemoryTable.of(SCHEMA, batches);
    }

    private static Expr revenue() {
        return Expr.col("price").binary(BinaryOp.MULTIPLY, Expr.col("discount"));
    }

    private static Expr predicate() {
        return Expr.col("day").binary(BinaryOp.GREATER_OR_EQUAL, Expr.date("1992-01-01"))
                .binary(BinaryOp.AND, Expr.col("discount").binary(BinaryOp.LESS, Expr.lit(0.07)));
    }

    private static Object rowResult(InMemoryTable table, Expr predicate, List<Aggregate> aggregates, int column) {
        RowOperator scan = new RowScan(table);
        RowOperator source = predicate == null ? scan : new RowFilter(scan, predicate);
        RowAggregate op = new RowAggregate(source, aggregates);
        op.open();
        Row row = op.next();
        Object value = row.get(column);
        op.close();
        return value;
    }

    private static Object vectorResult(InMemoryTable table, Expr predicate, List<Aggregate> aggregates, int column) {
        VectorOperator scan = new VectorScan(table);
        VectorOperator source = predicate == null ? scan : new VectorFilter(scan, predicate);
        VectorAggregate op = new VectorAggregate(source, aggregates);
        op.open();
        VectorBatch batch = op.next();
        Object value = batch.column(column).valueAt(0);
        assertNull(op.next(), "an ungrouped aggregate yields exactly one batch");
        op.close();
        return value;
    }

    @Test
    @DisplayName("summed revenue is bit-identical to the row engine on 200 tables")
    void matchesRowEngineExactly() {
        Random random = new Random(20261102L);
        List<Aggregate> aggregates = List.of(Aggregate.sum(revenue(), "revenue"));
        for (int trial = 0; trial < 200; trial++) {
            InMemoryTable table = table(random, 1 + random.nextInt(400), 1 + random.nextInt(64), trial % 2 == 0);
            assertEquals(rowResult(table, predicate(), aggregates, 0),
                    vectorResult(table, predicate(), aggregates, 0), "trial " + trial);
        }
    }

    @Test
    @DisplayName("an unfiltered sum over a bare column matches too")
    void sumsABareColumn() {
        Random random = new Random(31L);
        List<Aggregate> aggregates = List.of(Aggregate.sum(Expr.col("price"), "total"));
        InMemoryTable table = table(random, 500, 64, true);
        assertEquals(rowResult(table, null, aggregates, 0), vectorResult(table, null, aggregates, 0));
    }

    @Test
    @DisplayName("COUNT(*) counts selected rows, not materialized ones")
    void countsSelectedRows() {
        Random random = new Random(42L);
        List<Aggregate> aggregates = List.of(Aggregate.countStar("n"));
        InMemoryTable table = table(random, 300, 32, false);
        assertEquals(rowResult(table, predicate(), aggregates, 0),
                vectorResult(table, predicate(), aggregates, 0));
    }

    @Test
    @DisplayName("a sum over nothing is NULL, not zero")
    void emptyInputGivesNull() {
        Random random = new Random(13L);
        InMemoryTable table = table(random, 100, 16, false);
        Expr impossible = Expr.col("discount").binary(BinaryOp.LESS, Expr.lit(-1.0));
        VectorAggregate op = new VectorAggregate(new VectorFilter(new VectorScan(table), impossible),
                List.of(Aggregate.sum(revenue(), "revenue"), Aggregate.countStar("n")));
        op.open();
        VectorBatch batch = op.next();
        assertNull(batch.column(0).valueAt(0), "SUM over no rows is NULL");
        assertEquals(0L, ((LongVector) batch.column(1)).get(0), "COUNT(*) over no rows is zero");
        op.close();
    }

    @Test
    @DisplayName("reopening starts the fold from scratch")
    void reopenResetsState() {
        Random random = new Random(17L);
        InMemoryTable table = table(random, 200, 32, false);
        List<Aggregate> aggregates = List.of(Aggregate.sum(Expr.col("price"), "total"));
        VectorAggregate op = new VectorAggregate(new VectorScan(table), aggregates);
        op.open();
        double first = ((DoubleVector) op.next().column(0)).get(0);
        op.open();
        double second = ((DoubleVector) op.next().column(0)).get(0);
        op.close();
        assertEquals(first, second, "a second run must not double the first");
    }

    @Test
    @DisplayName("aggregates without a kernel are refused at build time")
    void refusesUnsupported() {
        Random random = new Random(19L);
        InMemoryTable table = table(random, 8, 8, false);
        VectorScan scan = new VectorScan(table);
        assertThrows(UnsupportedOperationException.class,
                () -> new VectorAggregate(scan, List.of(Aggregate.min(Expr.col("price"), "m"))));
        assertThrows(UnsupportedOperationException.class,
                () -> new VectorAggregate(scan, List.of(Aggregate.sum(Expr.col("qty"), "q"))));
        assertThrows(UnsupportedOperationException.class, () -> new VectorAggregate(scan,
                List.of(Aggregate.sum(Expr.col("price").binary(BinaryOp.ADD, Expr.lit(1.0)), "s"))));
        assertThrows(IllegalArgumentException.class, () -> new VectorAggregate(scan, List.of()));
    }
}
