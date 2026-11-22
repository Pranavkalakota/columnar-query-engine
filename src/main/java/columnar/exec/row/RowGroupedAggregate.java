package columnar.exec.row;

import columnar.exec.agg.Accumulator;
import columnar.exec.agg.Aggregate;
import columnar.expr.RowEvaluator;
import columnar.types.Field;
import columnar.types.Schema;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Row-at-a-time GROUP BY, the vector engine's baseline. */
public final class RowGroupedAggregate implements RowOperator {

    private final RowOperator child;
    private final List<String> keyNames;
    private final List<Aggregate> aggregates;
    private final int[] keyColumns;
    private final RowEvaluator[] inputs;
    private final Schema schema;
    private final ObjectRow out;
    private final Map<List<Object>, Accumulator[]> groups = new LinkedHashMap<>();
    private Iterator<Map.Entry<List<Object>, Accumulator[]>> cursor;
    private boolean drained;

    public RowGroupedAggregate(RowOperator child, List<String> keyNames, List<Aggregate> aggregates) {
        if (aggregates.isEmpty()) {
            throw new IllegalArgumentException("an aggregate needs at least one function");
        }
        if (keyNames.isEmpty()) {
            throw new IllegalArgumentException("grouping needs at least one key column");
        }
        this.child = child;
        this.keyNames = List.copyOf(keyNames);
        this.aggregates = List.copyOf(aggregates);
        Schema input = child.schema();
        this.keyColumns = new int[this.keyNames.size()];
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < keyColumns.length; i++) {
            keyColumns[i] = input.requireIndexOf(this.keyNames.get(i));
            fields.add(input.field(keyColumns[i]));
        }
        this.inputs = new RowEvaluator[this.aggregates.size()];
        for (int i = 0; i < inputs.length; i++) {
            Aggregate aggregate = this.aggregates.get(i);
            inputs[i] = aggregate.input() == null ? null : new RowEvaluator(aggregate.input(), input);
            fields.add(Field.of(aggregate.name(), aggregate.resultType(input)));
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
        groups.clear();
        cursor = null;
        drained = false;
    }

    /** Drains the child, then walks the groups. */
    @Override
    public Row next() {
        if (!drained) {
            drain();
            drained = true;
            cursor = groups.entrySet().iterator();
        }
        if (!cursor.hasNext()) {
            return null;
        }
        Map.Entry<List<Object>, Accumulator[]> group = cursor.next();
        for (int i = 0; i < keyColumns.length; i++) {
            out.set(i, group.getKey().get(i));
        }
        Accumulator[] state = group.getValue();
        for (int i = 0; i < state.length; i++) {
            out.set(keyColumns.length + i, state[i].result());
        }
        return out;
    }

    /** A boxed key per row, as the baseline should. */
    private void drain() {
        Row row;
        while ((row = child.next()) != null) {
            List<Object> key = new ArrayList<>(keyColumns.length);
            for (int i = 0; i < keyColumns.length; i++) {
                key.add(RowValues.read(row, keyColumns[i], schema.type(i)));
            }
            Accumulator[] state = groups.computeIfAbsent(key, k -> newState());
            for (int i = 0; i < state.length; i++) {
                state[i].accept(inputs[i] == null ? null : inputs[i].evaluate(row));
            }
        }
    }

    private Accumulator[] newState() {
        Accumulator[] state = new Accumulator[aggregates.size()];
        for (int i = 0; i < state.length; i++) {
            state[i] = Accumulator.create(aggregates.get(i).function(),
                    schema.type(keyColumns.length + i));
        }
        return state;
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public String toString() {
        return "RowGroupedAggregate(by " + keyNames + ", " + aggregates + ")";
    }
}
