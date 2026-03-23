#!/usr/bin/env bash
# ===========================================================================
# SimilarityJoin Benchmark — Full Pipeline
# ===========================================================================
# 1. Generate data (Python)
# 2. Compile Java
# 3. Run benchmark (Java)
# 4. Plot results (Python)
# ===========================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"

echo "============================================"
echo "  SimilarityJoin Benchmark Pipeline"
echo "============================================"
echo "Project dir : $PROJECT_DIR"
echo "JAVA_HOME   : $JAVA_HOME"
echo ""

# ------------------------------------------------------------------
# Step 1: Generate datasets
# ------------------------------------------------------------------
echo "[Step 1/4] Generating benchmark datasets..."
cd "$PROJECT_DIR"
python3 benchmark/scripts/generate_sim_join_data.py --n 100 --seed 42
echo ""

# ------------------------------------------------------------------
# Step 2: Compile Java
# ------------------------------------------------------------------
echo "[Step 2/4] Compiling Java..."
cd "$PROJECT_DIR"
mvn compile -q -pl . -am
echo "  Compilation successful"
echo ""

# ------------------------------------------------------------------
# Step 3: Run benchmark
# ------------------------------------------------------------------
echo "[Step 3/4] Running SimilarityJoin benchmark..."
cd "$PROJECT_DIR"

# Build classpath
CP=$(mvn dependency:build-classpath -q -DincludeScope=compile -Dmdep.outputFile=/dev/stdout 2>/dev/null || true)
CP="target/classes:$CP"

"$JAVA_HOME/bin/java" \
    -Dprobsparql.mode=V5_ADAPTIVE \
    -Dprobsparql.samples=5000 \
    -Dprobsparql.sprt.alpha=0.05 \
    -Dprobsparql.sprt.beta=0.05 \
    -Dprobsparql.sprt.epsilon=0.05 \
    -Xmx2g \
    -cp "$CP" \
    org.apache.jena.probsparql.SimilarityJoinBenchmark \
    --data-dir benchmark/data \
    --query benchmark/queries/simjoin_benchmark.sparql \
    --output-dir benchmark/results \
    --warmup 2 \
    --iterations 5
echo ""

# ------------------------------------------------------------------
# Step 4: Plot results
# ------------------------------------------------------------------
echo "[Step 4/4] Generating plots..."
cd "$PROJECT_DIR"
python3 benchmark/scripts/plot_simjoin_grouped_bar.py \
    --input benchmark/results/simjoin_results.csv \
    --output benchmark/results/plot_simjoin_grouped_bar.png

python3 benchmark/scripts/plot_simjoin_v5_breakdown.py \
    --input benchmark/results/simjoin_v5_breakdown.csv \
    --output benchmark/results/plot_simjoin_v5_breakdown.png

echo ""
echo "============================================"
echo "  Benchmark Complete!"
echo "============================================"
echo "Results:"
echo "  CSV:  benchmark/results/simjoin_results.csv"
echo "  CSV:  benchmark/results/simjoin_v5_breakdown.csv"
echo "  Plot: benchmark/results/plot_simjoin_grouped_bar.png"
echo "  Plot: benchmark/results/plot_simjoin_v5_breakdown.png"
