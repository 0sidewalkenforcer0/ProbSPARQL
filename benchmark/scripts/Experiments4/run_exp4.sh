#!/usr/bin/env bash
# =============================================================================
# run_exp4.sh — Full Pipeline for Experiment 4 (Generalization)
#
# Runs all five sub-experiments sequentially against remote Fuseki endpoints.
# All required Exp4 datasets must already be loaded on the Fujitsu server.
#
# Required remote service names:
#   exp4_dispatch_gmm_K3, exp4_dispatch_hist_B50, exp4_dispatch_dir_k4
#   exp4_micro_gmm_K3
#   exp4_micro_hist_B20, exp4_micro_hist_B50, exp4_micro_hist_B100
#   exp4_micro_dir_k4, exp4_micro_dir_k10, exp4_micro_dir_k20
#   exp4_crosstype_gmm_hist, exp4_crosstype_dir_hist
#   exp1_E3_K3, exp1_E5_K3, exp1_E7_K3
#   exp4_E3_hist_B50, exp4_E3_hist_B100
#   exp4_E5_hist_B50, exp4_E5_hist_B100
#   exp4_E7_hist_B50, exp4_E7_hist_B100
#   exp4_dirichlet
#
#   Phase 0 — Maven build (once)
#   Phase 1 — Exp 4.1  Exp4DispatchTest     (polymorphic dispatch verification)
#   Phase 2 — Exp 4.2  Exp4MicroBenchmark   (remote per-operation latency)
#   Phase 3 — Exp 4.3  Exp4CrossTypeJSD     (remote cross-type JSD)
#   Phase 4 — Exp 4.4  Exp4EndToEnd         (remote end-to-end Q2/Q4)
#   Phase 5 — Exp 4.5  Exp4DirichletDemo    (remote Dirichlet qualitative demo)
#   Phase 6 — Analysis  analyze_exp4.py     (charts + console tables)
#
# All results → benchmark/results/exp4/
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments4/run_exp4.sh
#
# Optional env vars:
#   ENDPOINT_TEMPLATE — required, e.g. https://fujitsu:3030/{dataset}/query
#   OUTPUT_DIR        — override result directory  (default: benchmark/results/exp4)
#   SKIP_BUILD        — set to 1 to skip Maven compile
#   JAVA_HOME         — override Java 21 home
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp4}"
ENDPOINT_TEMPLATE="${ENDPOINT_TEMPLATE:-}"
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
echo "  Experiment 4 — Generalization (REMOTE FUSEKI RUN)"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Output dir   : $OUTPUT_DIR"
echo "  Endpoint tpl : ${ENDPOINT_TEMPLATE:-<required>}"
echo "  Java home    : $JAVA_HOME"
echo "  Python bin   : ${PYTHON_BIN:-NONE}"
echo "  Start time   : $(date)"
echo "=================================================================="
echo

cd "$PROJECT_ROOT"
mkdir -p "$OUTPUT_DIR"

if [[ -z "${ENDPOINT_TEMPLATE}" ]]; then
    echo "ERROR: ENDPOINT_TEMPLATE is required, e.g. https://fujitsu:3030/{dataset}/query" >&2
    exit 1
fi

# ── Phase 0: Build ──────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo ">>> Phase 0: Building with Maven..."
    mvn -q package -DskipTests
    echo "    Build complete."
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
        -Dexec.args="--output-dir ${OUTPUT_DIR} --endpoint-template ${ENDPOINT_TEMPLATE} $*" \
        2>&1 | tee "${OUTPUT_DIR}/${class}.log" | grep -E "^  |^---|^=|^>>>" || true
    echo "    Done in $(( SECONDS - t0 ))s."
}

# ── Phase 1: Exp 4.1 Dispatch verification ──────────────────────────────────
run_java "Exp4DispatchTest"  "Phase 1 (Exp 4.1)"

# ── Phase 2: Exp 4.2 Micro-benchmark ────────────────────────────────────────
run_java "Exp4MicroBenchmark" "Phase 2 (Exp 4.2)"

# ── Phase 3: Exp 4.3 Cross-type JSD accuracy ────────────────────────────────
run_java "Exp4CrossTypeJSD"  "Phase 3 (Exp 4.3)"

# ── Phase 4: Exp 4.4 End-to-end performance ─────────────────────────────────
run_java "Exp4EndToEnd"      "Phase 4 (Exp 4.4)"

# ── Phase 5: Exp 4.5 Dirichlet demonstration ────────────────────────────────
run_java "Exp4DirichletDemo" "Phase 5 (Exp 4.5)"

# ── Phase 6: Analysis ───────────────────────────────────────────────────────
echo
echo ">>> Phase 6: Analysis..."
if [[ -z "$PYTHON_BIN" ]]; then
    echo "    WARNING: Python not available — skipping analysis."
else
    "$PYTHON_BIN" "${PROJECT_ROOT}/benchmark/scripts/Experiments4/analyze_exp4.py" \
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
