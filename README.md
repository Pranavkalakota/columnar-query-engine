# ColumnarDB

An analytical query engine written from scratch in Java. It stores tables in a
columnar file format, reads Parquet, accepts a subset of SQL, optimizes the
plan, and executes it with vectorized (batch-at-a-time) operators.

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

Under construction. Phase 1 of 10.

| Phase | Deliverable | State |
|---|---|---|
| 0 | Skeleton, TPC-H data generation, DuckDB oracle harness | done |
| 1 | Types, vectors, batches, TBL loader, row scan/filter/project | next |
| 2 | Row engines complete: aggregate, sort, limit, hash join | |
| 3 | Vectorized kernels and operators | |
| 4 | Columnar file format: encodings, zone maps, projection pushdown | |
| 5 | Vectorized sort, TopN, hash join | |
| 6 | SQL lexer, parser, binder, REPL | |
| 7 | Rule-based optimizer and EXPLAIN | |
| 8 | Hand-written Parquet reader | |
| 9 | Morsel-driven parallel execution | |
| 10 | Benchmarks and write-up | |

**There are no benchmark numbers here yet.** Every number that eventually
lands in this README will come from a reproducible run of
`benchmarks/scripts/run_all.sh` on stated hardware. Until then, nothing is
claimed.

## Three execution engines, one plan

The logical plan, the optimizer, and the storage readers are shared. Only the
physical operators differ, which isolates the single variable the benchmark is
about: the execution model.

| Engine | Description | Why it exists |
|---|---|---|
| `ROW_BOXED` | Volcano model, `Object[]` rows, interpreted expressions | the textbook baseline |
| `ROW_TUNED` | Volcano model, primitive getters, type-specialized expressions | the honest baseline |
| `VECTOR` | Batch-at-a-time with selection vectors and kernels | the point of the project |

Speedups will be reported against both row engines, not just the boxed one.

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

## Layout

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
