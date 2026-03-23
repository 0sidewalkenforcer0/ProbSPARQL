#!/usr/bin/env bash
# =============================================================================
# analyze_exp1.sh — Analysis and visualization for Experiment 1
#
# Reads exp1_raw.csv produced by run_exp1_full.sh and generates:
#   Tables:
#     exp1_table1_Q{1-4}.csv   — Absolute latency (ms): rows=scale, cols=DET/K
#     exp1_table2_overhead.csv — Overhead ratios at K=3
#   Charts:
#     exp1_chart1_scalability.png — Latency vs scale (one subplot per query)
#     exp1_chart2_complexity.png  — Latency vs K at fixed scale E3
#     exp1_chart3_overhead.png    — Overhead ratio grouped-bar by query
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments1/analyze_exp1.sh
#   bash benchmark/scripts/Experiments1/analyze_exp1.sh \
#       --results-dir benchmark/results/exp1_full
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

RESULTS_DIR="${PROJECT_ROOT}/benchmark/results/exp1_full"
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
echo "  Experiment 1 — Analysis"
echo "=================================================================="
echo "  Results dir  : $RESULTS_DIR"
echo "  Output dir   : $OUTPUT_DIR"
echo

RAW_CSV="${RESULTS_DIR}/exp1_raw.csv"
if [[ ! -f "$RAW_CSV" ]]; then
    echo "[ERROR] Raw CSV not found: $RAW_CSV"
    echo "        Run run_exp1_full.sh first."
    exit 1
fi

echo "Running analysis..."
python3 "${PROJECT_ROOT}/benchmark/scripts/analyze_exp1_proper.py" \
    --input  "$RAW_CSV" \
    --output "$OUTPUT_DIR"

echo
echo "=================================================================="
echo "  Analysis complete."
echo "  Output directory: $OUTPUT_DIR"
echo "=================================================================="
ls -lh "${OUTPUT_DIR}"/*.csv "${OUTPUT_DIR}"/*.png 2>/dev/null || true
