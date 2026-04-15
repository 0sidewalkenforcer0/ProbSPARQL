#!/usr/bin/env bash
# =============================================================================
# analyze_exp3.sh — Analysis and visualization for Experiment 3
#
# Reads CSVs produced by run_exp3_1/2/3.sh (or run_exp3_full.sh) and generates
# all charts and summary tables for all three sub-experiments.
#
# Sub-experiment 3.1 — Classification Accuracy:
#   Input:  exp3_1_classification.csv
#   Charts: exp3_1_accuracy_grouped_bar.png
#           exp3_1_f1_heatmap.png
#           exp3_1_latency.png
#
# Sub-experiment 3.2 — Convergence Analysis:
#   Input:  exp3_2_convergence_multimethod.csv
#   Charts: exp3_2_jsd_convergence.png
#           exp3_2_abs_error.png
#           exp3_2_time.png
#
# Sub-experiment 3.3 — Selectivity Sensitivity:
#   Input:  exp3_3_selectivity.csv
#   Charts: exp3_3_time_vs_theta.png
#           exp3_3_count_vs_theta.png
#           exp3_3_accuracy_vs_theta.png
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments3/analyze_exp3.sh
#   bash benchmark/scripts/Experiments3/analyze_exp3.sh --results-dir benchmark/results/exp3_full
#   bash benchmark/scripts/Experiments3/analyze_exp3.sh --only 3.2
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

RESULTS_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp3_full}"
ONLY=""   # if set to "3.1", "3.2", or "3.3" — run only that sub-experiment

# ── Argument parsing ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --results-dir) RESULTS_DIR="$2"; shift 2 ;;
        --output-dir)  RESULTS_DIR="$2"; shift 2 ;;   # alias
        --only)        ONLY="$2";        shift 2 ;;
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
echo "  Experiment 3 — Analysis"
echo "=================================================================="
echo "  Results dir  : $RESULTS_DIR"
echo "  Python       : $PYTHON_BIN"
echo "  Filter       : ${ONLY:-all sub-experiments}"
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

# ── Exp 3.1 ──────────────────────────────────────────────────────────────────
if [[ -z "$ONLY" ]] || [[ "$ONLY" == "3.1" ]]; then
    run_analysis \
        "Exp 3.1: Classification Accuracy" \
        "${SCRIPTS}/analyze_exp3_1_accuracy.py" \
        "${RESULTS_DIR}/exp3_1_classification.csv"
fi

# ── Exp 3.2 ──────────────────────────────────────────────────────────────────
if [[ -z "$ONLY" ]] || [[ "$ONLY" == "3.2" ]]; then
    run_analysis \
        "Exp 3.2: Convergence Analysis" \
        "${SCRIPTS}/analyze_exp3_2_convergence.py" \
        "${RESULTS_DIR}/exp3_2_convergence_multimethod.csv"
fi

# ── Exp 3.3 ──────────────────────────────────────────────────────────────────
if [[ -z "$ONLY" ]] || [[ "$ONLY" == "3.3" ]]; then
    run_analysis \
        "Exp 3.3: Selectivity Sensitivity" \
        "${SCRIPTS}/analyze_exp3_3_selectivity.py" \
        "${RESULTS_DIR}/exp3_3_selectivity.csv"
fi

# ── Chart summary ─────────────────────────────────────────────────────────────
echo "=================================================================="
echo "  Charts generated:"
for PNG in "${RESULTS_DIR}"/*.png; do
    [[ -f "$PNG" ]] && echo "    $(basename "$PNG")"
done
echo "=================================================================="
