#!/usr/bin/env bash
# =============================================================================
# run_exp5.sh — Exp5: In-engine early filter vs post-processing late filter
#
# Experiment goal:
#   Compare two execution styles for a probabilistic threshold query followed by
#   OPTIONAL expansion:
#     InEngine) early FILTER(prob:cdf(...))
#     PostProcessing) late filter after fetching OPTIONAL-side data
#
# Query shape:
#   ?gear :toothLength ?d .
#   FILTER(prob:cdf(?d, 9.8) >= 0.9)
#   OPTIONAL {
#     ?gear :ctMeasurement ?ctDist .
#     ?gear :lightMeasurement ?lightDist .
#   }
#
# This script sweeps:
#   PASS_FRACS = 0.01 / 0.05 / 0.1 / 0.3
#
# Optional-side structure:
#   a gear either has both optional measurements or neither
#
# Usage:
#   bash benchmark/scripts/Experiments5/run_exp5.sh
#
# Optional env vars:
#   DATA_DIR          — output directory for generated TTL datasets
#   OUTPUT_DIR        — result directory
#   QUERY_DIR         — directory containing Exp5 query files
#   N_GEARS           — number of gear entities per dataset
#   PASS_FRACS        — quoted space-separated list, e.g. "0.1 0.5 0.9"
#   OPT_PRESENT_FRAC  — fraction of gears with both OPTIONAL-side measurements
#   WARMUP            — warmup runs per configuration
#   RUNS              — timed runs per configuration
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

DATA_DIR="${DATA_DIR:-${PROJECT_ROOT}/benchmark/data/exp5}"
OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp5}"
QUERY_DIR="${QUERY_DIR:-${PROJECT_ROOT}/benchmark/queries/exp5}"
N_GEARS="${N_GEARS:-1_000_000}"
PASS_FRACS="${PASS_FRACS:-0.01 0.05 0.1 0.3}"
OPT_PRESENT_FRAC="${OPT_PRESENT_FRAC:-0.7}"
WARMUP="${WARMUP:-3}"
RUNS="${RUNS:-10}"

# ── Java resolution ─────────────────────────────────────────────────────────
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

mkdir -p "${DATA_DIR}" "${OUTPUT_DIR}"
MASTER_SUMMARY="${OUTPUT_DIR}/exp5_summary_all.csv"

echo "=== Exp5: In-engine vs Post-processing ==="
echo "Data dir   : ${DATA_DIR}"
echo "Output dir : ${OUTPUT_DIR}"
echo "Java home  : ${JAVA_HOME}"
echo "N_GEARS    : ${N_GEARS}"
echo "PASS_FRACS : ${PASS_FRACS}"
echo "OPTIONAL   : both-or-neither, present=${OPT_PRESENT_FRAC}"
echo

# ── Aggregate summary init ──────────────────────────────────────────────────
printf "PassFrac,Method,MedianMs,IQRMs,RowsReturned,DistinctGears,FetchedRowsBeforeFilter,DistinctGearsBeforeFilter\n" > "${MASTER_SUMMARY}"

# ── Sweep PASS_FRAC configurations ──────────────────────────────────────────
for PASS_FRAC in ${PASS_FRACS}; do
  PASS_LABEL="${PASS_FRAC/./p}"
  DATASET_NAME="exp5_gears_${N_GEARS}_pass_${PASS_LABEL}"
  TTL_PATH="${DATA_DIR}/${DATASET_NAME}.ttl"
  RUN_OUTPUT_DIR="${OUTPUT_DIR}/pass_${PASS_LABEL}"

  echo "── pass_frac=${PASS_FRAC} ─────────────────────────────"

  # ── Step 1: Generate dataset if missing ──────────────────────────────────
  if [[ ! -f "${TTL_PATH}" ]]; then
    echo "[1/2] Generating dataset..."
    python3 "${PROJECT_ROOT}/benchmark/scripts/Experiments5/generate_exp5.py" \
      --output-dir "${DATA_DIR}" \
      --name "${DATASET_NAME}" \
      --n-gears "${N_GEARS}" \
      --pass-frac "${PASS_FRAC}" \
      --opt-present-frac "${OPT_PRESENT_FRAC}"
  else
    echo "[1/2] Dataset exists — skipping generation."
  fi

  # ── Step 2: Run Java benchmark ───────────────────────────────────────────
  echo "[2/2] Running Java benchmark..."
  mkdir -p "${RUN_OUTPUT_DIR}"
  mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp5Benchmark" \
    -Dexec.args="--data ${TTL_PATH} \
                 --query-dir ${QUERY_DIR} \
                 --output-dir ${RUN_OUTPUT_DIR} \
                 --warmup ${WARMUP} \
                 --runs ${RUNS}"

  # ── Step 3: Append per-run summary into aggregate CSV ────────────────────
  if [[ -f "${RUN_OUTPUT_DIR}/exp5_summary.csv" ]]; then
    tail -n +2 "${RUN_OUTPUT_DIR}/exp5_summary.csv" | while IFS= read -r line; do
      printf "%s,%s\n" "${PASS_FRAC}" "${line}" >> "${MASTER_SUMMARY}"
    done
  fi
  echo
done

# ── Final output ────────────────────────────────────────────────────────────
echo "Done."
echo "Aggregate summary: ${MASTER_SUMMARY}"
