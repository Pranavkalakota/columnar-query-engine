package columnar.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** Times a task after warmup, reporting median, min and max. */
public final class Benchmark {

    private Benchmark() {
    }

    /** One measured task. Times are nanoseconds. */
    public record Result(String name, int runs, long median, long min, long max, Object last) {

        public double medianMillis() {
            return median / 1_000_000.0;
        }

        /** How many times slower this is than another result. */
        public double relativeTo(Result faster) {
            return (double) median / faster.median();
        }

        @Override
        public String toString() {
            return "%-22s median %8.2f ms   min %8.2f   max %8.2f   (%d runs)"
                    .formatted(name, medianMillis(), min / 1_000_000.0, max / 1_000_000.0, runs);
        }
    }

    /** Runs warmups, discards them, then times each run. */
    public static Result measure(String name, int warmups, int runs, Supplier<?> task) {
        if (runs < 1) {
            throw new IllegalArgumentException("need at least one measured run");
        }
        Object sink = null;
        for (int i = 0; i < warmups; i++) {
            sink = task.get();
        }
        List<Long> times = new ArrayList<>(runs);
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            sink = task.get();
            times.add(System.nanoTime() - start);
        }
        Collections.sort(times);
        return new Result(name, runs, times.get(times.size() / 2), times.get(0),
                times.get(times.size() - 1), sink);
    }

    /** Hardware and JVM, recorded beside every number. */
    public static String environment() {
        Runtime r = Runtime.getRuntime();
        return "%s %s, %d cores, max heap %d MB".formatted(
                System.getProperty("java.vm.name"), System.getProperty("java.version"),
                r.availableProcessors(), r.maxMemory() / (1024 * 1024));
    }
}
