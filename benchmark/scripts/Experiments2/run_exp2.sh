#!/usr/bin/env bash
# =============================================================================
# run_exp2.sh — Full remote run for Experiment 2
#
# Experiment 2: In-engine Filtering vs DIVJOIN with Mixed-K Datasets
#   Three-way comparison across:
#     InEngine_CheapFirst
#     InEngine_JSDFirst
#     DIVJOIN
#   default workload:
#     N_PAIRS = 5000
#     unimodalFrac ∈ {0.2, 0.5, 0.8}
#     selectivity ∈ {10pct, 50pct, 90pct}
#   = 9 total configurations
#
# Full configuration:
#   Warmup : 3 runs  (discarded for JIT warm-up)
#   Measure: 10 runs (median of 10 reported)
#   Numeric JSD filters use prob:jsd; DIVJOIN uses the server-side adaptive
#   similarity evaluator and exposes pruning stats through prob:lastDivJoinStats.
#
# Estimated runtime: depends on machine speed
#   Increase NPAIRS with a comma-separated list after loading matching remote
#   services named exp2_npairs_<N>_uf_0p2/0p5/0p8.
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments2/run_exp2.sh
#
# Optional env vars:
#   ENDPOINT_TEMPLATE — required, e.g. https://fujitsu:3030/{dataset}/query
#   OUTPUT_DIR        — override result directory (default: benchmark/results/exp2)
#   QUERY_DIR         — override query directory (default: benchmark/queries/exp2)
#   SKIP_BUILD        — set to 1 to skip Maven package step
#   NPAIRS            — comma-separated target pair counts (default: 5000)
#
# Required server JVM properties for pruning stats:
#   -Dprobsparql.simjoin.pruning=true
#   -Dprobsparql.simjoin.deduplicate=true
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp2}"
QUERY_DIR="${QUERY_DIR:-${PROJECT_ROOT}/benchmark/queries/exp2}"
ENDPOINT_TEMPLATE="${ENDPOINT_TEMPLATE:-}"
SKIP_BUILD="${SKIP_BUILD:-0}"
NPAIRS="${NPAIRS:-5000}"

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
export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" 

echo "=================================================================="
echo "  Experiment 2 — In-engine Filtering vs DIVJOIN  [FULL RUN]"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Output dir   : $OUTPUT_DIR"
echo "  Endpoint tpl : ${ENDPOINT_TEMPLATE:-<required>}"
echo "  Query dir    : $QUERY_DIR"
echo "  Java home    : $JAVA_HOME"
echo "  NPairs       : $NPAIRS"
echo "  Warmup       : $WARMUP_RUNS"
echo "  Runs         : $BENCHMARK_RUNS"
echo "  Configs      : $(awk -F',' '{print NF}' <<< "$NPAIRS") n_pairs × 3 unimodalFrac × 3 selectivity"
echo "  Start time   : $(date)"
echo "------------------------------------------------------------------"
echo

cd "$PROJECT_ROOT"
mkdir -p "$OUTPUT_DIR"

if [[ -z "${ENDPOINT_TEMPLATE}" ]]; then
    echo "ERROR: ENDPOINT_TEMPLATE is required, e.g. https://fujitsu:3030/{dataset}/query" >&2
    exit 1
fi

# ── Step 1: Build ───────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo "[1/3] Building with Maven (skipping tests)..."
    mvn -q package -DskipTests
    echo "      Build complete."
else
    echo "[1/3] Build skipped (SKIP_BUILD=1)."
fi

# ── Step 2: Run benchmark ───────────────────────────────────────────────────
echo
echo "[2/3] Running Exp2Benchmark over remote endpoints (warmup=$WARMUP_RUNS, runs=$BENCHMARK_RUNS)..."
echo "      Selected nPairs=${NPAIRS}; 3 variants over 3 unimodalFrac × 3 selectivity."
echo "      Remote services must be named exp2_npairs_<N>_uf_0p2/0p5/0p8."
echo "      Progress is logged to: ${OUTPUT_DIR}/exp2_run.log"
echo

START_EPOCH=$(date +%s)

mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp2Benchmark" \
    "-Dexec.args=--output-dir ${OUTPUT_DIR} \
                 --endpoint-template ${ENDPOINT_TEMPLATE} \
                 --query-dir ${QUERY_DIR} \
                 --npairs ${NPAIRS} \
                 --warmup ${WARMUP_RUNS} \
                 --runs ${BENCHMARK_RUNS}" \
    2>&1 | tee "${OUTPUT_DIR}/exp2_run.log"

END_EPOCH=$(date +%s)
ELAPSED=$(( END_EPOCH - START_EPOCH ))
echo
echo "      Benchmark completed in ${ELAPSED}s  ($(date))"

# ── Step 3: Validate ────────────────────────────────────────────────────────
echo
echo "[3/3] Validating results (recall / consistency checks)..."

if python3 "${PROJECT_ROOT}/benchmark/scripts/Experiments2/validate_exp2.py" \
        --results-dir "$OUTPUT_DIR" \
        2>&1 | tee "${OUTPUT_DIR}/exp2_validate.log"; then
    echo "      Validation passed."
else
    echo "      [WARN] Validation reported issues — check exp2_validate.log"
fi

# ── Step 4: Run analysis ────────────────────────────────────────────────────
echo
echo "[post] Running analysis (summary tables + charts)..."
python3 "${PROJECT_ROOT}/benchmark/scripts/Experiments2/analyze_exp2.py" \
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
echo "    exp2_inengine_cheapfirst.csv"
echo "    exp2_inengine_jsdfirst.csv"
echo "    exp2_similarityjoin.csv"
echo "    exp2_summary.csv     — merged speedup + recall table"
echo "    exp2_speedup_vs_uf.png"
echo "    exp2_speedup_vs_npairs.png"
echo "    exp2_pruning_heatmap.png"
echo
echo "  To re-run analysis only:"
echo "    bash benchmark/scripts/Experiments2/analyze_exp2.sh \\"
echo "        --results-dir ${OUTPUT_DIR}"
