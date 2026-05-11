#!/usr/bin/env bash
# =============================================================================
# analyze_exp3.sh — Analysis and visualization for Exp3
#
# Reads exp3_classification.csv and generates the Exp3 charts.
# Paper-aligned Exp3 outputs use K=5 and N=2,400 aligned pairs per
# Easy/Medium/Hard/Mixed workload.
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments3/analyze_exp3.sh
#   bash benchmark/scripts/Experiments3/analyze_exp3.sh --results-dir benchmark/results/exp3
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

RESULTS_DIR="${RESULTS_DIR:-${PROJECT_ROOT}/benchmark/results/exp3}"

# ── Argument parsing ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --results-dir) RESULTS_DIR="$2"; shift 2 ;;
        --output-dir)  RESULTS_DIR="$2"; shift 2 ;;   # alias
        *) echo "[ERROR] Unknown argument: $1" >&2; exit 1 ;;
    esac
done

# ── Python interpreter detection ─────────────────────────────────────────────
PYTHON_BIN=""
for PY in python3 python; do
    if command -v $PY &>/dev/null && $PY -c "import numpy, matplotlib" 2>/dev/null; then
        PYTHON_BIN=$PY; break
    fi
done
if [[ -z "$PYTHON_BIN" ]] && [[ -x "${PROJECT_ROOT}/.venv/bin/python3" ]]; then
    PYTHON_BIN="${PROJECT_ROOT}/.venv/bin/python3"
fi
if [[ -z "$PYTHON_BIN" ]]; then
    echo "[ERROR] Python with numpy+matplotlib not found."
    echo "  Install: pip install numpy matplotlib  or activate .venv"
    exit 1
fi

cd "$PROJECT_ROOT"

echo "=================================================================="
echo "  Exp3 — Analysis"
echo "=================================================================="
echo "  Results dir  : $RESULTS_DIR"
echo "  Python       : $PYTHON_BIN"
echo

SCRIPTS="${PROJECT_ROOT}/benchmark/scripts/Experiments3"

# ── Helper: run one analysis script ──────────────────────────────────────────
run_analysis() {
    local label="$1"
    local script="$2"
    local input_csv="$3"

    echo "----- ${label} -----"
    if [[ ! -f "$input_csv" ]]; then
        echo "  SKIP: Input CSV not found: $input_csv"
        echo "  (Run the corresponding benchmark first.)"
        echo
        return 0
    fi

    "$PYTHON_BIN" "$script" \
        --input  "$input_csv" \
        --output "$RESULTS_DIR" \
        2>&1
    echo
}

run_analysis \
    "Exp3: Classification Accuracy" \
    "${SCRIPTS}/analyze_exp3.py" \
    "${RESULTS_DIR}/exp3_classification.csv"

# ── Chart summary ─────────────────────────────────────────────────────────────
echo "=================================================================="
echo "  Charts generated:"
for PNG in "${RESULTS_DIR}"/*.png; do
    [[ -f "$PNG" ]] && echo "    $(basename "$PNG")"
done
echo "=================================================================="
