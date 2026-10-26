package columnar.exec.agg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AggregateTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("i", DataType.INT32), Field.of("d", DataType.DOUBLE),
            Field.of("s", DataType.VARCHAR));

    @Test
    @DisplayName("counting always yields INT64 and never NULL, even over no rows")
    void countTypes() {
        assertEquals(DataType.INT64, Aggregate.countStar("n").resultType(SCHEMA));
        assertEquals(DataType.INT64, Aggregate.count(Expr.col("i"), "n").resultType(SCHEMA));
        assertTrue(AggregateFunction.COUNT_STAR.countsRows());
        assertTrue(AggregateFunction.COUNT.countsRows());
        assertFalse(AggregateFunction.SUM.countsRows());
    }

    @Test
    @DisplayName("SUM widens integers to INT64 and leaves DOUBLE alone")
    void sumWidens() {
        assertEquals(DataType.INT64, Aggregate.sum(Expr.col("i"), "s").resultType(SCHEMA));
        assertEquals(DataType.DOUBLE, Aggregate.sum(Expr.col("d"), "s").resultType(SCHEMA));
    }

    @Test
    @DisplayName("AVG is DOUBLE even over integers, and MIN keeps the input type")
    void avgAndMinMaxTypes() {
        assertEquals(DataType.DOUBLE, Aggregate.avg(Expr.col("i"), "a").resultType(SCHEMA));
        assertEquals(DataType.DOUBLE, Aggregate.avg(Expr.col("d"), "a").resultType(SCHEMA));
        assertEquals(DataType.INT32, Aggregate.min(Expr.col("i"), "m").resultType(SCHEMA));
        assertEquals(DataType.VARCHAR, Aggregate.max(Expr.col("s"), "m").resultType(SCHEMA));
    }

    @Test
    @DisplayName("the type comes from the expression, not just a bare column")
    void typeFollowsTheExpression() {
        Expr product = Expr.col("i").binary(BinaryOp.MULTIPLY, Expr.col("d"));
        assertEquals(DataType.DOUBLE, Aggregate.sum(product, "revenue").resultType(SCHEMA));
    }

    @Test
    @DisplayName("COUNT(*) takes no input and everything else requires one")
    void inputRules() {
        assertFalse(AggregateFunction.COUNT_STAR.needsInput());
        assertTrue(AggregateFunction.SUM.needsInput());
        assertThrows(IllegalArgumentException.class,
                () -> new Aggregate(AggregateFunction.COUNT_STAR, Expr.col("i"), "n"));
        assertThrows(IllegalArgumentException.class,
                () -> new Aggregate(AggregateFunction.SUM, null, "s"));
    }

    @Test
    @DisplayName("an output name is required, and SUM over a non-numeric is refused")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> Aggregate.countStar(" "));
        assertThrows(IllegalArgumentException.class,
                () -> Aggregate.sum(Expr.col("s"), "s").resultType(SCHEMA));
    }

    @Test
    @DisplayName("aggregates print readably, which EXPLAIN will need")
    void readableToString() {
        assertEquals("COUNT(*) AS n", Aggregate.countStar("n").toString());
        assertEquals("SUM(d) AS total", Aggregate.sum(Expr.col("d"), "total").toString());
    }
}
