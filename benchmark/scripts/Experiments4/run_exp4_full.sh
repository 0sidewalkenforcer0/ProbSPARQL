#!/usr/bin/env bash
# =============================================================================
# run_exp4_full.sh — Full Overnight Pipeline for Experiment 4 (Generalization)
#
# Runs all five sub-experiments sequentially:
#
#   Phase 0 — Maven build (once)
#   Phase 1 — Python dataset generation (histogram + Dirichlet + cross-type)
#   Phase 2 — Exp 4.1  Exp4DispatchTest     (polymorphic dispatch verification)
#   Phase 3 — Exp 4.2  Exp4MicroBenchmark   (per-operation latency)
#   Phase 4 — Exp 4.3  Exp4CrossTypeJSD     (cross-type JSD accuracy)
#   Phase 5 — Exp 4.4  Exp4EndToEnd         (end-to-end Q1/Q3 GMM vs Hist)
#   Phase 6 — Exp 4.5  Exp4DirichletDemo    (Dirichlet qualitative demo)
#   Phase 7 — Analysis  analyze_exp4.py     (charts + console tables)
#
# All results → benchmark/results/exp4_full/
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments4/run_exp4_full.sh
#
# Optional env vars:
#   OUTPUT_DIR  — override result directory  (default: benchmark/results/exp4_full)
#   DATA_DIR    — override data directory    (default: benchmark/data)
#   SKIP_BUILD  — set to 1 to skip Maven compile
#   SKIP_DATA   — set to 1 to skip Python dataset generation
#   JAVA_HOME   — override Java 21 home
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp4_full}"
DATA_DIR="${DATA_DIR:-${PROJECT_ROOT}/benchmark/data}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_DATA="${SKIP_DATA:-0}"

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

# ── Python / venv ────────────────────────────────────────────────────────────
PYTHON_BIN=""
for PY in "${PROJECT_ROOT}/.venv/bin/python3" python3 python; do
    if command -v "$PY" &>/dev/null && "$PY" -c "import numpy, rdflib" 2>/dev/null; then
        PYTHON_BIN="$PY"
        break
    fi
done

TOTAL_START=$SECONDS

echo "=================================================================="
echo "  Experiment 4 — Generalization (FULL OVERNIGHT RUN)"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Output dir   : $OUTPUT_DIR"
echo "  Data dir     : $DATA_DIR"
echo "  Java home    : $JAVA_HOME"
echo "  Python bin   : ${PYTHON_BIN:-NONE}"
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

# ── Phase 1: Dataset generation ─────────────────────────────────────────────
if [[ "${SKIP_DATA}" != "1" ]]; then
    echo
    echo ">>> Phase 1: Generating datasets..."

    if [[ -z "$PYTHON_BIN" ]]; then
        echo "    WARNING: Python with numpy+rdflib not found — skipping dataset generation."
        echo "    Pre-generated datasets must already be present in $DATA_DIR/exp4/"
    else
        echo "    1a. Histogram datasets from exp1 K=3 files..."
        "$PYTHON_BIN" "${SCRIPT_DIR}/generate_histogram_datasets.py" \
            --input-dir  "${DATA_DIR}/exp1" \
            --output-dir "${DATA_DIR}/exp4"

        echo "    1b. Dirichlet dataset (100 components, k=4)..."
        "$PYTHON_BIN" "${SCRIPT_DIR}/generate_dirichlet_dataset.py" \
            --output-dir "${DATA_DIR}/exp4"

        echo "    1c. Cross-type JSD pairs (100 GMM↔Hist + 100 Dir↔Hist)..."
        "$PYTHON_BIN" "${SCRIPT_DIR}/generate_crosstype_pairs.py" \
            --output-dir "${DATA_DIR}/exp4"

        echo "    Dataset generation complete."
    fi
fi

# ── Helper: run a Java benchmark class ─────────────────────────────────────
run_java() {
    local class="$1"
    local phase_label="$2"
    shift 2
    echo
    echo ">>> ${phase_label}: ${class}..."
    local t0=$SECONDS
    mvn -q exec:java -Dexec.mainClass="org.apache.jena.probsparql.${class}" \
        -Dexec.args="--output-dir ${OUTPUT_DIR} --data-dir ${DATA_DIR} $*" \
        2>&1 | tee "${OUTPUT_DIR}/${class}.log" | grep -E "^  |^---|^=|^>>>" || true
    echo "    Done in $(( SECONDS - t0 ))s."
}

# ── Phase 2: Exp 4.1 Dispatch verification ──────────────────────────────────
run_java "Exp4DispatchTest"  "Phase 2 (Exp 4.1)"

# ── Phase 3: Exp 4.2 Micro-benchmark ────────────────────────────────────────
run_java "Exp4MicroBenchmark" "Phase 3 (Exp 4.2)"

# ── Phase 4: Exp 4.3 Cross-type JSD accuracy ────────────────────────────────
run_java "Exp4CrossTypeJSD"  "Phase 4 (Exp 4.3)"

# ── Phase 5: Exp 4.4 End-to-end performance ─────────────────────────────────
run_java "Exp4EndToEnd"      "Phase 5 (Exp 4.4)"

# ── Phase 6: Exp 4.5 Dirichlet demonstration ────────────────────────────────
run_java "Exp4DirichletDemo" "Phase 6 (Exp 4.5)"

# ── Phase 7: Analysis ───────────────────────────────────────────────────────
echo
echo ">>> Phase 7: Analysis..."
if [[ -z "$PYTHON_BIN" ]]; then
    echo "    WARNING: Python not available — skipping analysis."
else
    "$PYTHON_BIN" "${PROJECT_ROOT}/benchmark/scripts/analyze_exp4.py" \
        --input  "$OUTPUT_DIR" \
        --output "$OUTPUT_DIR" \
        || echo "    WARNING: Analysis script returned non-zero exit code."
    echo "    Analysis complete."
fi

# ── Summary ─────────────────────────────────────────────────────────────────
ELAPSED=$(( SECONDS - TOTAL_START ))
echo
echo "=================================================================="
echo "  Experiment 4 COMPLETE"
echo "  Total time: ${ELAPSED}s  ($(( ELAPSED / 60 ))m $(( ELAPSED % 60 ))s)"
echo "  Results in: $OUTPUT_DIR"
echo "=================================================================="
echo
echo "CSV outputs:"
ls -1 "${OUTPUT_DIR}"/*.csv 2>/dev/null || echo "  (none found)"
echo
echo "Charts:"
ls -1 "${OUTPUT_DIR}"/*.png 2>/dev/null || echo "  (none generated)"
