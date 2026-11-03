# ColumnarDB

An analytical query engine written from scratch in Java. The goal is to store
tables in a columnar file format, read Parquet, accept a subset of SQL, optimize
the plan, and execute it with vectorized (batch-at-a-time) operators.

This is the read-and-query half of a database. The write-and-storage half is a
separate project: an LSM-tree storage engine with a WAL, SSTables, bloom
filters, and compaction.

## Why vectorized execution

Analytical queries touch a few columns across a huge number of rows. Two
things make a naive engine slow:

1. **Row storage reads too much.** A table with 16 columns where the query
   needs 2 still reads all 16 off disk and through the CPU cache. A columnar
   layout reads only the 2 that matter, and similar values stored next to each
   other compress far better.
2. **Row-at-a-time execution wastes the CPU.** The Volcano model calls
   `next()` once per row per operator: a virtual call, boxed values, and an
   unpredictable branch for every single row. Vectorized execution pushes
   batches of ~1024 values through tight loops over primitive arrays, which
   amortizes the call overhead and lets the JIT do its job.

This is the design behind DuckDB, ClickHouse, and Velox. The original paper is
MonetDB/X100 (Boncz, Zukowski, Nes, CIDR 2005).

## Status

Work in progress. What is in the repository so far:

- Typed columnar vectors over primitive arrays, with null bitmaps
- Recycled batches, narrowed by a selection vector rather than copied
- Strings as one UTF-8 byte pool plus offsets, not an object per value
- Types, promotion rules, fields, schemas, and date conversions
- A zero-copy loader for TPC-H `.tbl` and CSV, reading fields as byte ranges
- In-memory tables, so a file is parsed once and scanned repeatedly
- DuckDB wired in as a differential-testing oracle, with result comparison
- TPC-H data generation at any scale factor, as Parquet and `.tbl`
- An expression tree, and two row-at-a-time engines over it
- Scan, filter and ungrouped aggregation on all three engines
- A vectorized filter that compiles a conjunction into a chain of kernels

Not built yet: grouped aggregation, joins, sorting, the columnar file format,
the SQL frontend, the optimizer, the Parquet reader, and parallel execution.

**Measured, on TPC-H Q6 at scale factor 0.1** (600,572 rows, in memory, single
thread): the vectorized engine runs in 1.66 ms against the tuned row engine's
24.33 ms and the boxed engine's 94.27 ms — **14.7x faster than the tuned row
engine, 56.8x faster than the boxed one.** All three return the same revenue,
the same `double` bit for bit, and it matches DuckDB.

The tuned row engine interprets the expression tree once per row and boxes the
intermediates, so that 14.7x covers removing interpretation and allocation as
well as batching — it is not attributable to SIMD alone. Method, hardware and
the rest of the caveats are in `docs/BENCHMARKS.md`.

## Three execution engines, one plan

All three engines run scans, filters and ungrouped aggregation today. They
share the expression tree and the same in-memory batches, so only the physical
operators differ, which isolates the single variable the benchmark is about:
the execution model.

| Engine | Description | Why it exists |
|---|---|---|
| `ROW_BOXED` | Volcano model, `Object[]` rows, interpreted expressions | the textbook baseline |
| `ROW_TUNED` | Volcano model, primitive getters, type-specialized expressions | the honest baseline |
| `VECTOR` | Batch-at-a-time with selection vectors and kernels | the point of the project |

Speedups are reported against both row engines, not just the boxed one.

## Correctness approach

DuckDB runs every test query as an oracle, in process over JDBC. A feature
without a differential test is not done. Beyond that, each query result must
be identical across an equivalence matrix: engine, batch size (1, 2, 3, 7, 64,
1000, 1024, 1025, 65536), thread count, optimizer rules on/off, zone maps
on/off, and source format. Most real bugs show up as one failing cell in that
matrix rather than in a hand-written unit test.

## Build

Requires JDK 21 or newer and Maven.

```
mvn test
```

## Generating data

TPC-H data comes from DuckDB's `tpch` extension and is written as both Parquet
and pipe-delimited `.tbl`, so the same rows can be read through either path.

```
./benchmarks/scripts/gen_tpch.sh        # SF0.1 and SF1
./benchmarks/scripts/gen_tpch.sh 0.01   # one small scale factor
```

Output lands in `data/sf<factor>/` and is gitignored. SF0.1 is 133MB and takes
about 3 seconds; SF1 is roughly 1GB, so it is generated on demand.

Two details that exist to keep later comparisons honest. TPC-H's money columns
are `DECIMAL(15,2)` and this engine has no DECIMAL type, so they are cast to
`DOUBLE` at generation time — both the engine and the oracle then read
byte-identical data, instead of every comparison carrying a representation
difference that says nothing about correctness. And real dbgen ends every line
with a trailing `|`, which the generator reproduces: emitting cleaner files
would mean the loader never gets tested against the format it actually has
to read.

## Non-goals

No transactions or updates (bulk load only), no subqueries or window
functions, no nested Parquet types, no spill-to-disk, no cost-based optimizer,
no distributed execution. Beating DuckDB is not a goal either; the goal is to
land within a stated factor and explain the gap.

## Intended layout

Where the pieces are meant to go. Today `types/`, `vector/`, `storage/csv/`,
`expr/` (with `expr/vec/` kernels) and `exec/` (`row/`, `vec/` and `agg/`)
exist, along with the differential tests and benchmarks under `src/test`;
`plan/`, `sql/`, `cli/`, `storage/colfile/` and `storage/parquet/` are the
target.

```
src/main/java/columnar/
  types/      DataType, Schema, Field
  vector/     ColumnVector subclasses, VectorBatch, validity bitmaps
  storage/    csv/ colfile/ parquet/
  expr/       expression tree, row evaluator, vectorized kernels
  plan/       logical/ optimizer/ physical/
  exec/       row/ vec/ parallel/
  sql/        lexer, parser, binder
  cli/        REPL, result printer
src/test/java/columnar/differential/
              DuckDB oracle, result comparator, equivalence matrix
```
