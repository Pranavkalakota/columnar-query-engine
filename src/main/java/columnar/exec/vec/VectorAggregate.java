package columnar.exec.vec;

import columnar.exec.agg.Aggregate;
import columnar.exec.agg.AggregateFunction;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.expr.vec.RunningSum;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.DoubleVector;
import columnar.vector.LongVector;
import columnar.vector.VectorBatch;
import java.util.ArrayList;
import java.util.List;

/** Ungrouped aggregation over batches, yielding one row. */
public final class VectorAggregate implements VectorOperator {

    private final VectorOperator child;
    private final List<Aggregate> aggregates;
    private final Schema schema;
    private Folder[] folders;
    private boolean produced;

    public VectorAggregate(VectorOperator child, List<Aggregate> aggregates) {
        if (aggregates.isEmpty()) {
            throw new IllegalArgumentException("an aggregate needs at least one function");
        }
        this.child = child;
        this.aggregates = List.copyOf(aggregates);
        List<Field> fields = new ArrayList<>(aggregates.size());
        for (Aggregate aggregate : this.aggregates) {
            fields.add(Field.of(aggregate.name(), aggregate.resultType(child.schema())));
        }
        this.schema = Schema.of(fields);
        this.folders = build();
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public void open() {
        child.open();
        folders = build();
        produced = false;
    }

    /** Drains the child on the first call, then ends. */
    @Override
    public VectorBatch next() {
        if (produced) {
            return null;
        }
        produced = true;
        VectorBatch batch;
        while ((batch = child.next()) != null) {
            for (Folder folder : folders) {
                folder.fold(batch);
            }
        }
        return oneRow();
    }

    private VectorBatch oneRow() {
        VectorBatch out = VectorBatch.allocate(schema, 1);
        out.setRowCount(1);
        for (int i = 0; i < folders.length; i++) {
            Object value = folders[i].result();
            if (value == null) {
                out.column(i).setNull(0);
            } else if (schema.type(i) == DataType.DOUBLE) {
                ((DoubleVector) out.column(i)).set(0, (Double) value);
            } else {
                ((LongVector) out.column(i)).set(0, (Long) value);
            }
        }
        return out;
    }

    private Folder[] build() {
        Folder[] built = new Folder[aggregates.size()];
        for (int i = 0; i < built.length; i++) {
            built[i] = folder(aggregates.get(i));
        }
        return built;
    }

    /** Only the shapes with a kernel behind them compile. */
    private Folder folder(Aggregate aggregate) {
        if (aggregate.function() == AggregateFunction.COUNT_STAR) {
            return new RowCount();
        }
        if (aggregate.function() != AggregateFunction.SUM) {
            throw new UnsupportedOperationException("no kernel for " + aggregate.function());
        }
        Schema input = child.schema();
        if (aggregate.resultType(input) != DataType.DOUBLE) {
            throw new UnsupportedOperationException("only double sums have a kernel: " + aggregate);
        }
        Expr expr = aggregate.input();
        if (expr instanceof Expr.ColumnRef ref) {
            return new ColumnSum(doubleColumn(ref, input));
        }
        if (expr instanceof Expr.Binary binary && binary.op() == BinaryOp.MULTIPLY
                && binary.left() instanceof Expr.ColumnRef left
                && binary.right() instanceof Expr.ColumnRef right) {
            return new ProductSum(doubleColumn(left, input), doubleColumn(right, input));
        }
        throw new UnsupportedOperationException("no kernel for SUM(" + expr + ")");
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
        return "VectorAggregate" + aggregates;
    }

    private interface Folder {
        void fold(VectorBatch batch);

        Object result();
    }

    private static final class RowCount implements Folder {
        private long count;

        @Override
        public void fold(VectorBatch batch) {
            count += batch.selectedCount();
        }

        @Override
        public Object result() {
            return count;
        }
    }

    private static final class ColumnSum implements Folder {
        private final int index;
        private final RunningSum state = new RunningSum();

        ColumnSum(int index) {
            this.index = index;
        }

        @Override
        public void fold(VectorBatch batch) {
            DoubleVector column = (DoubleVector) batch.column(index);
            int[] sel = batch.selection();
            if (sel == null) {
                state.addColumn(column.values(), column.validity(), batch.rowCount());
            } else {
                state.addColumn(column.values(), column.validity(), sel, batch.selectedCount());
            }
        }

        @Override
        public Object result() {
            return state.result();
        }
    }

    private static final class ProductSum implements Folder {
        private final int left;
        private final int right;
        private final RunningSum state = new RunningSum();

        ProductSum(int left, int right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public void fold(VectorBatch batch) {
            DoubleVector a = (DoubleVector) batch.column(left);
            DoubleVector b = (DoubleVector) batch.column(right);
            int[] sel = batch.selection();
            if (sel == null) {
                state.addProduct(a.values(), a.validity(), b.values(), b.validity(), batch.rowCount());
            } else {
                state.addProduct(a.values(), a.validity(), b.values(), b.validity(), sel,
                        batch.selectedCount());
            }
        }

        @Override
        public Object result() {
            return state.result();
        }
    }
}
