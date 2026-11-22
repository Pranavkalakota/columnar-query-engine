package columnar.exec.sort;

import java.util.Arrays;

/** Value comparisons that match DuckDB's ORDER BY. */
public final class Ordering {

    private Ordering() {
    }

    /** NaN sorts greatest, and -0.0 equals 0.0. */
    public static int compareDoubles(double a, double b) {
        if (a < b) {
            return -1;
        }
        if (a > b) {
            return 1;
        }
        if (a == b) {
            return 0;
        }
        return Boolean.compare(Double.isNaN(a), Double.isNaN(b));
    }

    /** Code point order, not UTF-16 order like compareTo. */
    public static int compareStrings(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }

    /** Unsigned UTF-8 byte order, which is code point order. */
    public static int compareUtf8(byte[] a, int aFrom, int aTo, byte[] b, int bFrom, int bTo) {
        return Integer.signum(Arrays.compareUnsigned(a, aFrom, aTo, b, bFrom, bTo));
    }
}
