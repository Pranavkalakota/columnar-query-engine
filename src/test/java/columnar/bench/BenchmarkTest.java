package columnar.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BenchmarkTest {

    @Test
    @DisplayName("warmups run but are not measured")
    void warmupsAreDiscarded() {
        AtomicInteger calls = new AtomicInteger();
        Benchmark.Result r = Benchmark.measure("t", 5, 3, calls::incrementAndGet);
        assertEquals(8, calls.get(), "5 warmups plus 3 measured");
        assertEquals(3, r.runs(), "only the measured runs are reported");
    }

    @Test
    @DisplayName("min is at most the median, which is at most the max")
    void orderedTimes() {
        Benchmark.Result r = Benchmark.measure("t", 2, 9, () -> {
            long s = 0;
            for (int i = 0; i < 10_000; i++) {
                s += i;
            }
            return s;
        });
        assertTrue(r.min() <= r.median(), "min " + r.min() + " median " + r.median());
        assertTrue(r.median() <= r.max(), "median " + r.median() + " max " + r.max());
        assertTrue(r.min() > 0, "a timed run takes nonzero time");
    }

    @Test
    @DisplayName("the task result is kept, so the work cannot be optimised away")
    void keepsTheResult() {
        Benchmark.Result r = Benchmark.measure("t", 1, 2, () -> 42L);
        assertEquals(42L, r.last());
    }

    @Test
    @DisplayName("a ratio compares two results")
    void ratio() {
        Benchmark.Result fast = new Benchmark.Result("fast", 1, 1_000_000, 1, 1, null);
        Benchmark.Result slow = new Benchmark.Result("slow", 1, 3_000_000, 1, 1, null);
        assertEquals(3.0, slow.relativeTo(fast), 1e-9);
    }

    @Test
    @DisplayName("zero measured runs is refused")
    void needsAtLeastOneRun() {
        assertThrows(IllegalArgumentException.class, () -> Benchmark.measure("t", 1, 0, () -> 1));
    }

    @Test
    @DisplayName("the environment line names the JVM and core count")
    void environmentIsRecorded() {
        String env = Benchmark.environment();
        assertFalse(env.isBlank());
        assertTrue(env.contains("cores"), env);
        assertTrue(env.contains("heap"), env);
    }

    @Test
    @DisplayName("a result prints its median, min and max")
    void readableToString() {
        String s = new Benchmark.Result("q6", 5, 2_000_000, 1_000_000, 3_000_000, null).toString();
        assertTrue(s.contains("q6") && s.contains("2.00") && s.contains("5 runs"), s);
    }
}
