#!/usr/bin/env bash
# =============================================================================
# run_exp1_component.sh — Component-complexity run for Experiment 1
#
# Experiment 1: System Overhead — ProbSPARQL vs Deterministic SPARQL
#   Compares query latency across 4 default dataset scales (E1, E3, E5, E7)
#   and 4 GMM complexities (K=1,3,5,10) for 3 deterministic and 4
#   probabilistic queries.
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
#   OUTPUT_DIR   — override result output directory
#   SKIP_BUILD   — set to 1 to skip Maven compile step
#   SCALES       — space-separated scales to generate/run (default: E1 E3 E5 E7)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp1/component}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SCALES="${SCALES:-E1 E3 E5 E7}"

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
echo "  Java home    : $JAVA_HOME"
echo "  Warmup       : $WARMUP_RUNS"
echo "  Runs         : $BENCHMARK_RUNS"
echo "  Scales       : $SCALES"
echo "  Start time   : $(date)"
echo "------------------------------------------------------------------"
echo

cd "$PROJECT_ROOT"

# ── Step 1: Build ───────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo "[1/3] Building with Maven (skipping tests)..."
    mvn -q package -DskipTests
    echo "      Build complete."
else
    echo "[1/3] Build skipped (SKIP_BUILD=1)."
fi

mkdir -p "$OUTPUT_DIR"

# ── Step 2: Generate experiment data (idempotent) ───────────────────────────
echo
echo "[2/3] Checking / generating exp1 datasets..."
DATA_DIR="${PROJECT_ROOT}/benchmark/data/exp1/component"
SCRIPTS_DIR="${PROJECT_ROOT}/benchmark/scripts/Experiments1/component"

read -r -a SCALE_LIST <<< "$SCALES"
FIRST_SCALE="${SCALE_LIST[0]}"

if [[ ! -f "${DATA_DIR}/exp1_${FIRST_SCALE}_K1.ttl" ]]; then
    echo "      Generating probabilistic exp1 datasets..."
    python3 "${SCRIPTS_DIR}/generate_exp1_component_probabilistic.py" \
        --scales ${SCALES} \
        --output-dir "${DATA_DIR}"
    echo "      Probabilistic datasets generated."
else
    echo "      Probabilistic datasets exist — skipping generation."
fi

if [[ ! -f "${DATA_DIR}/exp1_${FIRST_SCALE}_det.ttl" ]]; then
    echo "      Generating deterministic exp1 datasets..."
    python3 "${SCRIPTS_DIR}/generate_exp1_component_deterministic.py" \
        --scales ${SCALES} \
        --input-dir  "${DATA_DIR}" \
        --output-dir "${DATA_DIR}"
    echo "      Deterministic datasets generated."
else
    echo "      Deterministic datasets exist — skipping generation."
fi

# ── Step 3: Run ScalabilityBenchmark ────────────────────────────────────────
echo
echo "[3/3] Running ScalabilityBenchmark (warmup=$WARMUP_RUNS, runs=$BENCHMARK_RUNS)..."
echo "      This will take a while — ${SCALES} × 4 K-values × 7 queries..."
echo

START_EPOCH=$(date +%s)

mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.ScalabilityBenchmark" \
    -Dexec.args="--data-dir ${PROJECT_ROOT}/benchmark/data/exp1/component \
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
