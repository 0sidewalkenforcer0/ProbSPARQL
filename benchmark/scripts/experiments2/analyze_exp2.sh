#!/usr/bin/env bash
# =============================================================================
# analyze_exp2.sh — Analysis and visualization for Experiment 2 v5
#
# Reads CSV outputs from Exp2BenchmarkV5 and produces:
#   Tables:
#     exp2v5_summary.csv     — merged speedups, recall, pruning per config
#   Charts:
#     exp2v5_speedup_vs_uf.png     — C/A and C/B speedup vs unimodalFrac
#     exp2v5_speedup_vs_npairs.png — speedup vs dataset size
#     exp2v5_pruning_heatmap.png   — pruning rate heatmap
#
# Also runs validation checks (recall, consistency, pruning sanity).
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments2/analyze_exp2.sh
#   bash benchmark/scripts/Experiments2/analyze_exp2.sh \
#       --results-dir benchmark/results/exp2_full
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

RESULTS_DIR="${PROJECT_ROOT}/benchmark/results/exp2_full"
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
echo "  Experiment 2 v5 — Analysis"
echo "=================================================================="
echo "  Results dir  : $RESULTS_DIR"
echo "  Output dir   : $OUTPUT_DIR"
echo

# Check required input files
REQUIRED=(exp2v5_a.csv exp2v5_b_java.csv exp2v5_c.csv exp2v5_pruning_stats.csv)
MISSING=0
for f in "${REQUIRED[@]}"; do
    if [[ ! -f "${RESULTS_DIR}/$f" ]]; then
        echo "[ERROR] Missing: ${RESULTS_DIR}/$f"
        MISSING=1
    fi
done
if [[ $MISSING -eq 1 ]]; then
    echo "        Run run_exp2_full.sh first."
    exit 1
fi

# ── Validate ─────────────────────────────────────────────────────────────────
echo "Running validation checks..."
if python3 "${PROJECT_ROOT}/benchmark/scripts/exp2_validate_v5.py" \
        --results-dir "$RESULTS_DIR"; then
    echo "  Validation passed."
else
    echo "  [WARN] Validation issues detected — continuing with analysis."
fi
echo

# ── Analyze ───────────────────────────────────────────────────────────────────
echo "Running analysis..."
python3 "${PROJECT_ROOT}/benchmark/scripts/analyze_exp2_v5.py" \
    --results-dir "$RESULTS_DIR" \
    --output-dir  "$OUTPUT_DIR"

echo
echo "=================================================================="
echo "  Analysis complete."
echo "  Output directory: $OUTPUT_DIR"
echo "=================================================================="
ls -lh "${OUTPUT_DIR}"/*.csv "${OUTPUT_DIR}"/*.png 2>/dev/null || true
