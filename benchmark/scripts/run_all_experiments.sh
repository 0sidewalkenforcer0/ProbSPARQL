#!/usr/bin/env bash
# ===========================================================================
# ProbSPARQL Full Experiment Pipeline
# ===========================================================================
# Runs all experimental benchmarks in order of priority:
#
#   Exp 3.1 — SimJoin Classification Accuracy (all methods × all datasets)
#   Exp 3.2 — SimJoin Convergence (all methods × sample sizes)
#   Exp 3.3 — Selectivity Sensitivity (all methods × threshold values)
#   Exp 1   — Latency/Overhead Analysis (using existing simjoin_results.csv)
#
# Then generates all analysis plots.
#
# Usage:
#   bash benchmark/scripts/run_all_experiments.sh
#   bash benchmark/scripts/run_all_experiments.sh --skip-java    # plots only
#   bash benchmark/scripts/run_all_experiments.sh --skip-plots   # Java only
# ===========================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
DATA_DIR="$PROJECT_DIR/benchmark/data"
RESULTS_DIR="$PROJECT_DIR/benchmark/results"
SCRIPTS_DIR="$PROJECT_DIR/benchmark/scripts"
EXP1_SCRIPTS_DIR="$SCRIPTS_DIR/Experiments1"
EXP2_SCRIPTS_DIR="$SCRIPTS_DIR/Experiments2"
EXP3_SCRIPTS_DIR="$SCRIPTS_DIR/Experiments3"
EXP4_SCRIPTS_DIR="$SCRIPTS_DIR/Experiments4"

SKIP_JAVA=false
SKIP_PLOTS=false

for arg in "$@"; do
    case $arg in
        --skip-java)  SKIP_JAVA=true  ;;
        --skip-plots) SKIP_PLOTS=true ;;
    esac
done

# ─────────────────────────────────────────────────────────────────────────────
print_header() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "  $1"
    echo "═══════════════════════════════════════════════════════════════════"
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 0a — Generate data
# ─────────────────────────────────────────────────────────────────────────────
print_header "Step 0a: Generate Exp1 Datasets"
python3 "$EXP1_SCRIPTS_DIR/generate_exp1_main_probabilistic.py" \
    --output-dir "$DATA_DIR/exp1"

python3 "$EXP1_SCRIPTS_DIR/generate_exp1_main_deterministic.py" \
    --input-dir  "$DATA_DIR/exp1" \
    --output-dir "$DATA_DIR/exp1"

# ─────────────────────────────────────────────────────────────────────────────
# Compile
# ─────────────────────────────────────────────────────────────────────────────
if ! $SKIP_JAVA; then
    print_header "Step 0c/8: Compile"
    cd "$PROJECT_DIR"
    mvn compile -q
    echo "  Compilation successful."

    # Build classpath once
    CP=$(mvn dependency:build-classpath -q \
             -DincludeScope=compile \
             -Dmdep.outputFile=/dev/stdout 2>/dev/null || true)
    CP="$PROJECT_DIR/target/classes:$CP"

    mkdir -p "$RESULTS_DIR"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Helper: run Java benchmark
# ─────────────────────────────────────────────────────────────────────────────
run_java() {
    local class="$1"; shift
    "$JAVA_HOME/bin/java" \
        -Dprobsparql.samples=5000 \
        -Dprobsparql.sprt.alpha=0.05 \
        -Dprobsparql.sprt.beta=0.05 \
        -Dprobsparql.sprt.epsilon=0.05 \
        -Xmx4g \
        -cp "$CP" \
        "$class" "$@"
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 1a — Exp 1: System Overhead (DET vs PROB)
# ─────────────────────────────────────────────────────────────────────────────
if ! $SKIP_JAVA; then
    print_header "Step 1a/8: Exp 1 — System Overhead (DET vs PROB)"
    cd "$PROJECT_DIR"
    run_java org.apache.jena.probsparql.ScalabilityBenchmark \
        --output-dir "$RESULTS_DIR"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 1c — Re-run SimJoin Performance Benchmark (if needed)
# ─────────────────────────────────────────────────────────────────────────────
if ! $SKIP_JAVA; then
    print_header "Step 1c/8: Exp 1 (legacy) — SimJoin Performance (V1-V5 × datasets)"
    if [[ ! -f "$RESULTS_DIR/simjoin_results.csv" ]]; then
        cd "$PROJECT_DIR"
        python3 "$EXP3_SCRIPTS_DIR/generate_sim_join_data.py" --n 100 --seed 42
        run_java org.apache.jena.probsparql.SimilarityJoinBenchmark \
            --data-dir  "$DATA_DIR" \
            --query     "$PROJECT_DIR/benchmark/queries/simjoin_benchmark.sparql" \
            --output-dir "$RESULTS_DIR" \
            --warmup 2 --iterations 5
    else
        echo "  simjoin_results.csv already exists — skipping (delete to re-run)"
    fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 2 — Exp 3.1: Classification Accuracy Benchmark
# ─────────────────────────────────────────────────────────────────────────────
if ! $SKIP_JAVA; then
    print_header "Step 2/5: Exp 3.1 — Classification Accuracy (all methods)"
    cd "$PROJECT_DIR"
    run_java org.apache.jena.probsparql.ClassificationAccuracyBenchmark \
        --data-dir  "$DATA_DIR" \
        --output-dir "$RESULTS_DIR"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 3 — Exp 3.2: Multi-Method Convergence Analysis
# ─────────────────────────────────────────────────────────────────────────────
if ! $SKIP_JAVA; then
    print_header "Step 3/5: Exp 3.2 — Convergence Analysis (all methods)"
    cd "$PROJECT_DIR"
    run_java org.apache.jena.probsparql.MultiMethodConvergenceBenchmark \
        --data-dir  "$DATA_DIR" \
        --output-dir "$RESULTS_DIR"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 4 — Exp 3.3: Selectivity Sensitivity
# ─────────────────────────────────────────────────────────────────────────────
if ! $SKIP_JAVA; then
    print_header "Step 4/8: Exp 3.3 — Selectivity Sensitivity (θ sweep)"
    cd "$PROJECT_DIR"
    run_java org.apache.jena.probsparql.SelectivityBenchmark \
        --data-dir  "$DATA_DIR" \
        --gt-csv    "$RESULTS_DIR/simjoin_ground_truth.csv" \
        --output-dir "$RESULTS_DIR"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 6 — Generate all plots
# ─────────────────────────────────────────────────────────────────────────────
if ! $SKIP_PLOTS; then
    print_header "Step 6/8: Generate Analysis Plots"
    cd "$PROJECT_DIR"

    # ── Exp 1: Overhead ──────────────────────────────────────────────────────
    if [[ -f "$RESULTS_DIR/exp1_raw.csv" ]]; then
        echo ""
        echo "  [Exp 1] DET vs PROB overhead analysis..."
        python3 "$EXP1_SCRIPTS_DIR/analyze_exp1_main.py" \
            --input  "$RESULTS_DIR/exp1_raw.csv" \
            --output "$RESULTS_DIR"
    else
        echo "  [Exp 1] SKIP exp1_raw.csv not found"
    fi

    # ── Exp 3.1: Classification accuracy ───────────────────────────────────
    if [[ -f "$RESULTS_DIR/exp3_1_classification.csv" ]]; then
        echo ""
        echo "  [Exp 3.1] Classification accuracy analysis..."
        python3 "$EXP3_SCRIPTS_DIR/analyze_exp3_1_accuracy.py" \
            --input  "$RESULTS_DIR/exp3_1_classification.csv" \
            --output "$RESULTS_DIR"
    else
        echo "  [Exp 3.1] SKIP — exp3_1_classification.csv not found"
    fi

    # ── Exp 3.2: Convergence ────────────────────────────────────────────────
    if [[ -f "$RESULTS_DIR/exp3_2_convergence_multimethod.csv" ]]; then
        echo ""
        echo "  [Exp 3.2] Multi-method convergence analysis..."
        python3 "$EXP3_SCRIPTS_DIR/analyze_exp3_2_convergence.py" \
            --input  "$RESULTS_DIR/exp3_2_convergence_multimethod.csv" \
            --output "$RESULTS_DIR"
    else
        echo "  [Exp 3.2] SKIP — exp3_2_convergence_multimethod.csv not found"
    fi

    # ── Exp 3.3: Selectivity ────────────────────────────────────────────────
    if [[ -f "$RESULTS_DIR/exp3_3_selectivity.csv" ]]; then
        echo ""
        echo "  [Exp 3.3] Selectivity sensitivity analysis..."
        python3 "$EXP3_SCRIPTS_DIR/analyze_exp3_3_selectivity.py" \
            --input  "$RESULTS_DIR/exp3_3_selectivity.csv" \
            --output "$RESULTS_DIR"
    else
        echo "  [Exp 3.3] SKIP — exp3_3_selectivity.csv not found"
    fi

    # ── Existing plot scripts ───────────────────────────────────────────────
    if [[ -f "$RESULTS_DIR/simjoin_results.csv" ]]; then
        echo ""
        echo "  [SimJoin] Grouped bar chart..."
        python3 "$SCRIPTS_DIR/plot_simjoin_grouped_bar.py" \
            --input  "$RESULTS_DIR/simjoin_results.csv" \
            --output "$RESULTS_DIR/plot_simjoin_grouped_bar.png" 2>/dev/null || true

        python3 "$SCRIPTS_DIR/plot_simjoin_v5_breakdown.py" \
            --input  "$RESULTS_DIR/simjoin_v5_breakdown.csv" \
            --output "$RESULTS_DIR/plot_simjoin_v5_breakdown.png" 2>/dev/null || true
    fi

    if [[ -f "$RESULTS_DIR/simjoin_convergence.csv" ]]; then
        echo ""
        echo "  [Convergence] Existing convergence plot (stratified only)..."
        python3 "$SCRIPTS_DIR/plot_convergence.py" \
            --input  "$RESULTS_DIR/simjoin_convergence.csv" \
            --output "$RESULTS_DIR/plot_convergence.png" 2>/dev/null || true
    fi

fi

# ─────────────────────────────────────────────────────────────────────────────
print_header "Done — Summary of Outputs"
echo ""
echo "  Results:  $RESULTS_DIR"
echo ""

for f in \
    exp1_raw.csv \
    exp1_summary.csv \
    exp2_inengine.csv \
    exp2_external.csv \
    exp2_pairs.json \
    simjoin_results.csv \
    simjoin_accuracy_latency.csv \
    exp3_1_classification.csv \
    exp3_1_per_pair.csv \
    exp3_2_convergence_multimethod.csv \
    exp3_3_selectivity.csv; do
    if [[ -f "$RESULTS_DIR/$f" ]]; then
        lines=$(wc -l < "$RESULTS_DIR/$f")
        echo "  ✓ $f  (${lines} lines)"
    else
        echo "  ✗ $f  [NOT GENERATED]"
    fi
done

echo ""
echo "  Plots:"
for f in \
    exp1_scalability_lineplot.png exp1_overhead_barplot.png \
    exp2_speedup.png \
    exp4_overhead_barplot.png exp4_accuracy_scatter.png \
    exp1_latency_lines.png exp1_latency_bars.png exp1_overhead_ratio.png \
    exp3_1_grouped_bar.png exp3_1_f1_heatmap.png exp3_1_latency.png \
    exp3_2_jsd_convergence.png exp3_2_mae_convergence.png exp3_2_time.png \
    exp3_3_latency_theta_mixed.png exp3_3_result_count.png \
    exp3_3_accuracy_theta_mixed.png exp3_3_f1_theta.png \
    plot_simjoin_grouped_bar.png plot_simjoin_v5_breakdown.png; do
    if [[ -f "$RESULTS_DIR/$f" ]]; then
        echo "  ✓ $f"
    else
        echo "  ✗ $f"
    fi
done
echo ""
