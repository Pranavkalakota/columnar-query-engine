package columnar.differential;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the tester, because a comparator nobody checked proves nothing. */
class ResultComparatorTest {

    private final ResultComparator cmp = new ResultComparator();

    private static ResultTable t(List<List<Object>> rows) {
        return ResultTable.of(List.of("a", "b"), rows);
    }

    @Test
    @DisplayName("a deliberately wrong result is caught")
    void catchesWrongResult() {
        ResultTable right = t(List.of(List.of("A", 10L), List.of("B", 20L)));
        ResultTable wrong = t(List.of(List.of("A", 10L), List.of("B", 21L)));
        assertTrue(cmp.compare(right, wrong, false).isPresent());
        assertTrue(cmp.compare(right, right, false).isEmpty());
    }

    @Test
    @DisplayName("doubles compare with a relative tolerance, not exactly")
    void relativeTolerance() {
        assertTrue(cmp.valuesEqual(1_000_000.0, 1_000_000.0000001));
        assertFalse(cmp.valuesEqual(1.0, 1.01));
    }

    @Test
    @DisplayName("NULL equals NULL, and NULL never equals a value")
    void nullEquality() {
        assertTrue(cmp.valuesEqual(null, null));
        assertFalse(cmp.valuesEqual(null, 0L));
        assertFalse(cmp.valuesEqual(0L, null));
    }

    @Test
    @DisplayName("row order is ignored unless the comparison is ordered")
    void unorderedIgnoresOrder() {
        ResultTable a = t(List.of(List.of("A", 1L), List.of("B", 2L)));
        ResultTable b = t(List.of(List.of("B", 2L), List.of("A", 1L)));
        assertTrue(cmp.compare(a, b, false).isEmpty());
        assertTrue(cmp.compare(a, b, true).isPresent());
    }
}
