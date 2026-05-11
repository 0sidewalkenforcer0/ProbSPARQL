#!/usr/bin/env bash
# =============================================================================
# run_exp1_dimension.sh — Exp1 dimension-scaling supplement
#
# Executes the dimension-scaling workload from this machine against remote
# Fuseki HTTP endpoints. Each logical dataset must already be loaded on the
# Fujitsu server with a service name matching the endpoint template's
# {dataset} placeholder.
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
ENDPOINT_TEMPLATE="${ENDPOINT_TEMPLATE:-}"
SCALE="${SCALE:-E5}"
K_VALUE="${K_VALUE:-3}"
DIMENSIONS="${DIMENSIONS:-1 2 4 8}"
WARMUP_RUNS="${WARMUP_RUNS:-3}"
BENCHMARK_RUNS="${BENCHMARK_RUNS:-10}"

QUERY_DIR="${PROJECT_ROOT}/benchmark/queries/exp1/dimension"

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
echo "  Endpoint tpl : ${ENDPOINT_TEMPLATE:-<required>}"
echo "  Query dir    : $QUERY_DIR"
echo "  Output dir   : $OUTPUT_DIR"
echo "=================================================================="

cd "$PROJECT_ROOT"

if [[ -z "${ENDPOINT_TEMPLATE}" ]]; then
    echo "ERROR: ENDPOINT_TEMPLATE is required, e.g. https://fujitsu:3030/{dataset}/query" >&2
    exit 1
fi

if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo "[1/2] Building with Maven (skipping tests)..."
    mvn -q package -DskipTests
else
    echo "[1/2] Build skipped (SKIP_BUILD=1)."
fi

mkdir -p "$OUTPUT_DIR"

echo "[2/2] Running Exp1DimensionBenchmark over remote endpoints..."
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp1DimensionBenchmark" \
    -Dexec.args="--endpoint-template ${ENDPOINT_TEMPLATE} \
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
