# Benchmarks

Every number here comes from a run of `columnar.bench.Q6Benchmark` on the
hardware named below. Nothing is estimated, and nothing is quoted without the
method that produced it.

## Method

- The table is loaded into memory **once, before timing starts**.
- 8 warmup runs, discarded, so HotSpot has compiled the hot paths.
- 15 measured runs; the median is reported, with min and max.
- The benchmark refuses to print a result if any two engines disagree.
- Fixed heap (`-Xms4g -Xmx4g -XX:+AlwaysPreTouch`) to keep GC out.

Reproduce with:

```
./benchmarks/scripts/gen_tpch.sh 0.1
mvn -q test-compile
java -Xms4g -Xmx4g -XX:+AlwaysPreTouch \
  -cp target/classes:target/test-classes \
  columnar.bench.Q6Benchmark data/sf0.1 8 15
```

## TPC-H Q6, scale factor 0.1

600,572 lineitem rows held in memory, single thread, hot cache.
Apple M4 Pro, 12 cores, 24 GB; OpenJDK 21.0.12.1.
8 warmups discarded, 15 measured runs, all three engines in one process.

| Engine | median | min | max |
|---|---:|---:|---:|
| `ROW_BOXED` | 94.27 ms | 93.34 | 95.84 |
| `ROW_TUNED` | 24.33 ms | 23.97 | 25.26 |
| `VECTOR` | 1.66 ms | 1.56 | 1.77 |

**`VECTOR` is 14.7x faster than `ROW_TUNED`, and 56.8x faster than
`ROW_BOXED`.** An earlier run of the same build gave 14.1x and 52.4x; the
lower figures are the ones to quote.

All three engines return exactly 11,803,420.2534 — the same `double`, bit for
bit, because the vectorized sum folds in the same order the row engine does.
That is checked in the benchmark itself, which refuses to print a result when
the engines disagree, and again in `TpchQ6Test` against DuckDB reading the
same data.

## TPC-H Q6, scale factor 0.01

60,175 rows, same machine and method.

| Engine | median | min | max |
|---|---:|---:|---:|
| `ROW_BOXED` | 10.21 ms | 9.32 | 13.00 |
| `ROW_TUNED` | 2.53 ms | 2.50 | 2.59 |
| `VECTOR` | 0.13 ms | 0.12 | 0.33 |

`VECTOR` pulls further ahead here (18.8x over `ROW_TUNED`) because the whole
table fits in cache. SF0.1 is the figure to quote, being the less flattering
of the two.

## What this does and does not show

It shows what **batch-at-a-time execution with compiled kernels** buys over a
row-at-a-time interpreter reading the same columnar storage. The plan, the
data and the predicate are identical; only the execution model changes.

The 14.7x is not attributable to SIMD alone. `ROW_TUNED` walks the expression
tree once per row and boxes every intermediate value the interpreter
produces, so the gap also includes the interpretation and allocation that
batching removes. Separating those two effects would need a third engine
that compiles per-row code, which does not exist here. Quoting this as "14x
from vectorization" would overstate it; "14x from vectorized execution over a
row-at-a-time interpreter" is what was measured.

Three further things help the vector engine, and are worth naming:

- Q6 keeps only about 2% of rows, so the chain runs over a short selection.
- Selections narrow in place; a filter never copies a column.
- `lineitem` has no nulls, so every kernel takes its no-null fast path.

Not yet measured: any scale factor above 0.1, cold cache, multiple threads, a
comparison against DuckDB's own execution time, or anything with a `GROUP BY`.
