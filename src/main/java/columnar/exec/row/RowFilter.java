package columnar.exec.row;

import columnar.expr.Expr;
import columnar.expr.RowEvaluator;
import columnar.types.DataType;
import columnar.types.Schema;

/** Passes through only the rows whose predicate is TRUE. */
public final class RowFilter implements RowOperator {

    private final RowOperator child;
    private final Expr predicate;
    private final RowEvaluator evaluator;

    public RowFilter(RowOperator child, Expr predicate) {
        this.child = child;
        this.predicate = predicate;
        this.evaluator = new RowEvaluator(predicate, child.schema());
        if (evaluator.type() != DataType.BOOLEAN) {
            throw new IllegalArgumentException("a filter needs a boolean predicate, got " + evaluator.type());
        }
    }

    @Override
    public Schema schema() {
        return child.schema();
    }

    @Override
    public void open() {
        child.open();
    }

    /** A NULL predicate rejects the row, exactly as FALSE does. */
    @Override
    public Row next() {
        Row row;
        while ((row = child.next()) != null) {
            if (Boolean.TRUE.equals(evaluator.evaluate(row))) {
                return row;
            }
        }
        return null;
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public String toString() {
        return "Filter(" + predicate + ")";
    }
}
