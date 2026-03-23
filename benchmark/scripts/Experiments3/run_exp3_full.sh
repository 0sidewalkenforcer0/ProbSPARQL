#!/usr/bin/env bash
# =============================================================================
# run_exp3_full.sh — Full Overnight Pipeline for Experiment 3
#
# Runs all three sub-experiments sequentially, then runs analysis on each:
#
#   Phase 0 — Maven build (once)
#   Phase 1 — generate_ground_truth.py   (~5–15 min, prerequisite for 3.3)
#   Phase 2 — Exp 3.1 ClassificationAccuracyBenchmark   (~30–90 min)
#   Phase 3 — Exp 3.2 MultiMethodConvergenceBenchmark   (~1–2 hr)
#   Phase 4 — Exp 3.3 SelectivityBenchmark               (~1–3 hr)
#   Phase 5 — Analysis (3 Python scripts → 9 charts)
#
# All results → benchmark/results/exp3_full/
#
# Estimated total runtime: 3–6 hours depending on machine speed.
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments3/run_exp3_full.sh
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

TOTAL_START=$SECONDS

echo "=================================================================="
echo "  Experiment 3 — Sampling Methods (FULL OVERNIGHT RUN)"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Output dir   : $OUTPUT_DIR"
echo "  Data dir     : $DATA_DIR"
echo "  Java home    : $JAVA_HOME"
echo "  Start time   : $(date)"
echo "=================================================================="
echo

cd "$PROJECT_ROOT"
mkdir -p "$OUTPUT_DIR"

# ── Phase 0: Build ──────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo ">>> Phase 0: Building with Maven..."
    mvn -q package -DskipTests
    echo "    Build complete."
fi

# ── Phase 1: Ground Truth CSV ────────────────────────────────────────────────
echo
echo ">>> Phase 1: Ground Truth CSV generation..."
if [[ -f "$GT_CSV" ]]; then
    N=$(( $(wc -l < "$GT_CSV") - 1 ))
    echo "    Already exists  (${N} pairs) — skipping."
else
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
        --n-samples 10000 \
        --seed      42 \
        2>&1 | tee "${OUTPUT_DIR}/exp3_gt_generation.log"
    echo "    Done in $(( SECONDS - GT_START ))s."
fi

# ── Phase 2: Exp 3.1 ────────────────────────────────────────────────────────
echo
echo ">>> Phase 2: Exp 3.1 — Classification Accuracy"
P2_START=$SECONDS
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.ClassificationAccuracyBenchmark" \
    "-Dexec.args=--data-dir ${DATA_DIR} --output-dir ${OUTPUT_DIR}" \
    2>&1 | tee "${OUTPUT_DIR}/exp3_1_run.log"
echo "    Exp 3.1 done in $(( SECONDS - P2_START ))s."

# ── Phase 3: Exp 3.2 ────────────────────────────────────────────────────────
echo
echo ">>> Phase 3: Exp 3.2 — Convergence Analysis"
P3_START=$SECONDS
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.MultiMethodConvergenceBenchmark" \
    "-Dexec.args=--data-dir ${DATA_DIR} --output-dir ${OUTPUT_DIR}" \
    2>&1 | tee "${OUTPUT_DIR}/exp3_2_run.log"
echo "    Exp 3.2 done in $(( SECONDS - P3_START ))s."

# ── Phase 4: Exp 3.3 ────────────────────────────────────────────────────────
echo
echo ">>> Phase 4: Exp 3.3 — Selectivity Sensitivity"
P4_START=$SECONDS
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.SelectivityBenchmark" \
    "-Dexec.args=--data-dir ${DATA_DIR} --gt-csv ${GT_CSV} --output-dir ${OUTPUT_DIR} --limit-graphs 50" \
    2>&1 | tee "${OUTPUT_DIR}/exp3_3_run.log"
echo "    Exp 3.3 done in $(( SECONDS - P4_START ))s."

# ── Phase 5: Analysis ───────────────────────────────────────────────────────
echo
echo ">>> Phase 5: Analysis (charts + tables)"
ANALYZE_SCRIPT="${SCRIPT_DIR}/analyze_exp3.sh"
if [[ -x "$ANALYZE_SCRIPT" ]]; then
    OUTPUT_DIR="$OUTPUT_DIR" DATA_DIR="$DATA_DIR" bash "$ANALYZE_SCRIPT"
else
    echo "  WARNING: analyze_exp3.sh not found or not executable — skipping analysis."
fi

# ── Summary ─────────────────────────────────────────────────────────────────
TOTAL_ELAPSED=$(( SECONDS - TOTAL_START ))
echo
echo "=================================================================="
echo "  ALL PHASES COMPLETE"
echo "  Total runtime : ${TOTAL_ELAPSED}s  ($(( TOTAL_ELAPSED / 60 ))min)"
echo "  End time      : $(date)"
echo "  Output dir    : $OUTPUT_DIR"
echo ""
echo "  Result files:"
for F in \
    simjoin_ground_truth.csv \
    exp3_1_classification.csv \
    exp3_1_per_pair.csv \
    exp3_2_convergence_multimethod.csv \
    exp3_3_selectivity.csv; do
    if [[ -f "${OUTPUT_DIR}/${F}" ]]; then
        N=$(( $(wc -l < "${OUTPUT_DIR}/${F}") - 1 ))
        printf "    %-45s  (%d rows)\n" "$F" "$N"
    fi
done
echo ""
echo "  Charts:"
for PNG in "${OUTPUT_DIR}"/*.png; do
    [[ -f "$PNG" ]] && echo "    $(basename "$PNG")"
done
echo "=================================================================="
