package columnar.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BinaryOpTest {

    @Test
    @DisplayName("every operator belongs to exactly one category, so dispatch cannot miss one")
    void categoriesPartitionEveryOperator() {
        for (BinaryOp op : BinaryOp.values()) {
            int categories = (op.isArithmetic() ? 1 : 0) + (op.isComparison() ? 1 : 0) + (op.isLogical() ? 1 : 0);
            assertEquals(1, categories, op + " should be in exactly one category");
        }
    }

    @Test
    @DisplayName("the categories hold the operators you would expect")
    void categoryMembership() {
        assertTrue(BinaryOp.ADD.isArithmetic());
        assertTrue(BinaryOp.DIVIDE.isArithmetic());
        assertTrue(BinaryOp.LESS_OR_EQUAL.isComparison());
        assertTrue(BinaryOp.NOT_EQUAL.isComparison());
        assertTrue(BinaryOp.AND.isLogical());
        assertTrue(BinaryOp.OR.isLogical());
    }

    @Test
    @DisplayName("symbols are distinct, so a printed plan is unambiguous")
    void symbolsAreDistinct() {
        long distinct = Stream.of(BinaryOp.values()).map(BinaryOp::toString).distinct().count();
        assertEquals(BinaryOp.values().length, distinct, Arrays.toString(BinaryOp.values()));
    }
}
