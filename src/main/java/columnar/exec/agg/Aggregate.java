package columnar.exec.agg;

import columnar.expr.Expr;
import columnar.types.DataType;
import columnar.types.Schema;

/** One aggregate to compute: a function, the expression it reads. */
public record Aggregate(AggregateFunction function, Expr input, String name) {

    public Aggregate {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("an aggregate needs an output name");
        }
        if (function.needsInput() == (input == null)) {
            throw new IllegalArgumentException(function + (input == null ? " needs an input" : " takes no input"));
        }
    }

    public static Aggregate countStar(String name) {
        return new Aggregate(AggregateFunction.COUNT_STAR, null, name);
    }

    public static Aggregate count(Expr input, String name) {
        return new Aggregate(AggregateFunction.COUNT, input, name);
    }

    public static Aggregate sum(Expr input, String name) {
        return new Aggregate(AggregateFunction.SUM, input, name);
    }

    public static Aggregate avg(Expr input, String name) {
        return new Aggregate(AggregateFunction.AVG, input, name);
    }

    public static Aggregate min(Expr input, String name) {
        return new Aggregate(AggregateFunction.MIN, input, name);
    }

    public static Aggregate max(Expr input, String name) {
        return new Aggregate(AggregateFunction.MAX, input, name);
    }

    public DataType resultType(Schema schema) {
        return function.resultType(input == null ? DataType.INT64 : input.type(schema));
    }

    @Override
    public String toString() {
        return function + (input == null ? "" : "(" + input + ")") + " AS " + name;
    }
}
