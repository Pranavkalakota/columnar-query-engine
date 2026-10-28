# Benchmarks

Every number here comes from a run of `columnar.bench.Q6Benchmark` on the
hardware named below. Nothing is estimated, and nothing is quoted without the
method that produced it.

## Method

- The table is loaded into memory **once, before timing starts**, so these
  measure execution and not file parsing.
- 5 warmup runs, discarded, so HotSpot has compiled the hot paths before
  anything is recorded.
- 9 measured runs; the median is reported, with min and max so variance is
  visible rather than hidden.
- The benchmark refuses to print a result if the two engines disagree on the
  answer, so a faster-but-wrong engine cannot be reported as a win.
- Fixed heap (`-Xms4g -Xmx4g -XX:+AlwaysPreTouch`) to keep GC out of the
  comparison.

Reproduce with:

```
./benchmarks/scripts/gen_tpch.sh 0.1
mvn -q test-compile
java -Xms4g -Xmx4g -XX:+AlwaysPreTouch \
  -cp target/classes:target/test-classes \
  columnar.bench.Q6Benchmark data/sf0.1 5 9
```

## TPC-H Q6, scale factor 0.1

600,572 lineitem rows held in memory, single thread, hot cache.
Apple M4 Pro, 12 cores, 24 GB; OpenJDK 21.0.12.1.

| Engine | median | min | max |
|---|---:|---:|---:|
| `ROW_BOXED` | 129.11 ms | 107.93 | 147.98 |
| `ROW_TUNED` | 25.60 ms | 25.44 | 28.96 |

**`ROW_TUNED` is about 5x faster than `ROW_BOXED`.** Two independent runs gave
5.04x and 5.35x; the lower figure is the one to quote.

Both engines return 11,803,420.2534, which matches DuckDB reading the same
data to within 1.9e-15 relative. That gap is the order in which doubles are
summed, not a defect.

## What this does and does not show

It shows the cost of **boxing and per-row allocation**. Both engines here are
row-at-a-time Volcano pipelines running the identical plan; the only
difference is that `ROW_BOXED` allocates an `Object[]` row and boxes every
value, while `ROW_TUNED` reads primitives straight out of the column vectors
through a reused cursor.

It is **not** the vectorized speedup, because there is no vectorized engine
yet. When there is, the number worth reporting is vector against `ROW_TUNED`,
which is the harder and more honest comparison. Beating only the boxed engine
would flatter the result.

Not yet measured: any scale factor above 0.1, cold cache, multiple threads, a
comparison against DuckDB's own execution time, or anything with a `GROUP BY`.
