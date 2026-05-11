#!/usr/bin/env bash
# =============================================================================
# run_exp1_dimension.sh — Exp1 dimension-scaling supplement
#
# Fixed:
#   - scale = E5
#   - K = 3
#   - warm-up = 3 runs
#   - measured = 10 runs
# Variable:
#   - d in {1,2,4,8} by default
# Queries:
#   - Q1-Q3 deterministic and probabilistic paths
#   - Q4 legacy prob:jsdivergence and polymorphic prob:jsd
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp1/dimension}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SCALE="${SCALE:-E5}"
K_VALUE="${K_VALUE:-3}"
DIMENSIONS="${DIMENSIONS:-1 2 4 8}"
WARMUP_RUNS="${WARMUP_RUNS:-3}"
BENCHMARK_RUNS="${BENCHMARK_RUNS:-10}"

DATA_DIR="${PROJECT_ROOT}/benchmark/data/exp1/dimension"
QUERY_DIR="${PROJECT_ROOT}/benchmark/queries/exp1/dimension"
SCRIPT_PY="${PROJECT_ROOT}/benchmark/scripts/Experiments1/dimension/generate_exp1_dimension.py"

if [[ -z "${JAVA_HOME:-}" ]]; then
    if command -v /usr/libexec/java_home &>/dev/null; then
        JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home)"
    else
        JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
    fi
    export JAVA_HOME
fi
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"

echo "=================================================================="
echo "  Exp1 Supplement — Dimension Scaling"
echo "=================================================================="
echo "  Scale        : $SCALE"
echo "  K            : $K_VALUE"
echo "  Dimensions   : $DIMENSIONS"
echo "  Data dir     : $DATA_DIR"
echo "  Query dir    : $QUERY_DIR"
echo "  Output dir   : $OUTPUT_DIR"
echo "=================================================================="

cd "$PROJECT_ROOT"

if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo "[1/3] Building with Maven (skipping tests)..."
    mvn -q package -DskipTests
else
    echo "[1/3] Build skipped (SKIP_BUILD=1)."
fi

echo "[2/3] Generating dimension datasets if needed..."
mkdir -p "$DATA_DIR" "$OUTPUT_DIR"
read -r -a DIM_LIST <<< "$DIMENSIONS"
NEEDS_GEN=0
for dim in "${DIM_LIST[@]}"; do
    if [[ ! -f "${DATA_DIR}/exp1_${SCALE}_K${K_VALUE}_D${dim}.ttl" ]]; then
        NEEDS_GEN=1
        break
    fi
done
if [[ "${NEEDS_GEN}" == "1" ]]; then
    python3 "$SCRIPT_PY" --scale "$SCALE" --k "$K_VALUE" --dimensions ${DIMENSIONS} --output-dir "$DATA_DIR"
else
    echo "      Datasets exist — skipping generation."
fi

echo "[3/3] Running Exp1DimensionBenchmark..."
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp1DimensionBenchmark" \
    -Dexec.args="--data-dir ${DATA_DIR} \
                 --query-dir ${QUERY_DIR} \
                 --output-dir ${OUTPUT_DIR} \
                 --scale ${SCALE} \
                 --k ${K_VALUE} \
                 --dimensions ${DIMENSIONS} \
                 --warmup ${WARMUP_RUNS} \
                 --runs ${BENCHMARK_RUNS}"

echo
echo "Done. Results:"
echo "  ${OUTPUT_DIR}/exp1_dimension_raw.csv"
echo "  ${OUTPUT_DIR}/exp1_dimension_summary.csv"
