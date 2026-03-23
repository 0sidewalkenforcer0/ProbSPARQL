#!/usr/bin/env bash
# =============================================================================
# run_exp3_2.sh — Experiment 3.2: Convergence Analysis (All Methods)
#
# Selects the GMM pair from simjoin_hard.ttl whose quick-estimate JSD is
# closest to θ=0.3, then measures how each sampling method's JSD estimate
# converges to the 100k-sample ground truth as sample count increases.
#
# Configuration (from MultiMethodConvergenceBenchmark.java):
#   SAMPLE_COUNTS = [100, 500, 1000, 5000, 10000, 50000, 100000]
#   REPETITIONS   = 50  (runs per method × sample count)
#   GT_SAMPLES    = 100,000  (high-precision reference)
#   Dataset       = simjoin_hard.ttl
#
# Estimated runtime: 1–2 hours
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments3/run_exp3_2.sh
#
# Optional env vars:
#   OUTPUT_DIR   — override output directory
#   DATA_DIR     — override data directory
#   SKIP_BUILD   — set to 1 to skip Maven compile
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp3_full}"
DATA_DIR="${DATA_DIR:-${PROJECT_ROOT}/benchmark/data}"
SKIP_BUILD="${SKIP_BUILD:-0}"

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
echo "  Experiment 3.2 — Convergence Analysis (All Sampling Methods)"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Output dir   : $OUTPUT_DIR"
echo "  Data dir     : $DATA_DIR"
echo "  Java home    : $JAVA_HOME"
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

# ── Step 2: Check hard dataset ───────────────────────────────────────────────
echo
echo "[2/3] Checking simjoin_hard.ttl..."
HARD_TTL="${DATA_DIR}/simjoin_hard.ttl"
if [[ ! -f "$HARD_TTL" ]]; then
    echo "  ERROR: Missing dataset: $HARD_TTL"
    echo "  Run: python3 benchmark/scripts/generate_sim_join_data.py --output-dir $DATA_DIR"
    exit 1
fi
N=$(grep -c "prob:hasGMM" "$HARD_TTL" 2>/dev/null || echo "?")
echo "  OK  simjoin_hard.ttl  (${N} GMM triples)"

# ── Step 3: Run benchmark ────────────────────────────────────────────────────
echo
echo "[3/3] Running MultiMethodConvergenceBenchmark..."
START_TS=$SECONDS

mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.MultiMethodConvergenceBenchmark" \
    "-Dexec.args=--data-dir ${DATA_DIR} --output-dir ${OUTPUT_DIR}" \
    2>&1 | tee "${OUTPUT_DIR}/exp3_2_run.log"

ELAPSED=$(( SECONDS - START_TS ))
echo
echo "------------------------------------------------------------------"
echo "  Exp 3.2 finished in ${ELAPSED}s  ($(date))"
OUTCSV="${OUTPUT_DIR}/exp3_2_convergence_multimethod.csv"
if [[ -f "$OUTCSV" ]]; then
    N=$(( $(wc -l < "$OUTCSV") - 1 ))
    echo "    ${OUTCSV}  (${N} data rows)"
fi
echo "=================================================================="
