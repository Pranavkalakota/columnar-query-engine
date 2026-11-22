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
# or columnar.bench.SortBenchmark data/sf0.1 5 11
# or columnar.bench.Q3Benchmark data/sf0.1 8 15
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

Q1 exactly as the specification writes it: every column, including
`sum(l_extendedprice * (1 - l_discount) * (1 + l_tax))`, grouped on
`l_returnflag` and `l_linestatus`, with its `ORDER BY`. 10 warmups, 21
measured runs.

| Engine | median | min | max |
|---|---:|---:|---:|
| `ROW_BOXED` | 148.20 ms | 146.31 | 166.47 |
| `ROW_TUNED` | 87.57 ms | 87.12 | 89.66 |
| `VECTOR` | 29.35 ms | 28.61 | 29.68 |

**`VECTOR` is 2.9x faster than `ROW_TUNED`, and 5.0x faster than `ROW_BOXED`.**
Four runs of the full query gave 2.96x to 3.10x; seven earlier runs without
the final sort gave 2.92x to 3.19x. The sort adds nothing measurable, since
Q1 produces four rows. 2.92x is the lowest of all eleven, so 2.9x is the
figure to quote. All three engines produce identical output, in order.

## TPC-H Q3, scale factor 0.1

Two hash joins: 3,111 `BUILDING` customers are built and probed by 72,678
early orders, giving 15,224 rows; those are built and probed by 324,322 late
line items, of which 3,321 match. Then a `GROUP BY` into 1,216 groups, an
`ORDER BY` on revenue, and `LIMIT 10`. Revenue is moved to the end of the
select list, where grouping puts it; otherwise this is Q3 as written.
8 warmups, 15 measured runs.

| Engine | median | min | max |
|---|---:|---:|---:|
| `ROW_BOXED` | 120.04 ms | 117.50 | 134.29 |
| `ROW_TUNED` | 29.91 ms | 29.10 | 30.92 |
| `VECTOR` | 11.49 ms | 11.03 | 11.90 |

**`VECTOR` is 2.6x faster than `ROW_TUNED`, and about 10x faster than
`ROW_BOXED`.** Three runs gave 2.60x, 2.68x and 2.76x. All three engines return
the same ten rows, and `TpchQ3Test` checks them, and every group behind the
limit, against DuckDB.

**The first measurement said 6.5x, and was wrong.** The row join copied all
sixteen columns of every probe row, boxed, before checking its key, so it paid
to copy 324,322 line items in order to use 3,321 of them. A row-at-a-time join
has no reason to do that. Once it read the key first and copied only on a
match, `ROW_TUNED` fell from about 76 ms to about 30 ms and the speedup fell
with it. Same lesson as the harness bug: a baseline that loses through
carelessness turns the speedup into a measurement of the carelessness.

**Why 2.6x and not more.** Q3's cost is the lineitem probe: 324,322 rows each
need a hash lookup, in both engines, and batching cannot vectorize a hash
probe. It sits with Q1, which is dominated by hashing in the same way, rather
than with Q6, which is not.

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

## A full sort of lineitem, scale factor 0.1

All 600,572 rows and all 16 columns, ordered by `l_shipdate`,
`l_extendedprice DESC`, then the primary key so the order is total. This is
not a TPC-H query; it exists to measure the sort operator on a large input.
5 warmups, 11 measured runs. The engines are checked to agree by an
order-sensitive checksum over every output row.

| Engine | median | min | max |
|---|---:|---:|---:|
| `ROW_BOXED` | 452.74 ms | 411.58 | 481.25 |
| `ROW_TUNED` | 384.29 ms | 376.56 | 499.89 |
| `VECTOR` | 232.49 ms | 229.34 | 238.39 |

**`VECTOR` is 1.6x faster than `ROW_TUNED`, and 1.9x faster than
`ROW_BOXED`.** Three runs gave 1.60x, 1.64x and 1.65x.

**The first version managed 1.12x**, and the reason is worth recording.
Timing its three phases separately showed the copy-in at about 44 ms, the
sort at about 122 ms, and the gather — writing the sorted rows back out — at
about 180 ms, more than the sort itself. The gather was making one virtual
call per value: 16 columns times 600,572 rows is 9.6 million calls, each
re-checking bounds. That is row-at-a-time code inside the vectorized engine.
Replacing it with one bulk gather per column per batch, writing straight into
the primitive array, took the gather to about 69 ms. Output did not change;
the checksum is identical before and after.

**Why a sort gains far less than a filter.** A sort's work is mostly moving
data and comparing it, and both engines do the same O(n log n) comparisons.
Every one of the 600,572 rows survives, so there is no selectivity to exploit,
and five of the sixteen columns are strings that must be copied twice.

**What is left on the table.** The comparison phase, about 122 ms, is now the
largest. Each comparison calls through the column buffers, and with keys of
three different types that call cannot be inlined. The known fix is to
encode the sort keys into fixed-width normalized keys and compare those as
plain integers or bytes, which is what DuckDB does. It has not been done here.

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
comparison against DuckDB's own execution time, outer or semi joins, or a sort
or join build side large enough to need spilling.
