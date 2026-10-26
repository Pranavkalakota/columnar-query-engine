package columnar.exec.row;

import columnar.exec.agg.Accumulator;
import columnar.exec.agg.Aggregate;
import columnar.expr.RowEvaluator;
import columnar.types.Field;
import columnar.types.Schema;
import java.util.ArrayList;
import java.util.List;

/** Ungrouped aggregation: folds the whole input into exactly one row. */
public final class RowAggregate implements RowOperator {

    private final RowOperator child;
    private final List<Aggregate> aggregates;
    private final RowEvaluator[] inputs;
    private final Schema schema;
    private final ObjectRow out;
    private boolean produced;

    public RowAggregate(RowOperator child, List<Aggregate> aggregates) {
        if (aggregates.isEmpty()) {
            throw new IllegalArgumentException("an aggregate needs at least one function");
        }
        this.child = child;
        this.aggregates = List.copyOf(aggregates);
        this.inputs = new RowEvaluator[aggregates.size()];
        List<Field> fields = new ArrayList<>(aggregates.size());
        for (int i = 0; i < aggregates.size(); i++) {
            Aggregate a = aggregates.get(i);
            inputs[i] = a.input() == null ? null : new RowEvaluator(a.input(), child.schema());
            fields.add(Field.of(a.name(), a.resultType(child.schema())));
        }
        this.schema = Schema.of(fields);
        this.out = new ObjectRow(schema);
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public void open() {
        child.open();
        produced = false;
    }

    /** Drains the child on the first call. */
    @Override
    public Row next() {
        if (produced) {
            return null;
        }
        produced = true;
        Accumulator[] state = new Accumulator[aggregates.size()];
        for (int i = 0; i < state.length; i++) {
            state[i] = Accumulator.create(aggregates.get(i).function(), schema.type(i));
        }
        Row row;
        while ((row = child.next()) != null) {
            for (int i = 0; i < state.length; i++) {
                state[i].accept(inputs[i] == null ? null : inputs[i].evaluate(row));
            }
        }
        for (int i = 0; i < state.length; i++) {
            out.set(i, state[i].result());
        }
        return out;
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public String toString() {
        return "Aggregate" + aggregates;
    }
}
