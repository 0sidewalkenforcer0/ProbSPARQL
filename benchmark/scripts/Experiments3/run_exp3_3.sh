#!/usr/bin/env bash
# =============================================================================
# run_exp3_3.sh — Experiment 3.3: Selectivity Sensitivity
#
# Measures how each sampling method's latency, result count, and classification
# accuracy change as the similarity threshold θ varies across:
#   θ ∈ {0.01, 0.05, 0.10, 0.20, 0.30, 0.50}
#
# Configuration (from SelectivityBenchmark.java):
#   WARMUP     = 3   iterations (warmed JVM)
#   ITERATIONS = 10  measured runs per (method × θ × dataset)
#   Methods    = V1_MC, V2_STRATIFIED, V3_SPRT, V4_BOUNDS, V5_ADAPTIVE
#   Datasets   = easy, medium, hard, mixed
#
# Prerequisite: simjoin_ground_truth.csv must exist.
#   This script auto-generates it if missing (adds ~5–15 min).
#
# Estimated runtime: 1–3 hours
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments3/run_exp3_3.sh
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

GT_CSV="${OUTPUT_DIR}/simjoin_ground_truth.csv"

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
export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" \

echo "=================================================================="
echo "  Experiment 3.3 — Selectivity Sensitivity"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Output dir   : $OUTPUT_DIR"
echo "  Data dir     : $DATA_DIR"
echo "  GT CSV       : $GT_CSV"
echo "  Java home    : $JAVA_HOME"
echo "  Start time   : $(date)"
echo "------------------------------------------------------------------"
echo

cd "$PROJECT_ROOT"

# ── Step 1: Build ───────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo "[1/4] Building with Maven (skipping tests)..."
    mvn -q package -DskipTests
    echo "      Build complete."
else
    echo "[1/4] Build skipped (SKIP_BUILD=1)."
fi

mkdir -p "$OUTPUT_DIR"

# ── Step 2: Generate Ground Truth CSV if needed ──────────────────────────────
echo
if [[ -f "$GT_CSV" ]]; then
    N=$(( $(wc -l < "$GT_CSV") - 1 ))
    echo "[2/4] Ground truth CSV already exists  (${N} pairs)."
else
    echo "[2/4] Ground truth CSV not found — generating now..."
    echo "      (This takes ~5–15 min via Python MC; N=10,000 samples/pair)"
    GT_START=$SECONDS

    PYTHON_BIN=""
    for PY in python3 python; do
        if command -v $PY &>/dev/null && $PY -c "import numpy" 2>/dev/null; then
            PYTHON_BIN=$PY
            break
        fi
    done
    # Try .venv if system Python lacks numpy
    if [[ -z "$PYTHON_BIN" ]] && [[ -x "${PROJECT_ROOT}/.venv/bin/python3" ]]; then
        PYTHON_BIN="${PROJECT_ROOT}/.venv/bin/python3"
    fi
    if [[ -z "$PYTHON_BIN" ]]; then
        echo "  ERROR: Python with numpy not found. Install numpy or activate .venv."
        echo "  Then run manually:"
        echo "    python3 benchmark/scripts/Experiments3/generate_ground_truth.py \\"
        echo "        --data-dir $DATA_DIR --output $GT_CSV"
        exit 1
    fi

    "$PYTHON_BIN" "${SCRIPT_DIR}/generate_ground_truth.py" \
        --data-dir  "$DATA_DIR" \
        --output    "$GT_CSV" \
        --n-samples 10000 \
        --seed      42 \
        2>&1 | tee "${OUTPUT_DIR}/exp3_gt_generation.log"

    GT_ELAPSED=$(( SECONDS - GT_START ))
    N=$(( $(wc -l < "$GT_CSV") - 1 ))
    echo "      Generated ${N} rows in ${GT_ELAPSED}s."
fi

# ── Step 3: Check datasets ───────────────────────────────────────────────────
echo
echo "[3/4] Checking SimJoin datasets..."
MISSING=0
for D in easy medium hard mixed; do
    TTL="${DATA_DIR}/simjoin_${D}.ttl"
    if [[ ! -f "$TTL" ]]; then
        echo "  ERROR: Missing dataset: $TTL"
        MISSING=1
    else
        echo "  OK  simjoin_${D}.ttl"
    fi
done
if [[ $MISSING -eq 1 ]]; then
    echo "  Run: python3 benchmark/scripts/Experiments3/generate_sim_join_data.py --output-dir $DATA_DIR"
    exit 1
fi

# ── Step 4: Run benchmark ────────────────────────────────────────────────────
echo
echo "[4/4] Running SelectivityBenchmark..."
START_TS=$SECONDS

NUM_INTERATIONS="${NUM_INTERATIONS:-10}"
NUM_WARMUP="${NUM_WARMUP:-3}"
NUM_LIMIT_GRAPHS="${NUM_LIMIT_GRAPHS:-1000000}"


mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.SelectivityBenchmark" \
    "-Dexec.args=--data-dir ${DATA_DIR} --gt-csv ${GT_CSV} --output-dir ${OUTPUT_DIR}
     --iterations ${NUM_INTERATIONS} --warmup ${NUM_WARMUP} --limit-graphs ${NUM_LIMIT_GRAPHS}"  \
    2>&1 | tee "${OUTPUT_DIR}/exp3_3_run.log"

ELAPSED=$(( SECONDS - START_TS ))
echo
echo "------------------------------------------------------------------"
echo "  Exp 3.3 finished in ${ELAPSED}s  ($(date))"
OUTCSV="${OUTPUT_DIR}/exp3_3_selectivity.csv"
if [[ -f "$OUTCSV" ]]; then
    N=$(( $(wc -l < "$OUTCSV") - 1 ))
    echo "    ${OUTCSV}  (${N} data rows)"
fi
echo "=================================================================="
