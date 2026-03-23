#!/usr/bin/env bash
# =============================================================================
# run_exp1_full.sh — Full overnight run for Experiment 1
#
# Experiment 1: System Overhead — ProbSPARQL vs Deterministic SPARQL
#   Compares query latency across 7 dataset scales (E1–E7) and 4 GMM
#   complexities (K=1,3,5,10) for 4 deterministic and 4 probabilistic queries.
#
# Full configuration:
#   Warmup : 3 runs  (default in ScalabilityBenchmark)
#   Measure: 10 runs (default in ScalabilityBenchmark)
#   Reports median + IQR across 10 measured iterations
#
# Estimated runtime: 30–90 minutes depending on machine speed
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments1/run_exp1_full.sh
#
# Optional env vars:
#   OUTPUT_DIR   — override result output directory
#   SKIP_BUILD   — set to 1 to skip Maven compile step
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp1_full}"
SKIP_BUILD="${SKIP_BUILD:-0}"

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

echo "=================================================================="
echo "  Experiment 1 — System Overhead (DET vs PROB)  [FULL RUN]"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Output dir   : $OUTPUT_DIR"
echo "  Java home    : $JAVA_HOME"
echo "  Warmup       : $WARMUP_RUNS"
echo "  Runs         : $BENCHMARK_RUNS"
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
DATA_DIR="${PROJECT_ROOT}/benchmark/data/exp1"
SCRIPTS_DIR="${PROJECT_ROOT}/benchmark/scripts"

if [[ ! -d "$DATA_DIR" ]] || [[ -z "$(ls -A "$DATA_DIR" 2>/dev/null)" ]]; then
    echo "      Generating exp1 datasets..."
    python3 "${SCRIPTS_DIR}/generate_dataset_deterministic.py" \
        --input-dir  "${PROJECT_ROOT}/benchmark/data" \
        --output-dir "${PROJECT_ROOT}/benchmark/data"
    echo "      Datasets generated."
else
    echo "      Data directory exists — skipping generation."
fi

# ── Step 3: Run ScalabilityBenchmark ────────────────────────────────────────
echo
echo "[3/3] Running ScalabilityBenchmark (warmup=$WARMUP_RUNS, runs=$BENCHMARK_RUNS)..."
echo "      This will take a while — all 7 scales × 4 K-values × 7 queries..."
echo

START_EPOCH=$(date +%s)

mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.ScalabilityBenchmark" \
    -Dexec.args="--data-dir ${PROJECT_ROOT}/benchmark/data/exp1 \
                 --query-dir ${PROJECT_ROOT}/benchmark/queries \
                 --output-dir ${OUTPUT_DIR} \
                 --warmup ${WARMUP_RUNS} \
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
echo "    bash benchmark/scripts/Experiments1/analyze_exp1.sh \\"
echo "        --results-dir ${OUTPUT_DIR}"
