#!/usr/bin/env bash
# =============================================================================
# run_exp3.sh — Exp3: classification accuracy
#
# Evaluates the 5 sampling methods (V1_MC, V2_STRATIFIED, V3_SPRT,
# V4_BOUNDS, V5_ADAPTIVE) against CSV ground truth on 4 difficulty-stratified
# datasets (easy / medium / hard / mixed), each with 200 aligned GMM pairs.
# Current official dataset version corresponds to the former exp3_1_k5_n300_new
# configuration (K=5, N=300).
#
# Configuration (from Exp3Benchmark.java):
#   Ground truth = simjoin_ground_truth.csv
#   EVAL_SAMPLES = 10,000  (budget for V1–V5 per pair)
#   REPEAT       = 10      (repetitions per method × pair)
#
# Estimated runtime: 30–90 min
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments3/run_exp3.sh
#
# Optional env vars:
#   OUTPUT_DIR   — override output directory
#   DATA_DIR     — override data directory
#   SKIP_BUILD   — set to 1 to skip Maven compile
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp3}"
DATA_DIR="${DATA_DIR:-${PROJECT_ROOT}/benchmark/data/exp3}"
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
export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"

echo "=================================================================="
echo "  Experiment 3 — Classification Accuracy"
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

# ── Step 2: Check datasets ───────────────────────────────────────────────────
echo
echo "[2/3] Checking SimJoin datasets..."
MISSING=0
for D in easy medium hard mixed; do
    TTL="${DATA_DIR}/simjoin_${D}.ttl"
    if [[ ! -f "$TTL" ]]; then
        echo "  ERROR: Missing dataset: $TTL"
        MISSING=1
    else
        N=$(grep -c "prob:hasGMM" "$TTL" 2>/dev/null || echo "?")
        echo "  OK  simjoin_${D}.ttl  (${N} GMM triples)"
    fi
done
if [[ $MISSING -eq 1 ]]; then
    echo ""
    echo "  Run generate_exp3.py first:"
    echo "    python3 benchmark/scripts/Experiments3/generate_exp3.py --output-dir $DATA_DIR"
    exit 1
fi

# ── Step 3: Run benchmark ────────────────────────────────────────────────────
echo
echo "[3/3] Running Exp3Benchmark..."
START_TS=$SECONDS


REPEAT="${REPEAT:-10}"
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp3Benchmark" \
    "-Dexec.args=--data-dir ${DATA_DIR} --output-dir ${OUTPUT_DIR} --repeat ${REPEAT}" \
    2>&1 | tee "${OUTPUT_DIR}/exp3_run.log"

# Original command without REPEAT override:
# mvn -q exec:java \
#     -Dexec.mainClass="org.apache.jena.probsparql.Exp3Benchmark" \
#     "-Dexec.args=--data-dir ${DATA_DIR} --output-dir ${OUTPUT_DIR}" \
#     2>&1 | tee "${OUTPUT_DIR}/exp3_run.log"

ELAPSED=$(( SECONDS - START_TS ))
echo
echo "------------------------------------------------------------------"
echo "  Exp 3 finished in ${ELAPSED}s  ($(date))"
echo "  Results:"
for F in exp3_classification.csv exp3_per_pair.csv; do
    if [[ -f "${OUTPUT_DIR}/${F}" ]]; then
        N=$(( $(wc -l < "${OUTPUT_DIR}/${F}") - 1 ))
        echo "    ${OUTPUT_DIR}/${F}  (${N} data rows)"
    fi
done
echo "=================================================================="
