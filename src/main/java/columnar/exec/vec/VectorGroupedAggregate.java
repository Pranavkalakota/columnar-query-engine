package columnar.exec.vec;

import columnar.exec.agg.Aggregate;
import columnar.exec.agg.AggregateFunction;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.DoubleVector;
import columnar.vector.LongVector;
import columnar.vector.VectorBatch;
import java.util.ArrayList;
import java.util.List;

/** Hash aggregation: one row per distinct key. */
public final class VectorGroupedAggregate implements VectorOperator {

    private final VectorOperator child;
    private final List<String> keyNames;
    private final List<Aggregate> aggregates;
    private final Schema schema;
    private final int batchSize;
    private GroupKeys keys;
    private GroupFolder[] folders;
    private boolean drained;
    private int emitted;

    public VectorGroupedAggregate(VectorOperator child, List<String> keyNames,
            List<Aggregate> aggregates, int batchSize) {
        if (aggregates.isEmpty()) {
            throw new IllegalArgumentException("an aggregate needs at least one function");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be positive: " + batchSize);
        }
        this.child = child;
        this.keyNames = List.copyOf(keyNames);
        this.aggregates = List.copyOf(aggregates);
        this.batchSize = batchSize;
        this.keys = new GroupKeys(child.schema(), this.keyNames);
        this.folders = build();
        List<Field> fields = new ArrayList<>(keys.schema().fields());
        for (Aggregate aggregate : this.aggregates) {
            fields.add(Field.of(aggregate.name(), aggregate.resultType(child.schema())));
        }
        this.schema = Schema.of(fields);
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public void open() {
        child.open();
        keys = new GroupKeys(child.schema(), keyNames);
        folders = build();
        drained = false;
        emitted = 0;
    }

    /** Builds the table on the first call, then emits it. */
    @Override
    public VectorBatch next() {
        if (!drained) {
            drain();
            drained = true;
        }
        if (emitted >= keys.groupCount()) {
            return null;
        }
        int rows = Math.min(batchSize, keys.groupCount() - emitted);
        VectorBatch out = VectorBatch.allocate(schema, rows);
        out.setRowCount(rows);
        int keyCount = keys.schema().size();
        for (int row = 0; row < rows; row++) {
            int group = emitted + row;
            keys.writeKey(group, out, row);
            for (int i = 0; i < folders.length; i++) {
                write(out, keyCount + i, row, folders[i].result(group));
            }
        }
        emitted += rows;
        return out;
    }

    private void write(VectorBatch out, int column, int row, Object value) {
        if (value == null) {
            out.column(column).setNull(row);
        } else if (schema.type(column) == DataType.DOUBLE) {
            ((DoubleVector) out.column(column)).set(row, (Double) value);
        } else {
            ((LongVector) out.column(column)).set(row, (Long) value);
        }
    }

    private void drain() {
        VectorBatch batch;
        while ((batch = child.next()) != null) {
            int rows = batch.selectedCount();
            if (rows == 0) {
                continue;
            }
            int[] ordinals = keys.ordinalsFor(batch);
            for (GroupFolder folder : folders) {
                folder.ensure(keys.groupCount());
                folder.fold(batch, ordinals, rows);
            }
        }
    }

    private GroupFolder[] build() {
        GroupFolder[] built = new GroupFolder[aggregates.size()];
        for (int i = 0; i < built.length; i++) {
            built[i] = folder(aggregates.get(i));
        }
        return built;
    }

    /** Only the shapes with a kernel behind them compile. */
    private GroupFolder folder(Aggregate aggregate) {
        if (aggregate.function() == AggregateFunction.COUNT_STAR) {
            return GroupFolder.counter();
        }
        AggregateFunction function = aggregate.function();
        if (function != AggregateFunction.SUM && function != AggregateFunction.AVG) {
            throw new UnsupportedOperationException("no kernel for " + function);
        }
        Schema input = child.schema();
        if (aggregate.resultType(input) != DataType.DOUBLE) {
            throw new UnsupportedOperationException("only double results have a kernel: " + aggregate);
        }
        boolean mean = function == AggregateFunction.AVG;
        Expr expr = aggregate.input();
        if (expr instanceof Expr.ColumnRef ref) {
            return GroupFolder.sums(doubleColumn(ref, input), -1, mean);
        }
        if (expr instanceof Expr.Binary binary && binary.op() == BinaryOp.MULTIPLY
                && binary.left() instanceof Expr.ColumnRef left
                && binary.right() instanceof Expr.ColumnRef right) {
            return GroupFolder.sums(doubleColumn(left, input), doubleColumn(right, input), mean);
        }
        throw new UnsupportedOperationException("no kernel for " + function + "(" + expr + ")");
    }

    private static int doubleColumn(Expr.ColumnRef ref, Schema schema) {
        int index = schema.requireIndexOf(ref.name());
        if (schema.type(index) != DataType.DOUBLE) {
            throw new UnsupportedOperationException("expected a double column: " + ref);
        }
        return index;
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public String toString() {
        return "VectorGroupedAggregate(by " + keyNames + ", " + aggregates + ")";
    }
}
