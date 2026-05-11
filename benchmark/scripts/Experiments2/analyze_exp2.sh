#!/usr/bin/env bash
# =============================================================================
# analyze_exp2.sh — Analysis and visualization for Experiment 2
#
# Reads CSV outputs from Exp2Benchmark and produces:
#   Tables:
#     exp2_summary.csv       — merged timings, speedups, consistency per config
#   Charts:
#     exp2_speedup_vs_uf.png       — DIVJOIN vs InEngine_CheapFirst and InEngine_JSDFirst
#     exp2_speedup_vs_npairs.png   — speedup vs dataset size
#     exp2_pruning_heatmap.png     — pruning rate heatmap
#
# Also runs validation checks (recall, consistency, pruning sanity).
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments2/analyze_exp2.sh
#   bash benchmark/scripts/Experiments2/analyze_exp2.sh \
#       --results-dir benchmark/results/exp2
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

RESULTS_DIR="${PROJECT_ROOT}/benchmark/results/exp2"
OUTPUT_DIR=""  # defaults to RESULTS_DIR if not set

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --results-dir) RESULTS_DIR="$2"; shift 2 ;;
        --output-dir)  OUTPUT_DIR="$2";  shift 2 ;;
        *) echo "[ERROR] Unknown argument: $1" >&2; exit 1 ;;
    esac
done

OUTPUT_DIR="${OUTPUT_DIR:-$RESULTS_DIR}"

cd "$PROJECT_ROOT"

echo "=================================================================="
echo "  Experiment 2 — Analysis"
echo "=================================================================="
echo "  Results dir  : $RESULTS_DIR"
echo "  Output dir   : $OUTPUT_DIR"
echo

# Check required input files
REQUIRED=(
  exp2_inengine_cheapfirst.csv
  exp2_inengine_jsdfirst.csv
  exp2_similarityjoin.csv
  exp2_pruning_stats.csv
)
MISSING=0
for f in "${REQUIRED[@]}"; do
    if [[ ! -f "${RESULTS_DIR}/$f" ]]; then
        echo "[ERROR] Missing: ${RESULTS_DIR}/$f"
        MISSING=1
    fi
done
if [[ $MISSING -eq 1 ]]; then
    echo "        Run run_exp2.sh first."
    exit 1
fi

# ── Validate ─────────────────────────────────────────────────────────────────
echo "Running validation checks..."
if python3 "${PROJECT_ROOT}/benchmark/scripts/Experiments2/validate_exp2.py" \
        --results-dir "$RESULTS_DIR"; then
    echo "  Validation passed."
else
    echo "  [WARN] Validation issues detected — continuing with analysis."
fi
echo

# ── Analyze ───────────────────────────────────────────────────────────────────
echo "Running analysis..."
python3 "${PROJECT_ROOT}/benchmark/scripts/Experiments2/analyze_exp2.py" \
    --results-dir "$RESULTS_DIR" \
    --output-dir  "$OUTPUT_DIR"

echo
echo "=================================================================="
echo "  Analysis complete."
echo "  Output directory: $OUTPUT_DIR"
echo "=================================================================="
ls -lh "${OUTPUT_DIR}"/*.csv "${OUTPUT_DIR}"/*.png 2>/dev/null || true
