package columnar.exec.vec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.exec.agg.Aggregate;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.storage.InMemoryTable;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.DoubleVector;
import columnar.vector.IntVector;
import columnar.vector.StringVector;
import columnar.vector.VectorBatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorGroupedAggregateTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("day", DataType.DATE),
            Field.of("flag", DataType.VARCHAR),
            Field.of("part", DataType.INT32),
            Field.of("price", DataType.DOUBLE),
            Field.of("discount", DataType.DOUBLE));

    private static final String[] FLAGS = {"A", "N", "R", "", "LONGER-KEY"};

    private static InMemoryTable table(Random random, int rows, int batchSize, int distinct, boolean nulls) {
        List<VectorBatch> batches = new ArrayList<>();
        for (int start = 0; start < rows; start += batchSize) {
            int size = Math.min(batchSize, rows - start);
            VectorBatch batch = VectorBatch.allocate(SCHEMA, batchSize);
            IntVector day = (IntVector) batch.column(0);
            StringVector flag = (StringVector) batch.column(1);
            IntVector part = (IntVector) batch.column(2);
            DoubleVector price = (DoubleVector) batch.column(3);
            DoubleVector discount = (DoubleVector) batch.column(4);
            for (int i = 0; i < size; i++) {
                day.set(i, 8000 + random.nextInt(5));
                boolean nullFlag = nulls && random.nextInt(11) == 0;
                if (nullFlag) {
                    flag.appendNull();
                } else {
                    flag.append(FLAGS[random.nextInt(FLAGS.length)]);
                }
                part.set(i, random.nextInt(distinct));
                price.set(i, random.nextInt(100_000) / 100.0);
                discount.set(i, random.nextInt(12) / 100.0);
                if (nulls && random.nextInt(9) == 0) {
                    batch.column(3 + random.nextInt(2)).setNull(i);
                }
            }
            batch.setRowCount(size);
            batches.add(batch);
        }
        return InMemoryTable.of(SCHEMA, batches);
    }

    /** The obvious HashMap version the engine must agree with. */
    private static Map<List<Object>, List<Object>> reference(InMemoryTable table, List<String> keys) {
        Map<List<Object>, double[]> state = new HashMap<>();
        List<List<Object>> order = new ArrayList<>();
        for (int b = 0; b < table.batchCount(); b++) {
            VectorBatch batch = table.batch(b);
            for (int row = 0; row < batch.rowCount(); row++) {
                List<Object> key = new ArrayList<>();
                for (String name : keys) {
                    key.add(batch.column(SCHEMA.requireIndexOf(name)).valueAt(row));
                }
                if (!state.containsKey(key)) {
                    order.add(key);
                }
                double[] acc = state.computeIfAbsent(key, k -> new double[4]);
                acc[0]++;
                Object price = batch.column(3).valueAt(row);
                Object discount = batch.column(4).valueAt(row);
                if (price != null) {
                    acc[1] += (Double) price;
                    acc[2]++;
                }
                if (price != null && discount != null) {
                    acc[3] += (Double) price * (Double) discount;
                }
            }
        }
        Map<List<Object>, List<Object>> out = new HashMap<>();
        for (List<Object> key : order) {
            double[] acc = state.get(key);
            out.put(key, List.of((long) acc[0], acc[2] == 0 ? "NULL" : acc[1], acc[2] == 0 ? "NULL" : acc[1] / acc[2]));
        }
        return out;
    }

    private static Map<List<Object>, List<Object>> run(InMemoryTable table, List<String> keys, int batchSize) {
        List<Aggregate> aggregates = List.of(
                Aggregate.countStar("n"),
                Aggregate.sum(Expr.col("price"), "total"),
                Aggregate.avg(Expr.col("price"), "mean"));
        VectorGroupedAggregate op = new VectorGroupedAggregate(
                new VectorScan(table), keys, aggregates, batchSize);
        op.open();
        Map<List<Object>, List<Object>> out = new HashMap<>();
        VectorBatch batch;
        while ((batch = op.next()) != null) {
            for (int row = 0; row < batch.rowCount(); row++) {
                List<Object> key = new ArrayList<>();
                for (int k = 0; k < keys.size(); k++) {
                    key.add(batch.column(k).valueAt(row));
                }
                Object total = batch.column(keys.size() + 1).valueAt(row);
                Object mean = batch.column(keys.size() + 2).valueAt(row);
                out.put(key, List.of(batch.column(keys.size()).valueAt(row),
                        total == null ? "NULL" : total, mean == null ? "NULL" : mean));
            }
        }
        op.close();
        return out;
    }

    @Test
    @DisplayName("grouping matches a HashMap reference on 100 random tables")
    void matchesReference() {
        Random random = new Random(20261117L);
        for (int trial = 0; trial < 100; trial++) {
            InMemoryTable table = table(random, 1 + random.nextInt(400), 1 + random.nextInt(64), 8, trial % 2 == 0);
            List<String> keys = switch (trial % 3) {
                case 0 -> List.of("flag");
                case 1 -> List.of("flag", "day");
                default -> List.of("part");
            };
            assertEquals(reference(table, keys), run(table, keys, 1024), "trial " + trial);
        }
    }

    @Test
    @DisplayName("NULL is its own group, not dropped")
    void nullIsItsOwnGroup() {
        Random random = new Random(3L);
        InMemoryTable table = table(random, 600, 32, 4, true);
        Map<List<Object>, List<Object>> actual = run(table, List.of("flag"), 1024);
        assertEquals(reference(table, List.of("flag")), actual);
        List<Object> nullKey = new ArrayList<>();
        nullKey.add(null);
        assertTrue(actual.containsKey(nullKey), "a NULL key must appear as a group");
    }

    @Test
    @DisplayName("thousands of groups survive the rehashes")
    void growsPastTheInitialTable() {
        Random random = new Random(7L);
        InMemoryTable table = table(random, 20_000, 1024, 5_000, false);
        Map<List<Object>, List<Object>> actual = run(table, List.of("part"), 1024);
        assertEquals(reference(table, List.of("part")), actual);
        assertTrue(actual.size() > 4_000, "expected thousands of groups, got " + actual.size());
    }

    @Test
    @DisplayName("groups come out in batches, and the chunking changes nothing")
    void emitsInBatches() {
        Random random = new Random(11L);
        InMemoryTable table = table(random, 5_000, 256, 300, false);
        Map<List<Object>, List<Object>> whole = run(table, List.of("part"), 1024);
        assertEquals(whole, run(table, List.of("part"), 7), "chunked emission must agree");
        VectorGroupedAggregate op = new VectorGroupedAggregate(new VectorScan(table),
                List.of("part"), List.of(Aggregate.countStar("n")), 7);
        op.open();
        int batches = 0;
        VectorBatch batch;
        while ((batch = op.next()) != null) {
            assertTrue(batch.rowCount() <= 7, "a batch must respect the requested size");
            batches++;
        }
        op.close();
        assertTrue(batches > 1, "300 groups at 7 per batch should take many batches");
    }

    @Test
    @DisplayName("no input means no rows, not one row of nulls")
    void emptyInputYieldsNothing() {
        Random random = new Random(13L);
        InMemoryTable table = table(random, 200, 32, 4, false);
        Expr impossible = Expr.col("discount").binary(BinaryOp.LESS, Expr.lit(-1.0));
        VectorGroupedAggregate op = new VectorGroupedAggregate(
                new VectorFilter(new VectorScan(table), impossible),
                List.of("flag"), List.of(Aggregate.countStar("n")), 1024);
        op.open();
        assertNull(op.next(), "grouped aggregation over nothing returns no rows at all");
        op.close();
    }

    @Test
    @DisplayName("a group whose values are all NULL sums to NULL")
    void allNullGroupSumsToNull() {
        VectorBatch batch = VectorBatch.allocate(SCHEMA, 2);
        ((IntVector) batch.column(0)).set(0, 1);
        ((IntVector) batch.column(0)).set(1, 1);
        ((StringVector) batch.column(1)).append("X");
        ((StringVector) batch.column(1)).append("X");
        ((IntVector) batch.column(2)).set(0, 1);
        ((IntVector) batch.column(2)).set(1, 1);
        batch.column(3).setNull(0);
        batch.column(3).setNull(1);
        ((DoubleVector) batch.column(4)).set(0, 1.0);
        ((DoubleVector) batch.column(4)).set(1, 1.0);
        batch.setRowCount(2);
        InMemoryTable table = InMemoryTable.of(SCHEMA, List.of(batch));
        VectorGroupedAggregate op = new VectorGroupedAggregate(new VectorScan(table),
                List.of("flag"), List.of(Aggregate.sum(Expr.col("price"), "total"),
                        Aggregate.countStar("n")), 1024);
        op.open();
        VectorBatch out = op.next();
        assertNull(out.column(1).valueAt(0), "SUM over only NULLs is NULL");
        assertEquals(2L, out.column(2).valueAt(0), "COUNT(*) still counts the rows");
        op.close();
    }

    @Test
    @DisplayName("keys and aggregates without a kernel are refused")
    void refusesUnsupported() {
        Random random = new Random(19L);
        InMemoryTable table = table(random, 8, 8, 2, false);
        VectorScan scan = new VectorScan(table);
        assertThrows(UnsupportedOperationException.class, () -> new VectorGroupedAggregate(
                scan, List.of("price"), List.of(Aggregate.countStar("n")), 1024));
        assertThrows(UnsupportedOperationException.class, () -> new VectorGroupedAggregate(
                scan, List.of("flag"), List.of(Aggregate.min(Expr.col("price"), "m")), 1024));
        assertThrows(IllegalArgumentException.class, () -> new VectorGroupedAggregate(
                scan, List.of("flag"), List.of(), 1024));
        assertThrows(IllegalArgumentException.class, () -> new VectorGroupedAggregate(
                scan, List.of(), List.of(Aggregate.countStar("n")), 1024));
    }
}
