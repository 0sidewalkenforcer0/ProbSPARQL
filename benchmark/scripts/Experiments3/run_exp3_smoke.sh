#!/usr/bin/env bash
# =============================================================================
# run_exp3_smoke.sh — Smoke Test for Experiment 3 (tiny parameter set)
#
# Runs all three sub-experiments with minimal iterations to verify correctness
# of the full pipeline end-to-end (data loading, JSD computation, CSV output,
# analysis) in ~5–15 minutes instead of the overnight 3–6 hours.
#
# Parameter reductions vs full run:
# ┌─────────────┬──────────────────┬─────────────────────────────────────────┐
# │ Benchmark   │ Full             │ Smoke                                   │
# ├─────────────┼──────────────────┼─────────────────────────────────────────┤
# │ 3.1 Acc.    │ repeat=10        │ repeat=1                                │
# │ 3.2 Conv.   │ repeat=50,       │ repeat=2,                               │
# │             │ samples up to    │ max-samples=1000 (3 pts: 100,500,1000) │
# │             │ 100k (7 pts)     │                                         │
# │ 3.3 Selec.  │ warmup=3, iter=10│ warmup=1, iter=2                        │
# └─────────────┴──────────────────┴─────────────────────────────────────────┘
# GT generation: 500 MC samples/pair instead of 10,000.
#
# What smoke test validates:
#   ✓ Java correctly loads simjoin_*.ttl datasets
#   ✓ All 5 sampling methods (V1–V5) dispatch without errors
#   ✓ CSV output files are created with correct column headers
#   ✓ Analysis Python scripts parse CSVs and generate charts
#   ✓ End-to-end pipeline shell wiring (args, paths, Java 21)
#
# What it does NOT validate:
#   ✗ Statistical accuracy / convergence (too few samples)
#   ✗ Timing stability (too few iterations)
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments3/run_exp3_smoke.sh
#
# Optional env vars:
#   SKIP_BUILD  — set to 1 to skip Maven compile
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

OUTPUT_DIR="${PROJECT_ROOT}/benchmark/results/exp3_smoke"
DATA_DIR="${PROJECT_ROOT}/benchmark/data"
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

TOTAL_START=$SECONDS

echo "=================================================================="
echo "  Experiment 3 — SMOKE TEST  (fast validation)"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Output dir   : $OUTPUT_DIR"
echo "  Java home    : $JAVA_HOME"
echo "  Start time   : $(date)"
echo ""
echo "  Smoke parameters:"
echo "    Exp 3.1 repeat      = 1     (full: 10)"
echo "    Exp 3.2 repetitions = 2     (full: 50),  max-samples = 1000 (full: 100k)"
echo "    Exp 3.3 warmup      = 1     (full: 3),   iterations  = 2    (full: 10)"
echo "    Exp 3.3 limit-graphs= 10    (full: 200 per side → 40k pairs)"
echo "    GT generation       = 500 samples/pair   (full: 10000)"
echo "=================================================================="
echo

cd "$PROJECT_ROOT"
mkdir -p "$OUTPUT_DIR"

# ── Step 0: Build ──────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo ">>> Step 0: Building with Maven..."
    mvn -q package -DskipTests
    echo "    Build complete."
    echo
fi

# ── Step 1: Ground Truth CSV (500 samples/pair — fast) ───────────────────────
echo ">>> Step 1: Generating Ground Truth CSV (N=500 samples/pair)..."
PYTHON_BIN=""
for PY in python3 python; do
    if command -v $PY &>/dev/null && $PY -c "import numpy" 2>/dev/null; then
        PYTHON_BIN=$PY; break
    fi
done
[[ -z "$PYTHON_BIN" ]] && [[ -x "${PROJECT_ROOT}/.venv/bin/python3" ]] \
    && PYTHON_BIN="${PROJECT_ROOT}/.venv/bin/python3"
if [[ -z "$PYTHON_BIN" ]]; then
    echo "  ERROR: Python with numpy not found."
    exit 1
fi

GT_START=$SECONDS
"$PYTHON_BIN" "${SCRIPT_DIR}/generate_ground_truth.py" \
    --data-dir  "$DATA_DIR" \
    --output    "$GT_CSV" \
    --n-samples 500 \
    --seed      42 \
    2>&1

echo "    Done in $(( SECONDS - GT_START ))s."
N=$(( $(wc -l < "$GT_CSV") - 1 ))
echo "    Output: $GT_CSV  (${N} rows)"
echo

# ── Step 2: Exp 3.1 (repeat=1) ───────────────────────────────────────────────
echo ">>> Step 2: Exp 3.1 — Classification Accuracy  [repeat=1]"
P2_START=$SECONDS
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.ClassificationAccuracyBenchmark" \
    "-Dexec.args=--data-dir ${DATA_DIR} --output-dir ${OUTPUT_DIR} --repeat 1" \
    2>&1 | tee "${OUTPUT_DIR}/exp3_1_smoke.log"
echo "    Done in $(( SECONDS - P2_START ))s."

# Quick sanity check
if [[ -f "${OUTPUT_DIR}/exp3_1_classification.csv" ]]; then
    N=$(( $(wc -l < "${OUTPUT_DIR}/exp3_1_classification.csv") - 1 ))
    echo "    exp3_1_classification.csv: ${N} rows"
    head -3 "${OUTPUT_DIR}/exp3_1_classification.csv"
fi
echo

# ── Step 3: Exp 3.2 (repetitions=2, max-samples=1000) ────────────────────────
echo ">>> Step 3: Exp 3.2 — Convergence Analysis  [repetitions=2, max-samples=1000]"
P3_START=$SECONDS
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.MultiMethodConvergenceBenchmark" \
    "-Dexec.args=--data-dir ${DATA_DIR} --output-dir ${OUTPUT_DIR} --repetitions 2 --max-samples 1000" \
    2>&1 | tee "${OUTPUT_DIR}/exp3_2_smoke.log"
echo "    Done in $(( SECONDS - P3_START ))s."

if [[ -f "${OUTPUT_DIR}/exp3_2_convergence_multimethod.csv" ]]; then
    N=$(( $(wc -l < "${OUTPUT_DIR}/exp3_2_convergence_multimethod.csv") - 1 ))
    echo "    exp3_2_convergence_multimethod.csv: ${N} rows"
    head -3 "${OUTPUT_DIR}/exp3_2_convergence_multimethod.csv"
fi
echo

# ── Step 4: Exp 3.3 (warmup=1, iterations=2, limit-graphs=10) ───────────────
echo ">>> Step 4: Exp 3.3 — Selectivity Sensitivity  [warmup=1, iterations=2, limit-graphs=10]"
P4_START=$SECONDS
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.SelectivityBenchmark" \
    "-Dexec.args=--data-dir ${DATA_DIR} --gt-csv ${GT_CSV} --output-dir ${OUTPUT_DIR} --warmup 1 --iterations 2 --limit-graphs 10" \
    2>&1 | tee "${OUTPUT_DIR}/exp3_3_smoke.log"
echo "    Done in $(( SECONDS - P4_START ))s."

if [[ -f "${OUTPUT_DIR}/exp3_3_selectivity.csv" ]]; then
    N=$(( $(wc -l < "${OUTPUT_DIR}/exp3_3_selectivity.csv") - 1 ))
    echo "    exp3_3_selectivity.csv: ${N} rows"
    head -3 "${OUTPUT_DIR}/exp3_3_selectivity.csv"
fi
echo

# ── Step 5: Analysis ─────────────────────────────────────────────────────────
echo ">>> Step 5: Analysis (charts + tables)"
ANALYZE_SCRIPT="${SCRIPT_DIR}/analyze_exp3.sh"
if [[ -x "$ANALYZE_SCRIPT" ]]; then
    OUTPUT_DIR="$OUTPUT_DIR" bash "$ANALYZE_SCRIPT" 2>&1 | grep -v "^$"
else
    echo "    WARNING: analyze_exp3.sh not found; skipping."
fi

# ── Summary ──────────────────────────────────────────────────────────────────
TOTAL_ELAPSED=$(( SECONDS - TOTAL_START ))
echo
echo "=================================================================="
echo "  SMOKE TEST COMPLETE"
echo "  Total time: ${TOTAL_ELAPSED}s  ($(( TOTAL_ELAPSED / 60 ))min $(( TOTAL_ELAPSED % 60 ))s)"
echo "  End time  : $(date)"
echo ""
echo "  Result files in ${OUTPUT_DIR}:"
for F in \
    simjoin_ground_truth.csv \
    exp3_1_classification.csv \
    exp3_1_per_pair.csv \
    exp3_2_convergence_multimethod.csv \
    exp3_3_selectivity.csv; do
    if [[ -f "${OUTPUT_DIR}/${F}" ]]; then
        N=$(( $(wc -l < "${OUTPUT_DIR}/${F}") - 1 ))
        printf "    %-45s  (%d rows)\n" "$F" "$N"
    else
        printf "    %-45s  MISSING\n" "$F"
    fi
done
echo ""
echo "  Charts:"
for PNG in "${OUTPUT_DIR}"/*.png; do
    [[ -f "$PNG" ]] && echo "    $(basename "$PNG")" || true
done
echo "=================================================================="
echo ""
echo "  If all files exist and row counts look right, run the full overnight:"
echo "    bash benchmark/scripts/Experiments3/run_exp3_full.sh"
echo "=================================================================="
