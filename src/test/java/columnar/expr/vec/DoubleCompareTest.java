package columnar.expr.vec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import columnar.vector.Validity;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoublePredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DoubleCompareTest {

    /** The obvious scalar version every kernel must agree with. */
    private static int[] reference(double[] v, boolean[] isNull, int[] sel, int n, DoublePredicate p) {
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
            double[] v = new double[size];
            boolean[] isNull = new boolean[size];
            for (int i = 0; i < size; i++) {
                v[i] = random.nextInt(20) / 4.0;
                isNull[i] = random.nextInt(5) == 0;
            }
            Validity nulls = validity(isNull);
            double c = random.nextInt(20) / 4.0;
            int[] sel = new int[size];
            for (int i = 0; i < size; i++) {
                sel[i] = i;
            }
            int[] out = new int[size];
            String where = "trial " + trial;
            assertArrayEquals(reference(v, isNull, null, size, x -> x < c),
                    head(out, DoubleCompare.lt(v, c, nulls, size, out)), where + " dense lt");
            assertArrayEquals(reference(v, isNull, sel, size, x -> x < c),
                    head(out, DoubleCompare.lt(v, c, nulls, sel, size, out)), where + " lt");
            assertArrayEquals(reference(v, isNull, sel, size, x -> x <= c),
                    head(out, DoubleCompare.le(v, c, nulls, sel, size, out)), where + " le");
            assertArrayEquals(reference(v, isNull, sel, size, x -> x >= c),
                    head(out, DoubleCompare.ge(v, c, nulls, sel, size, out)), where + " ge");
        }
    }

    @Test
    @DisplayName("an empty batch survives, and a single row works either way")
    void emptyAndSingle() {
        Validity nulls = Validity.allValid(1);
        double[] v = {1.5};
        int[] out = new int[1];
        assertEquals(0, DoubleCompare.lt(v, 2.0, nulls, 0, out), "no rows means no survivors");
        assertEquals(1, DoubleCompare.lt(v, 2.0, nulls, 1, out));
        assertEquals(0, out[0]);
        assertEquals(0, DoubleCompare.lt(v, 1.0, nulls, 1, out));
    }

    @Test
    @DisplayName("nulls never survive a comparison, whichever way it points")
    void nullsNeverSurvive() {
        Validity nulls = Validity.allValid(4);
        nulls.setNull(1);
        nulls.setNull(3);
        double[] v = {1.0, 1.0, 2.0, 2.0};
        int[] sel = {0, 1, 2, 3};
        int[] out = new int[4];
        assertArrayEquals(new int[] {0, 2}, head(out, DoubleCompare.ge(v, 1.0, nulls, sel, 4, out)));
        assertArrayEquals(new int[] {0, 2}, head(out, DoubleCompare.le(v, 2.0, nulls, sel, 4, out)));
        assertEquals(2, DoubleCompare.lt(v, 9.0, nulls, 4, out), "dense path drops nulls too");
    }

    @Test
    @DisplayName("chained kernels narrow the selection, which is how AND works")
    void chainingNarrows() {
        Validity nulls = Validity.allValid(8);
        double[] v = {0.01, 0.05, 0.06, 0.07, 0.08, 0.05, 0.09, 0.07};
        int[] a = new int[8];
        int[] b = new int[8];
        int n = DoubleCompare.ge(v, 0.05, nulls, new int[] {0, 1, 2, 3, 4, 5, 6, 7}, 8, a);
        n = DoubleCompare.le(v, 0.07, nulls, a, n, b);
        assertArrayEquals(new int[] {1, 2, 3, 5, 7}, head(b, n), "0.05 to 0.07 inclusive");
    }

    @Test
    @DisplayName("NaN fails every comparison, so it never survives")
    void nanNeverSurvives() {
        Validity nulls = Validity.allValid(2);
        double[] v = {Double.NaN, 1.0};
        int[] out = new int[2];
        assertArrayEquals(new int[] {1}, head(out, DoubleCompare.lt(v, 9.0, nulls, 2, out)));
        assertArrayEquals(new int[] {1}, head(out, DoubleCompare.ge(v, 0.0, nulls, new int[] {0, 1}, 2, out)));
    }
}
