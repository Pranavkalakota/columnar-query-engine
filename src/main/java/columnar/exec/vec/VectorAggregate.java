package columnar.exec.vec;

import columnar.exec.agg.Aggregate;
import columnar.exec.agg.AggregateFunction;
import columnar.expr.vec.DoubleCompiler;
import columnar.expr.vec.DoubleKernel;
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
        AggregateFunction function = aggregate.function();
        if (function == AggregateFunction.COUNT_STAR) {
            return new RowCount();
        }
        if (function != AggregateFunction.SUM && function != AggregateFunction.AVG) {
            throw new UnsupportedOperationException("no kernel for " + function);
        }
        Schema input = child.schema();
        if (aggregate.resultType(input) != DataType.DOUBLE) {
            throw new UnsupportedOperationException("only double results have a kernel: " + aggregate);
        }
        return new ExpressionSum(DoubleCompiler.compile(aggregate.input(), input),
                function == AggregateFunction.AVG);
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

    /** SUM or AVG over any compiled double expression. */
    private static final class ExpressionSum implements Folder {
        private final DoubleKernel kernel;
        private final boolean mean;
        private final RunningSum state = new RunningSum();
        private double[] values = new double[0];
        private boolean[] valid = new boolean[0];

        ExpressionSum(DoubleKernel kernel, boolean mean) {
            this.kernel = kernel;
            this.mean = mean;
        }

        @Override
        public void fold(VectorBatch batch) {
            int rows = batch.selectedCount();
            if (values.length < rows) {
                values = new double[rows];
                valid = new boolean[rows];
            }
            boolean all = kernel.evaluate(batch, batch.selection(), rows, values, valid);
            state.addValues(values, valid, all, rows);
        }

        @Override
        public Object result() {
            return mean ? state.mean() : state.result();
        }
    }
}
