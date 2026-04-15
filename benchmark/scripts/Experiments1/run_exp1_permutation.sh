#!/usr/bin/env bash
# =============================================================================
# run_exp1_permutation.sh — Exp1 representation invariance sub-experiment
#
# Goal:
#   Compare Exp1 query latency on semantically equivalent GMM literals whose
#   mixture-component order is permuted.
#
# Setup:
#   - Scale fixed to E5
#   - K values fixed to 3, 5, 10  (K=1 skipped: no non-trivial permutation)
#   - Run two conditions:
#       1. original  datasets
#       2. permuted  datasets (same RDF graph, GMM components reordered)
#
# Output:
#   benchmark/results/exp1/permutation/
#
# Usage:
#   bash benchmark/scripts/Experiments1/run_exp1_permutation.sh
#
# Optional env vars:
#   OUTPUT_ROOT  — result root directory
#   SKIP_BUILD   — set to 1 to skip Maven compile
#   SCALE        — default E5
#   K_VALUES     — default "3 5 10"
#   Q4_VARIANT   — default legacy; set to poly to use prob:jsd
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

OUTPUT_ROOT="${OUTPUT_ROOT:-${PROJECT_ROOT}/benchmark/results/exp1/permutation}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SCALE="${SCALE:-E5}"
K_VALUES="${K_VALUES:-3 5 10}"
Q4_VARIANT="${Q4_VARIANT:-legacy}"

WARMUP_RUNS=3
BENCHMARK_RUNS=10

DATA_DIR="${PROJECT_ROOT}/benchmark/data/exp1/main"
PERM_DATA_DIR="${PROJECT_ROOT}/benchmark/data/exp1/permutation"
QUERY_DIR="${PROJECT_ROOT}/benchmark/queries/exp1"
PERM_SCRIPT="${SCRIPT_DIR}/generate_exp1_permutation.py"
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
echo "  Exp1 Sub-Experiment — GMM Permutation Invariance"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Data dir     : $DATA_DIR"
echo "  Perm dir     : $PERM_DATA_DIR"
echo "  Output root  : $OUTPUT_ROOT"
echo "  Scale        : $SCALE"
echo "  K values     : $K_VALUES"
echo "  Q4 variant   : $Q4_VARIANT"
echo "  Java home    : $JAVA_HOME"
echo "  Start time   : $(date)"
echo "=================================================================="
echo

cd "$PROJECT_ROOT"
mkdir -p "$OUTPUT_ROOT"

# ── Step 0: Build ───────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo "[0/4] Building with Maven (skipping tests)..."
    mvn -q package -DskipTests
    echo "      Build complete."
else
    echo "[0/4] Build skipped (SKIP_BUILD=1)."
fi

# ── Step 1: Validate source datasets ────────────────────────────────────────
echo
echo "[1/4] Checking source Exp1 datasets..."
for k in ${K_VALUES}; do
    src="${DATA_DIR}/exp1_${SCALE}_K${k}.ttl"
    if [[ ! -f "$src" ]]; then
        echo "ERROR: missing source dataset: $src"
        exit 1
    fi
done
if [[ ! -f "${DATA_DIR}/exp1_${SCALE}_det.ttl" ]]; then
    echo "ERROR: missing deterministic dataset: ${DATA_DIR}/exp1_${SCALE}_det.ttl"
    exit 1
fi
echo "      Source datasets present."

# ── Step 2: Generate permuted datasets if needed ────────────────────────────
echo
echo "[2/4] Checking / generating permuted GMM datasets..."
NEED_PERMUTE=0
for k in ${K_VALUES}; do
    if [[ ! -f "${PERM_DATA_DIR}/exp1_${SCALE}_K${k}_permuted.ttl" ]]; then
        NEED_PERMUTE=1
    fi
done

if [[ "$NEED_PERMUTE" == "1" ]]; then
    python3 "$PERM_SCRIPT" \
        --scales "$SCALE" \
        --ks ${K_VALUES} \
        --input-dir "$DATA_DIR" \
        --output-dir "$PERM_DATA_DIR"
    echo "      Permuted datasets generated."
else
    echo "      Permuted datasets already exist — skipping generation."
fi

# ── Step 3: Run paired benchmark in one JVM ─────────────────────────────────
echo
echo "[3/4] Running paired permutation benchmark..."
mkdir -p "$OUTPUT_ROOT"

mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp1PermutationBenchmark" \
    -Dexec.args="--data-dir ${DATA_DIR} \
                 --permuted-data-dir ${PERM_DATA_DIR} \
                 --query-dir ${QUERY_DIR} \
                 --output-dir ${OUTPUT_ROOT} \
                 --scale ${SCALE} \
                 --q4-variant ${Q4_VARIANT} \
                 --k-values ${K_VALUES} \
                 --warmup ${WARMUP_RUNS} \
                 --runs ${BENCHMARK_RUNS}" \
    2>&1 | tee "${OUTPUT_ROOT}/run.log"

echo
echo "=================================================================="
echo "  Exp1 permutation experiment complete"
echo "  Results  : ${OUTPUT_ROOT}"
echo "=================================================================="
