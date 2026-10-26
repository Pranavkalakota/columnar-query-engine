package columnar.exec.row;

import columnar.expr.Expr;
import columnar.expr.RowEvaluator;
import columnar.types.Field;
import columnar.types.Schema;
import java.util.ArrayList;
import java.util.List;

/** Computes a new row of expressions from each input row. */
public final class RowProject implements RowOperator {

    private final RowOperator child;
    private final List<Expr> expressions;
    private final RowEvaluator[] evaluators;
    private final Schema schema;
    private final ObjectRow out;

    public RowProject(RowOperator child, List<String> names, List<Expr> expressions) {
        if (names.size() != expressions.size()) {
            throw new IllegalArgumentException(
                    names.size() + " names for " + expressions.size() + " expressions");
        }
        this.child = child;
        this.expressions = List.copyOf(expressions);
        this.evaluators = new RowEvaluator[expressions.size()];
        List<Field> fields = new ArrayList<>(expressions.size());
        for (int i = 0; i < expressions.size(); i++) {
            evaluators[i] = new RowEvaluator(expressions.get(i), child.schema());
            fields.add(Field.of(names.get(i), evaluators[i].type()));
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
    }

    /** Reuses one output row, so it is valid only until the next call. */
    @Override
    public Row next() {
        Row row = child.next();
        if (row == null) {
            return null;
        }
        for (int i = 0; i < evaluators.length; i++) {
            out.set(i, evaluators[i].evaluate(row));
        }
        return out;
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public String toString() {
        return "Project" + expressions;
    }
}
