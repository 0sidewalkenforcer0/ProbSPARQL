#!/usr/bin/env bash
# =============================================================================
# run_exp2.sh — Orchestrate Experiment 2 (3-way: A vs B vs C)
#
# Steps:
#   1. Build the project
#   2. Run Exp2Benchmark.java  → exp2_a.csv, exp2_b_fetch.csv, exp2_c.csv,
#                                exp2_pruning_stats.csv, exp2_calibration.csv,
#                                exp2_pairs_*.json
#   3. Run exp2_external_v2.py → exp2_b_python.csv
#   4. Run analyze_exp2_v2.py  → tables + 4 charts
#
# Usage:
#   cd /path/to/ProbSPARQL
#   bash benchmark/scripts/experiments2/run_exp2.sh
#
#   Optional env vars:
#     OUTPUT_DIR   destination directory (default: benchmark/results/exp2)
#     SKIP_BUILD   set to 1 to skip mvn package
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp2}"
SKIP_BUILD="${SKIP_BUILD:-0}"

echo "============================================================"
echo " Experiment 2 — In-Engine vs External (3-way)"
echo "  Project : ${PROJECT_ROOT}"
echo "  Results : ${OUTPUT_DIR}"
echo "============================================================"
echo

mkdir -p "${OUTPUT_DIR}"

cd "${PROJECT_ROOT}"

# ── Step 1: Build ───────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo ">>> Step 1: Building project..."
    mvn -q package -DskipTests
    echo "    Done."
else
    echo ">>> Step 1: Skipping build (SKIP_BUILD=1)"
fi
echo

# ── Step 2: Java benchmark ──────────────────────────────────────────────────
echo ">>> Step 2: Running Exp2Benchmark.java..."
mvn -q exec:java \
    -Dprobsparql.mode=GT_10K \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp2Benchmark" \
    -Dexec.args="--output-dir ${OUTPUT_DIR}"
echo "    Done."
echo

# ── Step 3: Python external baseline ────────────────────────────────────────
echo ">>> Step 3: Running Python external baseline..."
python3 "${SCRIPT_DIR}/exp2_external_v2.py" \
    --results-dir "${OUTPUT_DIR}"
echo "    Done."
echo

# ── Step 4: Analysis + charts ────────────────────────────────────────────────
echo ">>> Step 4: Running analysis and generating charts..."
python3 "${SCRIPT_DIR}/analyze_exp2_v2.py" \
    --results-dir "${OUTPUT_DIR}" \
    --output-dir  "${OUTPUT_DIR}"
echo "    Done."
echo

echo "============================================================"
echo " Experiment 2 complete."
echo " Results in: ${OUTPUT_DIR}"
ls -lh "${OUTPUT_DIR}"/*.csv "${OUTPUT_DIR}"/*.png 2>/dev/null || true
echo "============================================================"
