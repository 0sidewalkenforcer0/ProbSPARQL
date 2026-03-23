#!/usr/bin/env bash
# =============================================================================
# run_exp4_demo.sh — Quick Feasibility Demo for Experiment 4
#
# Reduced parameters (for estimating real experiment runtime):
#
#   Sub-exp 4.1  Exp4DispatchTest   — unchanged (already small, N=10)
#   Sub-exp 4.2  Exp4MicroBenchmark — N=50, warmup=1, runs=1, B=[50], k=[4]
#   Sub-exp 4.3  Exp4CrossTypeJSD   — 5 pairs, MC 500/1000 samples
#   Sub-exp 4.4  Exp4EndToEnd       — E3 scale only, warmup=1, runs=1
#   Sub-exp 4.5  Exp4DirichletDemo  — unchanged
#
# Python data generation: SKIP_DATA=1 by default (only generates if missing).
#
# Timing: each phase is timed; a final summary estimates scale factors to
#         full-run time.
#
# Usage (from project root):
#   bash benchmark/scripts/Experiments4/run_exp4_demo.sh
#
# Optional env overrides:
#   OUTPUT_DIR   SKIP_BUILD   JAVA_HOME
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

OUTPUT_DIR="${OUTPUT_DIR:-${PROJECT_ROOT}/benchmark/results/exp4_demo}"
DATA_DIR="${DATA_DIR:-${PROJECT_ROOT}/benchmark/data}"
SKIP_BUILD="${SKIP_BUILD:-0}"

# ── Java 21 ─────────────────────────────────────────────────────────────────
if [[ -z "${JAVA_HOME:-}" ]]; then
    if command -v /usr/libexec/java_home &>/dev/null; then
        JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home)"
    else
        JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
    fi
    export JAVA_HOME
fi
export PATH="$JAVA_HOME/bin:$PATH"

# ── Python / venv ───────────────────────────────────────────────────────────
PYTHON_BIN=""
for PY in "${PROJECT_ROOT}/.venv/bin/python3" python3 python; do
    if command -v "$PY" &>/dev/null && "$PY" -c "import numpy, rdflib" 2>/dev/null; then
        PYTHON_BIN="$PY"
        break
    fi
done

TOTAL_START=$SECONDS
T_DISPATCH=0; T_MICRO=0; T_CROSS=0; T_E2E=0; T_DIRICHLET=0; T_DATA=0

echo "=================================================================="
echo "  Experiment 4 — DEMO / FEASIBILITY RUN"
echo "=================================================================="
echo "  Project root : $PROJECT_ROOT"
echo "  Output dir   : $OUTPUT_DIR"
echo "  Data dir     : $DATA_DIR"
echo "  Java home    : $JAVA_HOME"
echo "  Python bin   : ${PYTHON_BIN:-NONE}"
echo "  Start time   : $(date)"
echo "=================================================================="
echo "  Demo parameters (vs full run):"
echo "    Exp4.2 Micro : N=50 (was 1000), warmup=1 (was 3), runs=1 (was 10)"
echo "                   B=[50] only (was [20,50,100]), k=[4] only (was [4,10,20])"
echo "    Exp4.3 Cross : 5 pairs (was 100), MC=500/1000 (was 5k/10k)"
echo "    Exp4.4 E2E   : E3 scale only (was E3+E5+E7), warmup=1, runs=1"
echo "=================================================================="
echo

cd "$PROJECT_ROOT"
mkdir -p "$OUTPUT_DIR"

# ── Phase 0: Build ───────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" != "1" ]]; then
    echo ">>> Phase 0: Building with Maven..."
    t0=$SECONDS
    mvn -q package -DskipTests
    echo "    Build complete in $(( SECONDS - t0 ))s."
fi

# ── Phase 1: Data generation (only if missing) ───────────────────────────────
echo
echo ">>> Phase 1: Checking / generating datasets..."
t0=$SECONDS
SAMPLE_HIST="${DATA_DIR}/exp4/exp4_E3_hist_B50.ttl"
SAMPLE_DIR="${DATA_DIR}/exp4/exp4_dirichlet.ttl"

if [[ -s "$SAMPLE_HIST" && -s "$SAMPLE_DIR" ]]; then
    echo "    Exp4 datasets already present — SKIP_DATA."
    T_DATA=0
elif [[ -z "$PYTHON_BIN" ]]; then
    echo "    WARNING: Python not available — datasets may be missing."
    T_DATA=0
else
    echo "    Generating exp4 datasets (first-time setup)..."
    "$PYTHON_BIN" "${SCRIPT_DIR}/generate_histogram_datasets.py" \
        --input-dir  "${DATA_DIR}/exp1" \
        --output-dir "${DATA_DIR}/exp4"
    "$PYTHON_BIN" "${SCRIPT_DIR}/generate_dirichlet_dataset.py" \
        --output-dir "${DATA_DIR}/exp4"
    "$PYTHON_BIN" "${SCRIPT_DIR}/generate_crosstype_pairs.py" \
        --output-dir "${DATA_DIR}/exp4"
    T_DATA=$(( SECONDS - t0 ))
    echo "    Dataset generation: ${T_DATA}s"
fi

# ── Helper: timed Java run ──────────────────────────────────────────────────

# ── Phase 2: Exp 4.1 Dispatch (no --demo, already tiny) ─────────────────────
echo
echo ">>> Phase 2 (Exp 4.1): Exp4DispatchTest ..."
t0=$SECONDS
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp4DispatchTest" \
    -Dexec.args="--output-dir ${OUTPUT_DIR}" \
    2>&1 | tee "${OUTPUT_DIR}/Exp4DispatchTest.log" | grep -E "^\s|^--|^==|^>>>" || true
T_DISPATCH=$(( SECONDS - t0 ))
echo "    Done in ${T_DISPATCH}s."

# ── Phase 3: Exp 4.2 Micro-benchmark ─────────────────────────────────────────
echo
echo ">>> Phase 3 (Exp 4.2): Exp4MicroBenchmark [DEMO] ..."
t0=$SECONDS
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp4MicroBenchmark" \
    -Dexec.args="--output-dir ${OUTPUT_DIR} --data-dir ${DATA_DIR} --demo" \
    2>&1 | tee "${OUTPUT_DIR}/Exp4MicroBenchmark.log" | grep -E "^\s|^--|^==|^>>>" || true
T_MICRO=$(( SECONDS - t0 ))
echo "    Done in ${T_MICRO}s."

# ── Phase 4: Exp 4.3 Cross-type JSD ──────────────────────────────────────────
echo
echo ">>> Phase 4 (Exp 4.3): Exp4CrossTypeJSD [DEMO] ..."
t0=$SECONDS
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp4CrossTypeJSD" \
    -Dexec.args="--output-dir ${OUTPUT_DIR} --data-dir ${DATA_DIR} --demo" \
    2>&1 | tee "${OUTPUT_DIR}/Exp4CrossTypeJSD.log" | grep -E "^\s|^--|^==|^>>>" || true
T_CROSS=$(( SECONDS - t0 ))
echo "    Done in ${T_CROSS}s."

# ── Phase 5: Exp 4.4 End-to-end ──────────────────────────────────────────────
echo
echo ">>> Phase 5 (Exp 4.4): Exp4EndToEnd [DEMO] ..."
t0=$SECONDS
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp4EndToEnd" \
    -Dexec.args="--output-dir ${OUTPUT_DIR} --data-dir ${DATA_DIR} --demo" \
    2>&1 | tee "${OUTPUT_DIR}/Exp4EndToEnd.log" | grep -E "^\s|^--|^==|^>>>" || true
T_E2E=$(( SECONDS - t0 ))
echo "    Done in ${T_E2E}s."

# ── Phase 6: Exp 4.5 Dirichlet demo ──────────────────────────────────────────
echo
echo ">>> Phase 6 (Exp 4.5): Exp4DirichletDemo ..."
t0=$SECONDS
mvn -q exec:java \
    -Dexec.mainClass="org.apache.jena.probsparql.Exp4DirichletDemo" \
    -Dexec.args="--output-dir ${OUTPUT_DIR} --data-dir ${DATA_DIR}" \
    2>&1 | tee "${OUTPUT_DIR}/Exp4DirichletDemo.log" | grep -E "^\s|^--|^==|^>>>" || true
T_DIRICHLET=$(( SECONDS - t0 ))
echo "    Done in ${T_DIRICHLET}s."

# ── Phase 7: Analysis ────────────────────────────────────────────────────────
echo
echo ">>> Phase 7: Analysis..."
if [[ -n "$PYTHON_BIN" ]]; then
    "$PYTHON_BIN" "${PROJECT_ROOT}/benchmark/scripts/analyze_exp4.py" \
        --input  "$OUTPUT_DIR" \
        --output "$OUTPUT_DIR" \
        || echo "    WARNING: Analysis returned non-zero exit."
    echo "    Analysis complete."
fi

# ── Timing summary + scale estimates ────────────────────────────────────────
DEMO_TOTAL=$(( SECONDS - TOTAL_START ))

echo
echo "=================================================================="
echo "  DEMO RUN COMPLETE"
echo "  Total demo time: ${DEMO_TOTAL}s  ($(( DEMO_TOTAL / 60 ))m $(( DEMO_TOTAL % 60 ))s)"
echo "=================================================================="
echo
echo "  Phase timing breakdown:"
printf "  %-30s  %5s s\n" "Phase" "Demo"
printf "  %-30s  %5s\n" "------------------------------" "-----"
printf "  %-30s  %5d\n" "Phase2-Dispatch"  "$T_DISPATCH"
printf "  %-30s  %5d\n" "Phase3-Micro"     "$T_MICRO"
printf "  %-30s  %5d\n" "Phase4-Cross"     "$T_CROSS"
printf "  %-30s  %5d\n" "Phase5-E2E"       "$T_E2E"
printf "  %-30s  %5d\n" "Phase6-Dirichlet" "$T_DIRICHLET"
echo
echo "  ── Scale factors demo → full run ──"

# Scale factors
# Micro: N 50→1000 (×20), variants 2→7 (×3.5), warmup+runs 2→13 (×6.5) → ×20×3.5×6.5 ≈ ×455
#   but JVM overhead dominates; conservative estimate ×50-100
MICRO_FULL_EST=$(( T_MICRO * 80 ))
printf "  %-30s  demo=%3ds  →  full ≈ %ds (%dm)\n" \
    "Exp4.2 Micro" "$T_MICRO" "$MICRO_FULL_EST" "$(( MICRO_FULL_EST / 60 ))"

# Cross: 5→100 pairs (×20) for each of 5 types, MC 500→10k (×20) → ×400 worst case
#   but many are cheap exact; conservative ×20 on pairs
CROSS_FULL_EST=$(( T_CROSS * 20 ))
printf "  %-30s  demo=%3ds  →  full ≈ %ds (%dm)\n" \
    "Exp4.3 CrossType" "$T_CROSS" "$CROSS_FULL_EST" "$(( CROSS_FULL_EST / 60 ))"

# E2E: 1→3 scales + cost of E5/E7 is non-linear (quadratic for JSD due to LIMIT)
#   E5 has 10× entities but LIMIT 200 caps JSD, so CDF is ×10, JSD ~same
#   E7 has 100× entities, CDF ×100 but JSD still LIMIT 200
#   Plus 2× B variants for histogram, plus warmup+runs 2→13×
#   Conservative ×30
E2E_FULL_EST=$(( T_E2E * 30 ))
printf "  %-30s  demo=%3ds  →  full ≈ %ds (%dm)\n" \
    "Exp4.4 E2E" "$T_E2E" "$E2E_FULL_EST" "$(( E2E_FULL_EST / 60 ))"

printf "  %-30s  demo=%3ds  →  full ≈ same\n" "Exp4.5 Dirichlet" "$T_DIRICHLET"

BUILD_EST=120
FULL_TOTAL_EST=$(( BUILD_EST + T_DATA + MICRO_FULL_EST + CROSS_FULL_EST + E2E_FULL_EST + T_DIRICHLET ))
echo
printf "  %-30s  %ds  (%dm %ds)\n" \
    "Estimated FULL RUN total:" \
    "$FULL_TOTAL_EST" \
    "$(( FULL_TOTAL_EST / 60 ))" \
    "$(( FULL_TOTAL_EST % 60 ))"
echo
echo "  Results in: $OUTPUT_DIR"
echo "=================================================================="
echo
echo "CSV outputs:"
ls -1 "${OUTPUT_DIR}"/*.csv 2>/dev/null || echo "  (none found)"
