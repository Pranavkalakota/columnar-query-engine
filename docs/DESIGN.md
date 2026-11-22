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

## The row baseline reads the same columnar batches

The point of the benchmark is to isolate one variable: the execution model. So
the tuned row engine reads primitives straight out of the same column vectors
the vectorized engine uses, one row at a time, through a reused cursor. The
only difference is per-row virtual calls against tight batch loops, not the
data layout. A row baseline reading from some other structure would be
measuring two changes at once, and the resulting speedup would mean nothing.

The boxed engine is the other end of the scale: an `Object[]` per row with
interpreted expressions, which is the textbook Volcano model and where boxing
and allocation show up. Reporting a speedup against only that one would
flatter the result, which is why both exist.

Two details that keep the baseline honest rather than a strawman. The cursor is
reused across rows instead of allocated per row, so the scan does not invent
garbage the vectorized path avoids. And an expression resolves its column names
to indexes once, when the evaluator is built, rather than looking them up per
row; a baseline that did schema lookups inside the loop would lose for a reason
that has nothing to do with being row-at-a-time.

## A predicate compiles to a chain, not to a boolean vector

`a AND b AND c` never produces a boolean vector. Each conjunct is compiled to
a kernel, and the kernels run in sequence: the first writes the rows that pass
into a selection array, and each later one reads that array and overwrites it
with the shorter list of rows that still pass. By the third kernel the loop is
visiting 2% of the rows rather than all of them, which is where most of the
win on Q6 comes from.

Narrowing in place is safe because of how the kernels are written. Reading
position `i` of the selection, a kernel has written at most `i` survivors, so
the write cursor never passes the read cursor. One array per filter does the
whole chain, with no second buffer and no copying between stages.

The compiler refuses what it has no kernel for rather than guessing. A
comparison between two columns, an `=` on a double, a `NULL` literal: each
raises `UnsupportedOperationException` at plan time, where a caller can see it
and fall back, rather than part-way through a scan. The one case worth calling
out is a fractional literal against an int column. `linenumber < 2.5` is a
legitimate query, and truncating 2.5 to 2 would silently drop every row where
`linenumber = 2`. It is refused instead.

## The vectorized sum folds in the same order as the row engine

Floating-point addition is not associative, so a vectorized sum that
accumulated per batch and combined at the end would disagree with the row
engine in the last few bits. The benchmark would then have to compare with a
tolerance, and a tolerance hides exactly the kind of bug worth catching.

So `RunningSum` keeps one running total across every batch, in row order,
which is the order the row engine uses. The loop reads that total into a local
and writes it back once at the end, so the JIT can keep it in a register
without changing the arithmetic. The two engines return the same `double`, bit
for bit, and the benchmark asserts equality rather than closeness.

`SUM(price * discount)` never materializes the product. There is no temporary
vector of products; the kernel multiplies and accumulates in one pass.

## Counting is not the same as summing

`SUM` over zero rows is `NULL` in SQL, not zero, and the two are different
answers. `RunningSum` therefore tracks a count alongside the total, so an
empty input is distinguishable from an input that genuinely sums to zero.
`COUNT(*)` reads `selectedCount()`, which is the length of the selection when
one is set and the row count when it is not.

## Group keys are copied out of the batch, not pointed at

Batches are recycled, so a group's key cannot be a reference into the batch it
arrived in: by the next batch those bytes are something else. Each key column
therefore keeps its own copy, one entry per group, appended the first time a
group is seen. Strings go into a private byte pool with offsets, the same
layout `StringVector` uses, so a key comparison is a byte-range compare and no
`String` is ever decoded on the grouping path.

The table itself is open addressing with linear probing: a bucket array holding
a group ordinal plus one, so zero means empty, and a parallel array of each
group's hash. Comparing the stored hash before the key means a probe that
collides in the bucket array usually costs one `int` compare rather than a walk
over the key columns. It grows at half full, which keeps probe chains short at
the cost of memory the aggregate already needs.

Keys are ordinals, not objects. Every aggregate is then a plain array indexed
by that ordinal, which is what makes folding a batch a loop over primitives.

## Doubles are refused as group keys

`GROUP BY` on a `DOUBLE` compiles to nothing and raises instead. Grouping is
equality, and equality on floating point is the wrong question to ask: 0.1 + 0.2
and 0.3 are different keys, and two values that print identically can land in
different groups. SQL engines do allow it, and this one may later, but refusing
it is better than silently producing groups a reader cannot explain. Integers,
dates and strings cover every TPC-H `GROUP BY`.

## NULL is a group, and an empty input is no rows at all

A NULL key is its own group rather than a dropped row, which is what SQL does
and the opposite of what a filter does with NULL. The store records a per-group
null flag alongside the value, so a NULL key and a zero-length string stay
distinct.

An ungrouped aggregate over an empty input returns one row of NULLs; a grouped
aggregate over an empty input returns **no rows at all**. Both are tested,
because the two operators disagreeing here would be easy to get wrong and
invisible until a query returned a phantom row.

## Grouped results come out in batches

The operator drains its child, then emits groups a batch at a time rather than
one batch however large. A `GROUP BY` on a high-cardinality column can produce
millions of groups, and materializing them in a single batch would undo the
point of a batch-at-a-time engine. Emission size is a constructor argument, and
the tests run the same query at 7 and at 1024 to prove the chunking changes
nothing.

## There is no grouped speedup number, on purpose

`RowAggregate` is ungrouped only, so there is no row-at-a-time grouped
aggregate to measure against. A timing for the vectorized version alone would
be a throughput figure with nothing to compare it to, which is not a speedup
and would be quoted as one. The number arrives when a row-side grouped
aggregate does.

## An arithmetic expression is a tree of kernels, each writing a whole vector

`l_extendedprice * (1 - l_discount) * (1 + l_tax)` compiles to a small tree.
Each node writes its values for the whole batch into a `double[]`, and a
binary node evaluates its left child into the output buffer, its right child
into a scratch buffer it owns, then combines them in one loop over primitives.
The scratch buffers are allocated once per node and reused for every batch, so
a query that runs a thousand times allocates them once.

The alternative was fusing specific shapes, recognising `a * (1 - b)` and
emitting a hand-written kernel for it. That is faster for the shapes it knows
and useless for everything else, and TPC-H alone would need a dozen such
special cases. A general tree costs one pass over a temporary array per
operator and handles any expression.

Replacing the hand-written `SUM(a)` and `SUM(a * b)` folders with this did not
change a single digit of Q6's output: the kernel multiplies and sums in the
same order the hand-written one did, which is the same order the row engine
uses. That the number did not move is the evidence the change was safe.

## Validity travels beside the values, and is skipped when it can be

A node returns a boolean saying "every row I produced is valid", and only
fills the validity array when that is false. A column with no nulls returns
true without touching the mask, and a tree of such columns never writes
validity at all. When one side of an operator has nulls and the other does
not, the valid side's mask is never even read.

This matters because `lineitem` has no nulls, so the whole Q1 expression tree
takes the cheap path, while the null handling stays correct and tested rather
than absent.

## Integer arithmetic is refused, not widened

Every node in a compiled expression must type as `DOUBLE`. An integer
subexpression is rejected instead of being evaluated in floating point, even
though doubles represent every int exactly up to 2^53. The reason is that
integer arithmetic has different semantics the moment it overflows or divides,
and silently computing it in another type is the kind of thing that produces a
plausible wrong answer. Integer kernels can be added when a query needs them.

## The row engine got a GROUP BY so the vector one could be measured

`RowGroupedAggregate` exists to be the baseline, not because anything needs a
slow `GROUP BY`. It is the textbook Volcano shape on purpose: a boxed
`List<Object>` key per row into a `LinkedHashMap`, values through the same
interpreter and accumulators the ungrouped row aggregate uses. Both row
engines share it, exactly as they share `RowAggregate`, so the only thing that
differs between `ROW_TUNED` and `VECTOR` is still the execution model.

Writing a deliberately slower operator is worth it because the alternative was
reporting a vectorized `GROUP BY` timing with nothing to compare it against,
which is a throughput figure that reads like a speedup.

## ORDER BY follows DuckDB, checked rather than assumed

Before writing the sort, DuckDB was asked how it orders things, and two of
its answers differ from what a straightforward implementation would do.

- **NULLs sort last in both directions.** DuckDB's `default_null_order` is
  `NULLS_LAST`, so a `DESC` sort still puts NULLs at the end. Postgres puts
  them first for `DESC`, which is the behaviour most people would guess.
- **Strings sort by code point, and `String.compareTo` does not.** DuckDB
  orders the fullwidth tilde U+FF5E before the emoji U+1F600. Java's
  `compareTo` compares UTF-16 code units, sees the emoji's high surrogate
  0xD83D, and puts the emoji first. A sort built on `compareTo` would silently
  disagree with DuckDB on any text outside the basic multilingual plane. The
  row engine compares code points; the vector engine compares unsigned UTF-8
  bytes, which is the same order.
- `NaN` sorts above every other double, which `Double.compare` already does.
  But `-0.0` and `0.0` are equal in SQL and `Double.compare` calls `-0.0`
  smaller, so doubles get their own comparison too.

`SortSemanticsOracleTest` pins all of this to DuckDB: a table of NULLs,
infinities, `NaN`, both zeros, supplementary characters, pre-1970 dates and
deliberate ties, sorted every way on all three systems and compared row for
row. The test was mutation-checked: swapping in `compareTo` fails it on
exactly the string orderings, and putting NULLs first fails every case.

## The vector sort sorts row numbers, not rows

`VectorSort` copies each column into a growable primitive buffer, sorts an
`int[]` permutation of row numbers, then gathers every column through that
permutation. Rows are never moved during the sort; only four-byte ints are.

The permutation is sorted with a merge sort written for `int[]`, because the
JDK's `Arrays.sort(int[])` takes no comparator and the version that does
needs `Integer[]`. Boxing every row number would allocate an object per row,
which is the cost the vector engine exists to remove. The merge sort is
stable and is checked against the JDK's own stable sort on 2,000 inputs full
of ties.

Stability is not something SQL promises, but it means two engines given the
same input in the same order break ties identically, so their outputs can be
compared exactly instead of as multisets.

Strings are compared straight out of a byte pool, so sorting never decodes a
`String`.

## Sorting is blocking and fully in memory

A sort cannot emit its first row until it has seen its last, so both sort
operators buffer their entire input. There is no spill to disk; the input
must fit in the heap. That is fine for an in-memory engine at these scale
factors and is the first thing that would break at larger ones.

The row sort copies each row into its own array because the operator below
it reuses a single cursor. It keeps a `DATE` as its integer day rather than a
`LocalDate`, so an operator above it can still call `getInt` on it.

## The join reuses the GROUP BY table, and undoes one of its rules

A hash join's build side is a table from key to rows, and `GroupKeys` already
maps keys to dense ordinals with every key type, NULL flags and string pools
handled. The join reuses it and adds three arrays: the first and last build
row for each ordinal, and a `next` link per build row.

Two changes made that possible. The key stores used to read a column fixed
when they were built; they now read whichever column they are handed, so the
probe side can look up keys that sit in different positions. And the table
gained `find`, a lookup that never inserts, because probing must not add the
probe's keys to the build table.

The rule it undoes is NULL. In a `GROUP BY`, NULL is a key like any other and
all the NULLs form one group. In a join, NULL equals nothing, not even another
NULL. DuckDB confirms it: joining `(1), (NULL)` to `(1), (NULL)` gives one row.
So both sides skip any row with a NULL in its key before touching the table.
Reusing the grouping table without that step would have joined every NULL to
every other NULL. `JoinSemanticsOracleTest` was mutation-checked for exactly
this: removing the skip on either engine fails three of its five tests.

## Build rows chain in arrival order

Each key's build rows are linked head to tail, not pushed onto the front, so a
probe row's matches come out in the order the build side produced them. The
row join's `LinkedHashMap` of lists does the same, so the two engines emit the
same pairs in the same order and can be compared exactly instead of as
multisets. SQL promises no join order; this is for testing.

## The probe side copies only rows that match

In Q3 the lineitem probe has 324,322 rows and 3,321 of them find a match.
The vector join therefore records matching row numbers first and gathers only
those rows into the output, rather than copying probe batches and filtering
afterwards.

The row baseline gets the same courtesy. Its first version copied all sixteen
probe columns, boxed, before checking the key, which cost it time for a reason
unrelated to being row-at-a-time. It now reads the key alone and copies the
row only on a match. A baseline that loses through carelessness makes the
speedup a measurement of the carelessness.

## The caller picks the build side

There is no optimizer, so a plan names which input is built and which is
probed, and the smaller input should be built. Q3 builds the 3,111 matching
customers, probes them with orders, then builds that 15,224-row result and
probes it with lineitem.

## Every row operator reads values the same way

Q3 found a bug from an earlier session. `RowGroupedAggregate` built its keys
with `row.get()`, which turns a DATE into a `LocalDate`, while every other row
operator keeps a DATE as its integer day. Nothing noticed until Q3 grouped on a
date and then sorted on it, and the sort's `getInt` hit a `LocalDate`. Three
operators had each written their own typed read and one got it wrong, so they
now share a single `RowValues.read`, with a test that sorts on a grouped date.

## Known divergences from the oracle

- `SUM` over an integer column returns `INT64` here and `HUGEINT` in DuckDB.
  The two agree for every sum that fits in a long; past that DuckDB is correct
  and this overflows. TPC-H sums are over `DOUBLE` columns, so the benchmark
  never reaches the gap.
- Join keys must have the same type on both sides. DuckDB would cast an
  `INTEGER` key to `BIGINT` to join them; this refuses the plan instead.
- There is no `DECIMAL` type. TPC-H's money columns are cast to `DOUBLE` at
  generation time so both sides read identical data. Double addition is not
  associative, so sums depend on evaluation order, which is why comparison uses
  a relative tolerance rather than equality.
