# Stage 2 Results

**Date:** 2025-07-09  
**Build Status:** `mvn clean compile test-compile` → **BUILD SUCCESS**  
**Java:** 21 | **Framework:** Apache Jena ARQ 6.0.0-SNAPSHOT (custom build)

---

## 1. Overview

Stage 2 completed two complementary goals:

1. **Implement all missing experiments from experiment_plan_v2** — new histogram distribution type, SPARQL functions, benchmark harnesses, and analysis scripts identified in Stage 1 gap analysis.
2. **Fix all real code quality issues** causing IDE red-file warnings — unused imports, unused variables, dead methods, and deprecated API calls.

---

## 2. New Components Implemented (Session 1)

### 2.1 Histogram Distribution Type — 5 new Java files

| File | Description |
|------|-------------|
| `src/main/java/org/apache/jena/probsparql/datatypes/HistogramValue.java` | Value object. Stores `B` bins, `min`/`max` range, `counts[]`. Provides `cdf(double x)`, `mean()`, `probabilities()`, `jsd(HistogramValue)`. |
| `src/main/java/org/apache/jena/probsparql/datatypes/HistogramDatatype.java` | Jena `BaseDatatype` for type URI `uq:histLiteral`. Parses/serializes JSON: `{"B":50,"min":8.0,"max":12.0,"counts":[...]}`. |
| `src/main/java/org/apache/jena/probsparql/functions/comparison/HistogramJSD.java` | `prob:histjsd` — discrete Jensen-Shannon divergence between two histogram literals. Public `extractHistogram` util used by other functions. |
| `src/main/java/org/apache/jena/probsparql/functions/thresholding/HistogramCDF.java` | `prob:histcdf(hist, x)` — CDF evaluated at threshold `x`. Used in wear-detection SPARQL patterns. |
| `src/main/java/org/apache/jena/probsparql/functions/manipulation/HistogramMean.java` | `prob:histmean(hist)` — expected value (mean) of a histogram distribution. |

**Integration:** All histogram types and functions registered in `src/main/java/org/apache/jena/probsparql/ProbSPARQL.java`.

### 2.2 Benchmark Harness — 3 new Java classes

| Class | Experiment | Output Files |
|-------|-----------|--------------|
| `ScalabilityBenchmark.java` | **Exp 1** — DET vs PROB overhead: 5 dataset scales × 4 K-values × {Q-BGP, Q-CDF, Q-Mean} query pairs, 15 warm-up + 30 timed repetitions | `benchmark/results/exp1_overhead.csv` |
| `InEngineVsExternalBenchmark.java` | **Exp 2** — In-engine JSD vs external Python MC: pair counts {100, 500, 1K, 5K, 10K} × θ {0.1, 0.3, 0.5} | `benchmark/results/exp2_inengine.csv`, `exp2_pairs.json` |
| `Exp4DispatchTest.java`, `Exp4MicroBenchmark.java`, `Exp4CrossTypeJSD.java`, `Exp4EndToEnd.java`, `Exp4DirichletDemo.java` | **Exp 4** — current generalization pipeline | `benchmark/results/exp4_*.csv` |

Also updated: `ClassificationAccuracyBenchmark.java` — raised `REPEAT` constant from 5 → 10 repetitions.

### 2.3 Python Scripts — 6 new files

| Script | Purpose |
|--------|---------|
| `benchmark/scripts/Experiments1/generate_exp1_main_deterministic.py` | Converts GMM TTL → `xsd:double` point-value TTL for deterministic (DET) baseline dataset |
| `benchmark/scripts/generate_histogram_variants.py` | Converts GMM TTL → histogram TTL with configurable bin counts (B=20, 50, 100) |
| `benchmark/scripts/Experiments2/exp2_external_baseline.py` | Pure-Python Monte Carlo JSD baseline: 5 000 samples per pair, matches `exp2_pairs.json` pairing |
| `benchmark/scripts/Experiments2/analyze_exp2.py` | Merges in-engine + external CSVs, computes speedup ratio, produces latency comparison plot |
| `benchmark/scripts/Experiments1/analyze_exp1_main.py` | Reads `exp1_overhead.csv`, outputs per-query DET/PROB overhead tables and plot |
| `benchmark/scripts/Experiments4/analyze_exp4.py` | Reads the current Exp 4 CSV suite (`exp4_dispatch.csv`, `exp4_micro.csv`, `exp4_crosstype.csv`, `exp4_endtoend.csv`, `exp4_dirichlet_demo.csv`) |

### 2.4 Shell Script Update

`benchmark/scripts/run_all_experiments.sh` updated with:
- Step 0a: generate deterministic dataset
- Step 0b: generate histogram variants
- Step 1a: run Exp 1 (`ScalabilityBenchmark`)
- Step 1b: run Exp 2 (`InEngineVsExternalBenchmark`)
- Step 5: run the current Exp 4 pipeline (`run_exp4_full.sh`)
- Updated analysis section for all new scripts

---

## 3. Bug Fixes (Session 2)

`mvn compile` and `mvn test-compile` both succeeded before and after fixes. All items below were real warnings (unused import/variable, dead code, deprecated API) flagged by the Java compiler.

| File | Issue | Fix Applied |
|------|-------|-------------|
| `propertyfunctions/ExactJoinPF.java` | Unused import `BindingFactory`; unused variable `Binding newBinding` | Removed both |
| `server/ProbSPARQLFuseki.java` | Unused import `ModelFactory` | Removed import |
| `FullBenchmark.java` | Unused variable `String[] queryNames` | Removed variable declaration |
| `U5KComplexityTest.java` | Unused variable `int d = x.length` inside `logGaussianPDF` | Removed variable |
| `functions/comparison/JSDivergence.java` | Unused field `private final int sampleCount`; unused local `int K = gmm.getK()` in `sampleFromGMM`; dead private method `computeJSDivergence(GMMValue, GMMValue, int)` | Removed field + constructor assignment; removed local; removed dead method |
| `examples/U1_ProbabilisticThresholding.java` | Deprecated `FileManager.get().open(path)` API | Replaced with `RDFDataMgr.open(path)` using try-with-resources; updated imports |
| `examples/U3_DistributionTransformation.java` | Same `FileManager` deprecation | Same fix |
| `examples/U4_DistributionManipulation.java` | Same `FileManager` deprecation | Same fix |

---

## 4. Known Remaining Issues

### 4.1 IDE Language Server False Positives

The following "cannot be resolved" errors appear in the VSCode Java Language Server but do **not** block Maven compilation:

| File | Reported Symbol | Root Cause |
|------|----------------|-----------|
| `FuseJoinDemo.java` | `GMMDatatype` | LS can't index custom SNAPSHOT across complex jena sub-module structure |
| `SimilarityJoinAccuracyBenchmark.java` | `GMMDatatype`, `JSDivergence`, `JSDivergenceConfig` | Same |
| `ProbabilisticJoinTest.java` | `GMMDatatype` | Same |
| `ProbabilisticJoinFrameworkDemo.java` | `ProbabilisticJoins`, `ProbJoinFunc` | Same |

**Resolution:** These clear after running `mvn clean compile` which forces the IDE to refresh its index.

### 4.2 Pre-Existing Compiler Warning (Not Introduced Here)

- `QueryRunner.java` — `deprecated item is not annotated with @Deprecated`  
  Pre-existing warning in the Jena ARQ codebase. Not a red file; not introduced by Stage 2 changes.

---

## 5. Expected Experiment Output Files

Run `benchmark/scripts/run_all_experiments.sh` to generate all outputs.

| Output File | Generated By | Columns |
|------------|-------------|---------|
| `benchmark/results/exp1_overhead.csv` | `ScalabilityBenchmark` | Scale, K, QueryID, Type, Median_ms, IQR_ms, OverheadRatio |
| `benchmark/results/exp2_inengine.csv` | `InEngineVsExternalBenchmark` | NPairs, Theta, Approach, Median_ms, ResultCount |
| `benchmark/results/exp2_external.csv` | `exp2_external_baseline.py` | NPairs, Theta, Approach, Median_ms, ResultCount |
| `benchmark/results/exp4_dispatch.csv` | `Exp4DispatchTest` | Function, DistType, Status, ResultCount |
| `benchmark/results/exp4_micro.csv` | `Exp4MicroBenchmark` | Function, DistType, Param, MedianUs, IQRUs |

---

## 6. Summary

| Item | Count | Status |
|------|-------|--------|
| New Java files | 8 | ✅ Compiled |
| Modified Java files | 2 (ProbSPARQL.java, ClassificationAccuracyBenchmark.java) | ✅ Compiled |
| New Python scripts | 6 | ✅ Created |
| Updated shell scripts | 1 | ✅ Updated |
| Bug-fixed Java files | 8 | ✅ Warnings resolved |
| Maven build | — | ✅ BUILD SUCCESS |
| Experiments run | — | ⏳ Pending execution |
