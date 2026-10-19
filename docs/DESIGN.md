# Design decisions

Why the engine is built the way it is. Each entry records the alternative that
was rejected, because a decision without its rejected alternative is just a
description.

## Correctness comes from an oracle, not from assertions

DuckDB runs every test query in process over JDBC, and results must match. The
oracle was built first, before any engine code, because every later
definition of "done" is "agrees with DuckDB". A wrong comparator produces a
green suite that proves nothing, and the failure would surface much later as a
mystery.

It already paid for itself. DuckDB returns `SUM` over any integer type as
HUGEINT, which JDBC hands back as `BigInteger` — a class the normalizer did not
handle, so values fell through to `toString()`. Every integer `SUM` test would
have failed with "expected 3, got 3": a broken comparator wearing the costume
of an engine bug.

Semantics are checked rather than assumed, and the obvious guess has been wrong
more than once:

| Question | Answer, from the oracle |
|---|---|
| `INTEGER / INTEGER` | `DOUBLE`. `1/2` is `0.5`, and `1/0` is `Infinity`, not an error |
| `SUM(INTEGER)` | `HUGEINT`, not `BIGINT` |
| `DATE * 2` | rejected outright |
| An empty CSV field | `NULL`, for quoted `""` as well as an unquoted gap |
| `2023-02-29` | a hard error, not rolled into March |

## Result comparison matches multisets, it does not sort

Comparing unordered results by sorting both sides and walking them in lockstep
is broken, because **a tolerance is not a total order**. If a double differs in
its fifteenth digit, the two rows can sort to different positions and lockstep
comparison reports a mismatch that is not one.

So rows are bucketed by the columns that compare exactly — strings, dates,
integers — and scanned linearly only inside a bucket. A column counts as exact
only when no `Double` appears in it on *either* side, which keeps a column
that is integral on one side and approximate on the other out of the key. This
degrades to O(n²) on an all-float result set, which the query suite barely
produces.

## Types are an enum, and DATE is an int

Every type in v1 is unparameterized, so there is nothing for a record to carry
and an enum gives exhaustive `switch` for free. Adding `DECIMAL(precision,
scale)` later would need parameters and force a sealed interface; that is the
price of not paying the ceremony now.

DATE is days since 1970-01-01 in an `int`, negative before it, so it shares
storage with INT32 and date filters compile to the same integer comparison
kernels as any other int column. It is integral but deliberately *not* numeric:
comparing two dates is meaningful, multiplying them is not, and excluding it
from arithmetic promotion makes the binder reject that the way DuckDB does.

## Column lookup reports ambiguity rather than resolving it

A join of two tables that both have a `c` column produces a schema with two
`c` fields. Returning the first would make `SELECT c` silently read a column
the user did not mean, so lookup refuses instead. Lookup is case-insensitive
because unquoted SQL identifiers are.

## Vectors hold primitive arrays, not generics

`ColumnVector<T>` would erase to `Object` and box every value, which is the
exact overhead the project exists to remove. Instead a base class owns null
handling and capacity, and each subclass exposes its raw primitive array.
Type dispatch therefore happens **once per batch**, not once per row, because
the binder has already resolved every expression's type at plan time.

Vectors are recycled through `reset` rather than reallocated per batch. A fresh
array per batch would put the vector path back into the allocator, which is
half of what makes the row path slow.

## Nulls are a bitmap, with a flag to skip them

One bit per row rather than a `boolean[]`'s byte, so eight times as many rows
fit in a cache line. The larger win is a single `mayHaveNulls` flag: a kernel
reads it once per batch and runs a loop with no null handling at all when it is
clear.

The flag is deliberately **conservative**. It latches true on the first null
written and is never recomputed, because clearing it would mean scanning the
bitmap to save a branch. It means "might", not "does", and a stale true only
costs a slower loop.

`1 = valid` matches Arrow and Parquet, so Parquet definition levels map across
without inversion.

`BooleanVector` is a bitmap for the same reason plus one more: a SQL boolean
needs two facts per row, its value and whether it is known, and when both are
`long[]`, `a AND b` across 64 rows is a few word operations instead of 64
branches. Three-valued logic falls out of masking.

## Strings are two arrays, not a thousand objects

All UTF-8 bytes go in one `byte[]`, with an `int[]` of offsets of length n+1
marking boundaries. A batch of 1024 strings is two allocations rather than 1024
`String`s each wrapping its own array, and filters compare byte ranges in place
without allocating.

The cost is that values must be **appended in row order**, because a value's
position depends on the total length of everything before it. Loaders and scans
fill a batch in order anyway.

An empty string and a NULL are both zero bytes long, told apart only by the
validity bit. That distinction is load-bearing for TPC-H `.tbl` files.

## Filters narrow a selection vector, they do not copy

A filter does not build a new batch of the rows that passed. It writes their
indexes into an `int[]` and narrows the count. With five columns and 900 of
1024 rows surviving, copying would move 4,500 values; recording 900 indexes
touches no column data at all.

**This is a trade, not a free win.** Every downstream loop becomes
`values[selection[i]]` rather than `values[i]`, which is an indirection,
scatters cache access, and can stop the JIT auto-vectorizing a loop it would
otherwise unroll. Selection vectors pay off when few rows survive; compacting
into a dense batch pays off when most do and several operators run after.
Production engines switch between the two adaptively. This one always uses a
selection vector, which is a simplification worth stating rather than hiding.

A related trap, stated once because it has bitten every engine ever written:
**an empty batch is not end of stream.** A filter that rejects every row of a
batch must keep pulling from its child.

## The loader is zero-copy, except where correctness costs more

A field is handed back as a byte *range* into a shared buffer, valid only until
the next record is read. A VARCHAR goes straight into a string vector's byte
pool; integers and dates parse from the bytes directly.

Two deliberate exceptions:

- **Doubles go through `Double.parseDouble` on a String**, which allocates.
  Correct decimal-to-binary rounding is genuinely hard, and getting it slightly
  wrong would put every aggregate a few bits away from the oracle. The loader
  is not the query hot path: data is loaded once and queried many times.
- **Date arithmetic is hand-rolled** rather than going through `LocalDate`,
  which would allocate per value across tens of millions of fields. Hand-rolled
  calendar maths is a classic source of off-by-one bugs, so the test checks
  every one of the 3.65 million days in the supported range against
  `LocalDate` instead of spot-checking a few.

Integer parsing accumulates **negatively**, the JDK's trick: `Long.MIN_VALUE`
has no positive counterpart, so a parser that builds a positive value and
negates it cannot represent it. Overflow is detected, never wrapped, because a
silently wrapped key is worse than a failed load.

## Materializing a table cannot reuse the loader's batch

The loader recycles one batch, resetting it per call, which is right for
streaming and wrong for materializing: keeping a reference to a recycled batch
would hand back the last chunk N times. So an in-memory table allocates a fresh
batch per chunk and accepts the memory, which is the point of holding a table
in memory at all.

Those batches outlive any one query, so consumers must treat column data as
immutable. An operator may set a selection on a batch, but that makes the batch
unsafe to share between two concurrent scans. That is a problem for whenever
parallel execution lands, noted here rather than discovered then.

## Known divergences from the oracle

- `SUM` over an integer column returns `INT64` here and `HUGEINT` in DuckDB.
  The two agree for every sum that fits in a long; past that DuckDB is correct
  and this overflows. TPC-H sums are over `DOUBLE` columns, so the benchmark
  never reaches the gap.
- There is no `DECIMAL` type. TPC-H's money columns are cast to `DOUBLE` at
  generation time so both sides read identical data. Double addition is not
  associative, so sums depend on evaluation order, which is why comparison uses
  a relative tolerance rather than equality.
