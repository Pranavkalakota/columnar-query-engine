package columnar.exec.agg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import columnar.types.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccumulatorTest {

    private static Accumulator feed(AggregateFunction function, DataType type, Object... values) {
        Accumulator acc = Accumulator.create(function, type);
        for (Object v : values) {
            acc.accept(v);
        }
        return acc;
    }

    @Test
    @DisplayName("over no rows, counting gives 0 while everything else gives NULL")
    void emptyInput() {
        assertEquals(0L, feed(AggregateFunction.COUNT_STAR, DataType.INT64).result());
        assertEquals(0L, feed(AggregateFunction.COUNT, DataType.INT64).result());
        assertNull(feed(AggregateFunction.SUM, DataType.INT64).result());
        assertNull(feed(AggregateFunction.SUM, DataType.DOUBLE).result());
        assertNull(feed(AggregateFunction.AVG, DataType.DOUBLE).result());
        assertNull(feed(AggregateFunction.MIN, DataType.INT64).result());
        assertNull(feed(AggregateFunction.MAX, DataType.INT64).result());
    }

    @Test
    @DisplayName("all-NULL input is the same as no input, except COUNT(*) still counts the rows")
    void allNullInput() {
        assertEquals(3L, feed(AggregateFunction.COUNT_STAR, DataType.INT64, null, null, null).result());
        assertEquals(0L, feed(AggregateFunction.COUNT, DataType.INT64, null, null, null).result());
        assertNull(feed(AggregateFunction.SUM, DataType.INT64, null, null).result());
        assertNull(feed(AggregateFunction.AVG, DataType.DOUBLE, null, null).result());
    }

    @Test
    @DisplayName("SUM skips NULLs and accumulates past the int range")
    void sumSkipsNullsAndWidens() {
        assertEquals(6L, feed(AggregateFunction.SUM, DataType.INT64, 1, null, 2, 3).result());
        long big = 2_000_000_000L;
        assertEquals(big * 3,
                feed(AggregateFunction.SUM, DataType.INT64, big, big, big).result(),
                "summing ints must not overflow an int");
        assertEquals(4.0, feed(AggregateFunction.SUM, DataType.DOUBLE, 1.5, null, 2.5).result());
    }

    @Test
    @DisplayName("AVG divides by the non-null count, not the row count")
    void averageIgnoresNulls() {
        assertEquals(2.0, feed(AggregateFunction.AVG, DataType.DOUBLE, 1.0, null, 3.0).result());
        assertEquals(2.0, feed(AggregateFunction.AVG, DataType.DOUBLE, 1, 2, 3).result(),
                "integers average as a double");
    }

    @Test
    @DisplayName("MIN and MAX work on numbers and on strings")
    void extremes() {
        assertEquals(1L, feed(AggregateFunction.MIN, DataType.INT64, 3L, null, 1L, 2L).result());
        assertEquals(3L, feed(AggregateFunction.MAX, DataType.INT64, 3L, null, 1L, 2L).result());
        assertEquals(-2.5, feed(AggregateFunction.MIN, DataType.DOUBLE, 1.5, -2.5, 0.0).result());
        assertEquals("R", feed(AggregateFunction.MAX, DataType.VARCHAR, "A", "R", "N").result());
        assertEquals("A", feed(AggregateFunction.MIN, DataType.VARCHAR, "A", "R", "N").result());
    }

    @Test
    @DisplayName("SUM picks a long or double accumulator from its result type")
    void sumPicksByResultType() {
        assertInstanceOf(Accumulator.LongSum.class,
                Accumulator.create(AggregateFunction.SUM, DataType.INT64));
        assertInstanceOf(Accumulator.DoubleSum.class,
                Accumulator.create(AggregateFunction.SUM, DataType.DOUBLE));
    }
}
