#!/usr/bin/env bash
# =============================================================================
# run_exp2_full.sh — Full overnight run for Experiment 2 v5
#
# Experiment 2: Filter-Pushdown Advantage with Mixed-K Datasets
#   Three-way comparison (A vs B vs C) across:
#     N_PAIRS ∈ {100, 500, 1000, 5000, 10000}
#     unimodalFrac ∈ {0.2, 0.5, 0.8}
#     selectivity ∈ {10pct, 50pct, 90pct}
#   = 45 total configurations
#
# Full configuration:
#   Warmup : 3 runs  (discarded for JIT warm-up)
#   Measure: 10 runs (median of 10 reported)
#   Mode   : GT_10K  (10,000-sample Monte Carlo JSD — high accuracy)
#
# Estimated runtime: 6–12 hours depending on machine speed
#   (GT_10K at n=10000 takes ~30s/run × 10 runs × 3 approaches × 45 configs)
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments2/run_exp2_full.sh
#
# Optional env vars:
#   OUTPUT_DIR   — override result directory (default: benchmark/results/exp2_full)
#   SKIP_BUILD   — set to 1 to skip Maven package step
#   MODE         — JSD mode override (default: GT_10K)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp2_full}"
SKIP_BUILD="${SKIP_BUILD:-0}"
MODE="${MODE:-GT_10K}"

WARMUP_RUNS=3
BENCHMARK_RUNS=10

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

echo "=================================================================="
echo "  Experiment 2 v5 — Filter-Pushdown Advantage  [FULL RUN]"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Output dir   : $OUTPUT_DIR"
echo "  Java home    : $JAVA_HOME"
echo "  JSD mode     : $MODE"
echo "  Warmup       : $WARMUP_RUNS"
echo "  Runs         : $BENCHMARK_RUNS"
echo "  Configs      : 5 n_pairs × 3 unimodalFrac × 3 selectivity = 45"
echo "  Start time   : $(date)"
echo "------------------------------------------------------------------"
echo

cd "$PROJECT_ROOT"
mkdir -p "$OUTPUT_DIR"

# ── Step 1: Build ───────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo "[1/4] Building with Maven (skipping tests)..."
    mvn -q package -DskipTests
    echo "      Build complete."
else
    echo "[1/4] Build skipped (SKIP_BUILD=1)."
fi

# ── Step 2: Run benchmark ───────────────────────────────────────────────────
echo
echo "[2/4] Running Exp2BenchmarkV5 (mode=$MODE, warmup=$WARMUP_RUNS, runs=$BENCHMARK_RUNS)..."
echo "      All 45 configurations × 3 approaches — this will take several hours."
echo "      Progress is logged to: ${OUTPUT_DIR}/exp2_run.log"
echo

START_EPOCH=$(date +%s)

mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp2BenchmarkV5" \
    "-Dexec.args=--output-dir ${OUTPUT_DIR} \
                 --mode ${MODE} \
                 --warmup ${WARMUP_RUNS} \
                 --runs ${BENCHMARK_RUNS}" \
    -Dprobsparql.simjoin.pruning=true \
    -Dprobsparql.simjoin.deduplicate=true \
    2>&1 | tee "${OUTPUT_DIR}/exp2_run.log"

END_EPOCH=$(date +%s)
ELAPSED=$(( END_EPOCH - START_EPOCH ))
echo
echo "      Benchmark completed in ${ELAPSED}s  ($(date))"

# ── Step 3: Validate ────────────────────────────────────────────────────────
echo
echo "[3/4] Validating results (recall / consistency checks)..."

if python3 "${PROJECT_ROOT}/benchmark/scripts/exp2_validate_v5.py" \
        --results-dir "$OUTPUT_DIR" \
        2>&1 | tee "${OUTPUT_DIR}/exp2_validate.log"; then
    echo "      Validation passed."
else
    echo "      [WARN] Validation reported issues — check exp2_validate.log"
fi

# ── Step 4: Run analysis ────────────────────────────────────────────────────
echo
echo "[4/4] Running analysis (summary tables + charts)..."
python3 "${PROJECT_ROOT}/benchmark/scripts/analyze_exp2_v5.py" \
    --results-dir "$OUTPUT_DIR" \
    --output-dir  "$OUTPUT_DIR" \
    2>&1 | tee "${OUTPUT_DIR}/exp2_analysis.log"

echo
echo "=================================================================="
echo "  Full run complete!"
echo "  Duration  : ${ELAPSED}s"
echo "  Output    : $OUTPUT_DIR"
echo "=================================================================="
echo
echo "  Key files:"
echo "    exp2_run.csv           — per-approach raw timings"
echo "    exp2v5_summary.csv     — merged speedup + recall table"
echo "    exp2v5_speedup_vs_uf.png"
echo "    exp2v5_speedup_vs_npairs.png"
echo "    exp2v5_pruning_heatmap.png"
echo
echo "  To re-run analysis only:"
echo "    bash benchmark/scripts/Experiments2/analyze_exp2.sh \\"
echo "        --results-dir ${OUTPUT_DIR}"
