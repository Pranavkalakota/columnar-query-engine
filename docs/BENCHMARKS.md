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
- Each engine settles the heap before its warmups, for the reason below.

Reproduce with:

```
./benchmarks/scripts/gen_tpch.sh 0.1
mvn -q test-compile
java -Xms4g -Xmx4g -XX:+AlwaysPreTouch \
  -cp target/classes:target/test-classes \
  columnar.bench.Q6Benchmark data/sf0.1 8 15
# or columnar.bench.Q1Benchmark data/sf0.1 10 21
```

## The harness settles between engines, and it mattered

Engines are timed one after another in the same JVM. Until 2026-11-18 nothing
cleared the heap between them, and on Q1 that made the numbers meaningless: the
row engines allocate a boxed key per row, so by the time the vectorized engine
was timed it had inherited the garbage of roughly eighteen million objects.

Eleven runs of Q1 under the old harness gave speedups from **1.6x to 4.8x**,
with `ROW_TUNED` itself swinging between 86 ms and 166 ms. The spread was in
the measurement, not the engines. Adding a `System.gc()` and a short pause
before each engine's warmups collapsed it: six runs then gave 2.92x to 3.19x,
with `ROW_TUNED` varying by 1.4%.

**Every number below was measured with the settle in place.** The Q6 figures
are therefore lower than the ones published on 2026-11-02, which claimed 14.7x
over `ROW_TUNED`. That earlier figure was measured without it, and the
vectorized engine, timed last, benefited from a heap the row engines had
already warmed. The honest figure is the one here. A number that moves when
the harness is fixed was never the engine's number.

## TPC-H Q6, scale factor 0.1

600,572 lineitem rows held in memory, single thread, hot cache.
Apple M4 Pro, 12 cores, 24 GB; OpenJDK 21.0.12.1.
8 warmups discarded, 15 measured runs, all three engines in one process.

| Engine | median | min | max |
|---|---:|---:|---:|
| `ROW_BOXED` | 98.34 ms | 96.53 | 135.43 |
| `ROW_TUNED` | 25.71 ms | 25.03 | 26.62 |
| `VECTOR` | 2.08 ms | 1.79 | 2.21 |

**`VECTOR` is 12.4x faster than `ROW_TUNED`, and 47.4x faster than
`ROW_BOXED`.** Four settled runs gave 12.38x, 12.42x, 12.84x and 12.98x; the
lowest is the one to quote.

All three engines return exactly 11,803,420.2534 — the same `double`, bit for
bit, because the vectorized sum folds in the same order the row engine does.
The benchmark refuses to print a result when they disagree, and `TpchQ6Test`
checks the same query against DuckDB.

## TPC-H Q1, scale factor 0.1

Every column of Q1, including `sum(l_extendedprice * (1 - l_discount) *
(1 + l_tax))`, grouped on `l_returnflag` and `l_linestatus`. No `ORDER BY`,
because there is no sort operator yet. 10 warmups, 21 measured runs.

| Engine | median | min | max |
|---|---:|---:|---:|
| `ROW_BOXED` | 144.60 ms | 143.08 | 148.34 |
| `ROW_TUNED` | 87.90 ms | 87.11 | 90.71 |
| `VECTOR` | 28.72 ms | 27.75 | 29.04 |

**`VECTOR` is 2.9x faster than `ROW_TUNED`, and 5.0x faster than `ROW_BOXED`.**
Seven settled runs ranged 2.92x to 3.19x; the lowest is the one to quote. All
three engines produce identical output for all four groups.

## Why Q1 gains 2.9x where Q6 gains 12.4x

This is the most useful number in the file, because it shows where
vectorization stops helping.

**Selectivity.** Q6 keeps 11,618 of 600,572 rows, 1.9%. Q1 keeps 591,856, or
98.5% — both measured, not estimated. Q6's kernel chain throws away almost
everything on the first comparison, so the aggregate runs over a short
selection vector. Q1's filter does nothing useful, and every surviving row
reaches the aggregate.

**Grouping is per-row in both engines.** Each of Q1's 591,856 surviving rows
needs its group looked up in a hash table. Batching does not remove that probe;
it only makes the work around it cheaper. The vectorized engine still wins,
because the key compare reads bytes instead of boxed objects and the aggregates
fold over primitive arrays, but it cannot vectorize the probe itself.

**Q1 does more real arithmetic.** Eight aggregates, two of them over
multi-operand expressions, are work both engines must do.

So 12.4x is what vectorization buys on a selective scan-and-aggregate, and 2.9x
is what it buys when the filter passes nearly everything and the query is
dominated by hashing. Quoting the first figure alone would describe the
friendlier half of the engine.

## What this does and does not show

It shows what **batch-at-a-time execution with compiled kernels** buys over a
row-at-a-time interpreter reading the same columnar storage. The plan, the
data and the predicate are identical; only the execution model changes.

Neither figure is attributable to SIMD alone. `ROW_TUNED` walks the expression
tree once per row and boxes every intermediate value the interpreter produces,
so the gap also includes the interpretation and allocation that batching
removes. Separating those two effects would need a fourth engine that compiles
per-row code, which does not exist here. "12.4x from vectorized execution over
a row-at-a-time interpreter" is what was measured; "12x from vectorization"
would overstate it.

Two more things help the vectorized engine on Q6, and are worth naming:

- Selections narrow in place; a filter never copies a column.
- `lineitem` has no nulls, so every kernel takes its no-null fast path.

Not yet measured: any scale factor above 0.1, cold cache, multiple threads, a
comparison against DuckDB's own execution time, or any query with a join.
