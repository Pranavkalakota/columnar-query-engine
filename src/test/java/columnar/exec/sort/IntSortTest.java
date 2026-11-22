package columnar.exec.sort;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntSortTest {

    /** Java's sort is stable, so ties must match it. */
    private static int[] reference(int[] keys, int n) {
        Integer[] boxed = new Integer[n];
        for (int i = 0; i < n; i++) {
            boxed[i] = i;
        }
        Arrays.sort(boxed, Comparator.comparingInt(i -> keys[i]));
        return Arrays.stream(boxed).mapToInt(Integer::intValue).toArray();
    }

    private static int[] sorted(int[] keys, int n) {
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        IntSort.sort(order, n, (a, b) -> Integer.compare(keys[a], keys[b]));
        return order;
    }

    @Test
    @DisplayName("matches Java's stable sort on 2,000 inputs full of ties")
    void matchesStableReference() {
        Random random = new Random(20261121L);
        for (int trial = 0; trial < 2_000; trial++) {
            int n = random.nextInt(300);
            int distinct = 1 + random.nextInt(20);
            int[] keys = new int[n];
            for (int i = 0; i < n; i++) {
                keys[i] = random.nextInt(distinct);
            }
            assertArrayEquals(reference(keys, n), sorted(keys, n), "trial " + trial);
        }
    }

    @Test
    @DisplayName("sizes either side of the insertion cutoff all sort correctly")
    void aroundTheInsertionCutoff() {
        Random random = new Random(7L);
        for (int n : new int[] {0, 1, 2, 23, 24, 25, 48, 49, 50, 10_000}) {
            int[] keys = random.ints(n, -50, 50).toArray();
            assertArrayEquals(reference(keys, n), sorted(keys, n), "n = " + n);
        }
    }

    @Test
    @DisplayName("already sorted and reversed input both come out right")
    void sortedAndReversed() {
        int n = 5_000;
        int[] ascending = new int[n];
        int[] descending = new int[n];
        for (int i = 0; i < n; i++) {
            ascending[i] = i / 3;
            descending[i] = (n - i) / 3;
        }
        assertArrayEquals(reference(ascending, n), sorted(ascending, n));
        assertArrayEquals(reference(descending, n), sorted(descending, n));
    }

    @Test
    @DisplayName("only the first n entries move")
    void sortsOnlyThePrefix() {
        int[] values = {5, 3, 1, 9, 8};
        IntSort.sort(values, 3, Integer::compare);
        assertArrayEquals(new int[] {1, 3, 5, 9, 8}, values);
        assertThrows(IllegalArgumentException.class, () -> IntSort.sort(values, 6, Integer::compare));
    }
}
