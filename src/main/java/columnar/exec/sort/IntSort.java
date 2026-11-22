package columnar.exec.sort;

/** A stable merge sort over int[], so nothing is boxed. */
public final class IntSort {

    private static final int INSERTION_LIMIT = 24;

    private IntSort() {
    }

    /** Compares two row indexes. */
    @FunctionalInterface
    public interface IntComparator {
        int compare(int a, int b);
    }

    /** Sorts the first n entries in place, stably. */
    public static void sort(int[] values, int n, IntComparator order) {
        if (n < 0 || n > values.length) {
            throw new IllegalArgumentException("cannot sort " + n + " of " + values.length);
        }
        if (n < 2) {
            return;
        }
        mergeSort(values, new int[n], 0, n, order);
    }

    private static void mergeSort(int[] a, int[] scratch, int lo, int hi, IntComparator order) {
        if (hi - lo <= INSERTION_LIMIT) {
            insertionSort(a, lo, hi, order);
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(a, scratch, lo, mid, order);
        mergeSort(a, scratch, mid, hi, order);
        if (order.compare(a[mid - 1], a[mid]) <= 0) {
            return;
        }
        System.arraycopy(a, lo, scratch, lo, hi - lo);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            a[k++] = order.compare(scratch[j], scratch[i]) < 0 ? scratch[j++] : scratch[i++];
        }
        while (i < mid) {
            a[k++] = scratch[i++];
        }
        while (j < hi) {
            a[k++] = scratch[j++];
        }
    }

    /** Shifts only past strictly greater entries, which keeps it stable. */
    private static void insertionSort(int[] a, int lo, int hi, IntComparator order) {
        for (int i = lo + 1; i < hi; i++) {
            int value = a[i];
            int j = i - 1;
            while (j >= lo && order.compare(a[j], value) > 0) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = value;
        }
    }
}
