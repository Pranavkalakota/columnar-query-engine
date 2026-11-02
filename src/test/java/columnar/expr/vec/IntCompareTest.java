package columnar.expr.vec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import columnar.types.Dates;
import columnar.vector.Validity;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntCompareTest {

    /** The obvious scalar version every kernel must agree with. */
    private static int[] reference(int[] v, boolean[] isNull, int[] sel, int n, IntPredicate p) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int r = sel == null ? i : sel[i];
            if (!isNull[r] && p.test(v[r])) {
                out.add(r);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Validity validity(boolean[] isNull) {
        Validity nulls = Validity.allValid(isNull.length);
        for (int i = 0; i < isNull.length; i++) {
            if (isNull[i]) {
                nulls.setNull(i);
            }
        }
        return nulls;
    }

    private static int[] head(int[] a, int n) {
        int[] out = new int[n];
        System.arraycopy(a, 0, out, 0, n);
        return out;
    }

    @Test
    @DisplayName("every kernel matches a scalar reference on 10,000 random cases")
    void matchesScalarReference() {
        Random random = new Random(20261101L);
        for (int trial = 0; trial < 10_000; trial++) {
            int size = 1 + random.nextInt(40);
            int[] v = new int[size];
            boolean[] isNull = new boolean[size];
            for (int i = 0; i < size; i++) {
                v[i] = random.nextInt(40) - 20;
                isNull[i] = random.nextInt(5) == 0;
            }
            Validity nulls = validity(isNull);
            int c = random.nextInt(40) - 20;
            int[] sel = new int[size];
            for (int i = 0; i < size; i++) {
                sel[i] = i;
            }
            int[] out = new int[size];
            String where = "trial " + trial;
            assertArrayEquals(reference(v, isNull, null, size, x -> x >= c),
                    head(out, IntCompare.ge(v, c, nulls, size, out)), where + " dense ge");
            assertArrayEquals(reference(v, isNull, null, size, x -> x < c),
                    head(out, IntCompare.lt(v, c, nulls, size, out)), where + " dense lt");
            assertArrayEquals(reference(v, isNull, sel, size, x -> x >= c),
                    head(out, IntCompare.ge(v, c, nulls, sel, size, out)), where + " ge");
            assertArrayEquals(reference(v, isNull, sel, size, x -> x < c),
                    head(out, IntCompare.lt(v, c, nulls, sel, size, out)), where + " lt");
        }
    }

    @Test
    @DisplayName("negative values compare correctly, so pre-1970 dates work")
    void negativesCompare() {
        Validity nulls = Validity.allValid(4);
        int[] v = {-719162, -1, 0, 8766};
        int[] out = new int[4];
        assertArrayEquals(new int[] {2, 3}, head(out, IntCompare.ge(v, 0, nulls, 4, out)));
        assertArrayEquals(new int[] {0, 1}, head(out, IntCompare.lt(v, 0, nulls, 4, out)));
    }

    @Test
    @DisplayName("a half-open date range narrows like Q6's shipdate clause")
    void dateRangeNarrows() {
        int from = Dates.toEpochDay(1994, 1, 1);
        int to = Dates.toEpochDay(1995, 1, 1);
        int[] v = {from - 1, from, from + 200, to - 1, to, to + 1};
        Validity nulls = Validity.allValid(v.length);
        int[] a = new int[v.length];
        int[] b = new int[v.length];
        int n = IntCompare.ge(v, from, nulls, v.length, a);
        n = IntCompare.lt(v, to, nulls, a, n, b);
        assertArrayEquals(new int[] {1, 2, 3}, head(b, n), "the upper bound is exclusive");
    }

    @Test
    @DisplayName("nulls never survive, and an empty batch yields nothing")
    void nullsAndEmpty() {
        Validity nulls = Validity.allValid(3);
        nulls.setNull(1);
        int[] v = {5, 5, 5};
        int[] out = new int[3];
        assertArrayEquals(new int[] {0, 2}, head(out, IntCompare.ge(v, 5, nulls, 3, out)));
        assertEquals(0, IntCompare.ge(v, 5, nulls, 0, out));
    }
}
