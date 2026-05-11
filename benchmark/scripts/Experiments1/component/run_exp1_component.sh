#!/usr/bin/env bash
# =============================================================================
# run_exp1_component.sh — Component-complexity run for Experiment 1
#
# Experiment 1: System Overhead — ProbSPARQL vs Deterministic SPARQL
#   Executes query workloads through remote Fuseki HTTP endpoints. Each logical
#   dataset must already be loaded on the server with a service name matching
#   the endpoint template's {dataset} placeholder.
#
# Full configuration:
#   Warmup : 3 runs  (default in ScalabilityBenchmark)
#   Measure: 10 runs (default in ScalabilityBenchmark)
#   Reports median + IQR across 10 measured iterations
#
# Estimated runtime: 30–90 minutes depending on machine speed
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments1/component/run_exp1_component.sh
#
# Optional env vars:
#   ENDPOINT_TEMPLATE — required, e.g. https://fujitsu:3030/{dataset}/query
#   OUTPUT_DIR        — override result output directory
#   SKIP_BUILD        — set to 1 to skip Maven compile step
#   SCALES            — space-separated scales to run (default: E1 E3 E5 E7)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp1/component}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SCALES="${SCALES:-E1 E3 E5 E7}"
ENDPOINT_TEMPLATE="${ENDPOINT_TEMPLATE:-}"

WARMUP_RUNS=3
BENCHMARK_RUNS=10

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
echo "  Experiment 1 — System Overhead (DET vs PROB)  [FULL RUN]"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Output dir   : $OUTPUT_DIR"
echo "  Endpoint tpl : ${ENDPOINT_TEMPLATE:-<required>}"
echo "  Java home    : $JAVA_HOME"
echo "  Warmup       : $WARMUP_RUNS"
echo "  Runs         : $BENCHMARK_RUNS"
echo "  Scales       : $SCALES"
echo "  Start time   : $(date)"
echo "------------------------------------------------------------------"
echo

cd "$PROJECT_ROOT"

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

mkdir -p "$OUTPUT_DIR"

# ── Step 2: Run ScalabilityBenchmark ────────────────────────────────────────
echo
echo "[2/2] Running ScalabilityBenchmark over remote endpoints (warmup=$WARMUP_RUNS, runs=$BENCHMARK_RUNS)..."
echo "      This will take a while — ${SCALES} × 4 K-values × 7 queries..."
echo

START_EPOCH=$(date +%s)

mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.ScalabilityBenchmark" \
    -Dexec.args="--endpoint-template ${ENDPOINT_TEMPLATE} \
                 --query-dir ${PROJECT_ROOT}/benchmark/queries/exp1/component \
                 --output-dir ${OUTPUT_DIR} \
                 --warmup ${WARMUP_RUNS} \
                 --scales ${SCALES} \
                 --runs ${BENCHMARK_RUNS}" \
    2>&1 | tee "${OUTPUT_DIR}/exp1_run.log"

END_EPOCH=$(date +%s)
ELAPSED=$(( END_EPOCH - START_EPOCH ))
echo
echo "=================================================================="
echo "  Benchmark completed in ${ELAPSED}s  ($(date))"
echo "  Raw results  : ${OUTPUT_DIR}/exp1_raw.csv"
echo "  Summary      : ${OUTPUT_DIR}/exp1_summary.csv"
echo "  Run log      : ${OUTPUT_DIR}/exp1_run.log"
echo "=================================================================="
echo
echo "  Next step:"
echo "    bash benchmark/scripts/Experiments1/component/analyze_exp1_component.sh \\"
echo "        --results-dir ${OUTPUT_DIR}"
