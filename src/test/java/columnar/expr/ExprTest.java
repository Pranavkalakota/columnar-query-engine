package columnar.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import columnar.types.DataType;
import columnar.types.Dates;
import columnar.types.Field;
import columnar.types.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExprTest {

    private static final Schema SCHEMA = Schema.of(
            Field.of("i", DataType.INT32), Field.of("l", DataType.INT64),
            Field.of("d", DataType.DOUBLE), Field.of("s", DataType.VARCHAR),
            Field.of("day", DataType.DATE), Field.of("b", DataType.BOOLEAN));

    private static DataType typeOf(Expr e) {
        return e.type(SCHEMA);
    }

    @Test
    @DisplayName("a column takes its type from the schema and fails loudly when absent")
    void columnTypes() {
        assertEquals(DataType.INT32, typeOf(Expr.col("i")));
        assertEquals(DataType.DATE, typeOf(Expr.col("day")));
        assertEquals(DataType.INT32, typeOf(Expr.col("I")), "lookup is case-insensitive");
        assertThrows(IllegalArgumentException.class, () -> typeOf(Expr.col("nope")));
        assertThrows(IllegalArgumentException.class, () -> Expr.col(" "));
    }

    @Test
    @DisplayName("literals carry their own type, including a typed NULL")
    void literalTypes() {
        assertEquals(DataType.INT64, typeOf(Expr.lit(5L)));
        assertEquals(DataType.DOUBLE, typeOf(Expr.lit(1.5)));
        assertEquals(DataType.VARCHAR, typeOf(Expr.lit("x")));
        assertEquals(DataType.BOOLEAN, typeOf(Expr.lit(true)));
        assertEquals(DataType.DATE, typeOf(Expr.date("1994-01-01")));
        assertEquals(Dates.toEpochDay(1994, 1, 1), Expr.date("1994-01-01").value());
        assertNull(Expr.nul(DataType.INT32).value());
        assertEquals(DataType.INT32, typeOf(Expr.nul(DataType.INT32)));
    }

    @Test
    @DisplayName("arithmetic promotes like DataType.widen, and division is always DOUBLE")
    void arithmeticTypes() {
        assertEquals(DataType.INT64, typeOf(Expr.col("i").binary(BinaryOp.ADD, Expr.col("l"))));
        assertEquals(DataType.DOUBLE, typeOf(Expr.col("i").binary(BinaryOp.MULTIPLY, Expr.col("d"))));
        assertEquals(DataType.INT32, typeOf(Expr.col("i").binary(BinaryOp.SUBTRACT, Expr.col("i"))));
        assertEquals(DataType.DOUBLE, typeOf(Expr.col("i").binary(BinaryOp.DIVIDE, Expr.col("i"))));
    }

    @Test
    @DisplayName("comparisons yield BOOLEAN and allow any two numerics")
    void comparisonTypes() {
        assertEquals(DataType.BOOLEAN, typeOf(Expr.col("i").binary(BinaryOp.LESS, Expr.lit(24L))));
        assertEquals(DataType.BOOLEAN, typeOf(Expr.col("d").binary(BinaryOp.GREATER_OR_EQUAL, Expr.col("i"))));
        assertEquals(DataType.BOOLEAN,
                typeOf(Expr.col("day").binary(BinaryOp.LESS, Expr.date("1995-01-01"))));
        assertThrows(IllegalArgumentException.class,
                () -> typeOf(Expr.col("s").binary(BinaryOp.LESS, Expr.col("i"))));
        assertThrows(IllegalArgumentException.class,
                () -> typeOf(Expr.col("day").binary(BinaryOp.LESS, Expr.col("i"))));
    }

    @Test
    @DisplayName("AND and OR demand booleans, and a date is still not a number")
    void logicalAndDateRules() {
        assertEquals(DataType.BOOLEAN, typeOf(Expr.col("b").binary(BinaryOp.AND, Expr.lit(true))));
        assertThrows(IllegalArgumentException.class,
                () -> typeOf(Expr.col("b").binary(BinaryOp.OR, Expr.col("i"))));
        assertThrows(IllegalArgumentException.class,
                () -> typeOf(Expr.col("day").binary(BinaryOp.MULTIPLY, Expr.lit(2L))));
    }

    @Test
    @DisplayName("a tree prints readably, which EXPLAIN will need")
    void readableToString() {
        Expr e = Expr.col("i").binary(BinaryOp.LESS, Expr.lit(24L))
                .binary(BinaryOp.AND, Expr.col("d").binary(BinaryOp.GREATER, Expr.lit(0.05)));
        assertEquals("((i < 24) AND (d > 0.05))", e.toString());
    }
}
