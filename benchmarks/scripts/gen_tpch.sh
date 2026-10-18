#!/usr/bin/env bash
#
# Generates TPC-H data as Parquet and pipe-delimited .tbl.
#
#   ./benchmarks/scripts/gen_tpch.sh            # the default scale factors
#   ./benchmarks/scripts/gen_tpch.sh 0.01       # one small scale factor
#
# Output goes to data/sf<factor>/, which is gitignored. SF0.1 is for
# development and the differential tests; SF1 is for benchmarks. SF1 writes
# roughly 1GB, so it is generated on demand rather than by default in CI.
#
# Decimal columns are cast to DOUBLE on the way out so that the engine and the
# DuckDB oracle read byte-identical data. See TpchData for why.

set -euo pipefail

cd "$(dirname "$0")/../.."

SCALE_FACTORS=("$@")
if [ ${#SCALE_FACTORS[@]} -eq 0 ]; then
  SCALE_FACTORS=(0.1 1)
fi

echo "==> compiling"
mvn -q -B test-compile

CLASSPATH_FILE=$(mktemp)
trap 'rm -f "$CLASSPATH_FILE"' EXIT
mvn -q -B dependency:build-classpath \
  -Dmdep.includeScope=test \
  -Dmdep.outputFile="$CLASSPATH_FILE"

CP="target/test-classes:target/classes:$(cat "$CLASSPATH_FILE")"

for SF in "${SCALE_FACTORS[@]}"; do
  OUT="data/sf${SF}"
  echo "==> generating scale factor ${SF} into ${OUT}"
  java -cp "$CP" columnar.tpch.TpchData "$SF" "$OUT"
done

echo "==> done"
du -sh data/sf* 2>/dev/null || true
