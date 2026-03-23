#!/usr/bin/env bash
# run_exp2_v5.sh — Build, run, validate, and analyze Experiment 2 v5
#
# Usage:
#   ./run_exp2_v5.sh [--output-dir <dir>] [--skip-build] [--skip-validate]
#
# Steps:
#   1. Maven build (skip tests for speed)
#   2. Run Exp2BenchmarkV5 (GT_10K, WARMUP=1, RUNS=3)
#   3. Validate results (recall check, consistency)
#   4. Analyze results (summary tables + charts)
#
# Environment:
#   JAVA_HOME — override JDK path if needed
#   MVN       — override mvn command (default: mvn)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR" && pwd)"

OUTPUT_DIR="benchmark/results/exp2_v5"
SKIP_BUILD=0
SKIP_VALIDATE=0

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output-dir)  OUTPUT_DIR="$2"; shift 2 ;;
        --skip-build)  SKIP_BUILD=1;    shift   ;;
        --skip-validate) SKIP_VALIDATE=1; shift ;;
        *) echo "[ERROR] Unknown argument: $1" >&2; exit 1 ;;
    esac
done

MVN="${MVN:-mvn}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA="${JAVA:-java}"

echo "=================================================="
echo " Experiment 2 v5 — Filter-Pushdown Advantage"
echo "=================================================="
echo " Project root : $PROJECT_ROOT"
echo " Output dir   : $OUTPUT_DIR"
echo " Skip build   : $SKIP_BUILD"
echo ""

cd "$PROJECT_ROOT"

# -----------------------------------------------------------------------
# Step 1: Build
# -----------------------------------------------------------------------
if [[ $SKIP_BUILD -eq 0 ]]; then
    echo "[1/4] Building with Maven (skipping tests)..."
    $MVN -q package -DskipTests -pl . 2>&1 | tail -5
    echo "      Build complete."
else
    echo "[1/4] Build skipped (--skip-build)."
fi

# -----------------------------------------------------------------------
# Step 2: Run benchmark
# -----------------------------------------------------------------------
echo ""
echo "[2/4] Running Exp2BenchmarkV5..."
echo "      Output dir: $OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

START_EPOCH=$(date +%s)

$MVN -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp2BenchmarkV5" \
    -Dexec.args="--output-dir $OUTPUT_DIR" \
    -Dprobsparql.mode=GT_10K \
    -Dprobsparql.simjoin.pruning=true \
    -Dprobsparql.simjoin.deduplicate=true \
    2>&1 | tee "$OUTPUT_DIR/exp2v5_run.log"

END_EPOCH=$(date +%s)
ELAPSED=$(( END_EPOCH - START_EPOCH ))
echo "      Benchmark completed in ${ELAPSED}s."

# -----------------------------------------------------------------------
# Step 3: Validate
# -----------------------------------------------------------------------
echo ""
if [[ $SKIP_VALIDATE -eq 0 ]]; then
    echo "[3/4] Validating results..."
    if python3 benchmark/scripts/exp2_validate_v5.py \
            --results-dir "$OUTPUT_DIR" 2>&1 | tee "$OUTPUT_DIR/exp2v5_validate.log"; then
        echo "      Validation PASSED."
    else
        echo "[WARN] Validation reported failures. Check $OUTPUT_DIR/exp2v5_validate.log"
        # Don't exit — still run analysis so we can diagnose
    fi
else
    echo "[3/4] Validation skipped (--skip-validate)."
fi

# -----------------------------------------------------------------------
# Step 4: Analyze
# -----------------------------------------------------------------------
echo ""
echo "[4/4] Analyzing results..."
if python3 benchmark/scripts/analyze_exp2_v5.py \
        --results-dir "$OUTPUT_DIR" \
        --output-dir  "$OUTPUT_DIR" 2>&1 | tee "$OUTPUT_DIR/exp2v5_analysis.log"; then
    echo "      Analysis complete."
else
    echo "[WARN] Analysis had errors. Check $OUTPUT_DIR/exp2v5_analysis.log"
fi

echo ""
echo "=================================================="
echo " Results written to: $OUTPUT_DIR"
echo " Key files:"
echo "   exp2v5_a.csv           — Approach A timings"
echo "   exp2v5_b_java.csv      — Approach B (Java loop) timings"
echo "   exp2v5_c.csv           — Approach C timings"
echo "   exp2v5_calibration.csv — Calibration thresholds"
echo "   exp2v5_pruning_stats.csv — Pruning breakdown"
echo "   exp2v5_summary.csv     — Speedup summary"
echo "   exp2v5_speedup_vs_uf.png — Key chart"
echo "=================================================="
