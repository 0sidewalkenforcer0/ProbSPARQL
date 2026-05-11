#!/usr/bin/env bash
# =============================================================================
# run_exp1_permutation.sh — Exp1 representation invariance sub-experiment
#
# Goal:
#   Compare Exp1 query latency on semantically equivalent GMM literals whose
#   mixture-component order is permuted.
#
# Execution:
#   Runs from this machine against remote Fuseki HTTP endpoints. Original,
#   permuted, and deterministic datasets must already be loaded on the Fujitsu
#   server with service names matching the endpoint template's {dataset}
#   placeholder.
#
# Setup:
#   - Scale fixed to E5
#   - K values fixed to 3, 5, 10  (K=1 skipped: no non-trivial permutation)
#   - Run two conditions:
#       1. original  datasets
#       2. permuted  datasets (same RDF graph, GMM components reordered)
#
# Output:
#   benchmark/results/exp1/permutation/
#
# Usage:
#   bash benchmark/scripts/Experiments1/permutation/run_exp1_permutation.sh
#
# Optional env vars:
#   ENDPOINT_TEMPLATE — required, e.g. https://fujitsu:3030/{dataset}/query
#   OUTPUT_ROOT  — result root directory
#   SKIP_BUILD   — set to 1 to skip Maven compile
#   SCALE        — default E5
#   K_VALUES     — default "3 5 10"
#   Q4_VARIANT   — default poly; set to legacy to use prob:jsdivergence
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

OUTPUT_ROOT="${OUTPUT_ROOT:-${PROJECT_ROOT}/benchmark/results/exp1/permutation}"
SKIP_BUILD="${SKIP_BUILD:-0}"
ENDPOINT_TEMPLATE="${ENDPOINT_TEMPLATE:-}"
SCALE="${SCALE:-E5}"
K_VALUES="${K_VALUES:-3 5 10}"
Q4_VARIANT="${Q4_VARIANT:-poly}"

WARMUP_RUNS=3
BENCHMARK_RUNS=10

QUERY_DIR="${PROJECT_ROOT}/benchmark/queries/exp1/permutation"
# ── Java 21 resolution ──────────────────────────────────────────────────────
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
echo "  Exp1 Sub-Experiment — GMM Permutation Invariance"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Endpoint tpl : ${ENDPOINT_TEMPLATE:-<required>}"
echo "  Output root  : $OUTPUT_ROOT"
echo "  Scale        : $SCALE"
echo "  K values     : $K_VALUES"
echo "  Q4 variant   : $Q4_VARIANT"
echo "  Java home    : $JAVA_HOME"
echo "  Start time   : $(date)"
echo "=================================================================="
echo

cd "$PROJECT_ROOT"
mkdir -p "$OUTPUT_ROOT"

if [[ -z "${ENDPOINT_TEMPLATE}" ]]; then
    echo "ERROR: ENDPOINT_TEMPLATE is required, e.g. https://fujitsu:3030/{dataset}/query" >&2
    exit 1
fi

# ── Step 1: Build ───────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo "[1/2] Building with Maven (skipping tests)..."
    mvn -q package -DskipTests
    echo "      Build complete."
else
    echo "[1/2] Build skipped (SKIP_BUILD=1)."
fi

# ── Step 2: Run paired benchmark in one JVM ─────────────────────────────────
echo
echo "[2/2] Running paired permutation benchmark over remote endpoints..."
mkdir -p "$OUTPUT_ROOT"

mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp1PermutationBenchmark" \
    -Dexec.args="--endpoint-template ${ENDPOINT_TEMPLATE} \
                 --query-dir ${QUERY_DIR} \
                 --output-dir ${OUTPUT_ROOT} \
                 --scale ${SCALE} \
                 --q4-variant ${Q4_VARIANT} \
                 --k-values ${K_VALUES} \
                 --warmup ${WARMUP_RUNS} \
                 --runs ${BENCHMARK_RUNS}" \
    2>&1 | tee "${OUTPUT_ROOT}/run.log"

echo
echo "=================================================================="
echo "  Exp1 permutation experiment complete"
echo "  Results  : ${OUTPUT_ROOT}"
echo "=================================================================="
