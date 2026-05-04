# ProbSPARQL Evaluation Plan

**Status:** active benchmark scope  
**Updated:** 2026-04-18

## Overview

This document defines the active benchmark scope for the ProbSPARQL repository.

The benchmark suite currently contains five active experiments:

| Experiment | Focus | Current Scope |
|---|---|---|
| Exp1 | System overhead and robustness | Main DET vs PROB overhead benchmark, plus a permutation-invariance supplement |
| Exp2 | Operator-level optimization | In-engine filtering vs `SIMILARITYJOIN` |
| Exp3 | JSD method comparison | Single benchmark for controlled comparison of GMM JSD strategies |
| Exp4 | Generalization across distribution families | GMM / histogram / Dirichlet dispatch, microbenchmarks, and end-to-end cases |
| Exp5 | Execution placement | In-engine filtering vs post-processing |

## Design Principles

- Each experiment should have one clear ownership boundary.
- Current benchmark directories should reflect only the active scope.
- Historical artifacts may remain under `archive` or `legacy`, but they are not part of the current evaluation protocol.
- Query-driven experiments should keep queries in `benchmark/queries/expN` when that improves maintainability.
- Method-level benchmarks that are not query-driven may keep logic in Java benchmark classes.

## Experiment 1: System Overhead

### Objective

Measure the runtime overhead of probabilistic execution relative to deterministic SPARQL baselines, and evaluate robustness to GMM component permutation.

### Active Structure

- Data:
  - `benchmark/data/exp1/component`
  - `benchmark/data/exp1/permutation`
- Queries:
  - `benchmark/queries/exp1/det`
  - `benchmark/queries/exp1/prob`
  - `benchmark/queries/exp1/variants`
- Results:
  - `benchmark/results/exp1/component`
  - `benchmark/results/exp1/permutation`
  - `benchmark/results/exp1/archive`

### Active Runs

1. **Exp1 component**
   - Default scales: `E1 E3 E5 E7`
   - Measures DET vs PROB latency across the main query set.

2. **Exp1 permutation**
   - Default scale: `E5`
   - Tests whether permuting GMM components changes runtime or result counts.

### Notes

- `q4.sparql` is the active legacy `prob:jsdivergence` path used when evaluating the adaptive JSD implementation in the permutation supplement.
- Variant queries such as `q4_jsd.sparql` are retained only for controlled comparisons.

## Experiment 2: In-Engine Filtering vs SIMILARITYJOIN

### Objective

Measure the benefit of a dedicated similarity-join operator over ordinary in-engine filtering plans.

### Active Structure

- Data:
  - `benchmark/data/exp2`
- Queries:
  - `benchmark/queries/exp2/collect_multimodal.sparql`
  - `benchmark/queries/exp2/inengine_cheapfirst.sparql`
  - `benchmark/queries/exp2/inengine_jsdfirst.sparql`
  - `benchmark/queries/exp2/similarityjoin.sparql`
- Results:
  - `benchmark/results/exp2`
  - `benchmark/results/exp2/archive`

### Compared Strategies

- `InEngine_CheapFirst`
- `InEngine_JSDFirst`
- `SimilarityJoin`

### Notes

- The formal Exp2 comparison is limited to in-engine filtering plans and `SIMILARITYJOIN`.

## Experiment 3: JSD Method Benchmark

### Objective

Compare five GMM JSD strategies under a controlled classification benchmark:

- `V1_MC`
- `V2_STRATIFIED`
- `V3_SPRT`
- `V4_BOUNDS`
- `V5_ADAPTIVE`

### Scope

Exp3 is a single active benchmark focused on JSD method comparison under controlled classification.

### Active Structure

- Data:
  - `benchmark/data/exp3`
- Results:
  - `benchmark/results/exp3`
  - `benchmark/results/exp3/archive`

### Official Dataset Configuration

The official dataset uses:

- `K = 5`
- `N = 300`

This parameterization is preserved in code comments and script headers, but not in the current directory name.

### Ground Truth

- `simjoin_ground_truth.csv` is stored alongside the Exp3 TTL datasets in `benchmark/data/exp3`.
- The benchmark reads this CSV directly instead of recomputing a separate runtime ground truth.

### Notes

- Exp3 is a method-evaluation benchmark, not a query-driven benchmark.
- Therefore it does not maintain a dedicated `benchmark/queries/exp3` directory.

## Experiment 4: Generalization Beyond GMM

### Objective

Show that ProbSPARQL generalizes beyond GMM literals to additional distribution families and cross-type JSD handling.

### Active Structure

- Data:
  - `benchmark/data/exp4`
- Queries:
  - `benchmark/queries/exp4`
- Results:
  - `benchmark/results/exp4`
  - `benchmark/results/exp4/archive`

### Active Components

- Dispatch validation
- Microbenchmarks
- Cross-type JSD evaluation
- End-to-end query cases
- Dirichlet demo

### Notes

- Histogram variant generation is treated as part of the active Exp4 tooling.

## Experiment 5: In-Engine vs Post-Processing

### Objective

Compare two execution placements for the same logical filtering task:

- execute the probabilistic computation inside the engine
- fetch candidates and filter them outside the engine

### Active Structure

- Data:
  - `benchmark/data/exp5`
- Queries:
  - `benchmark/queries/exp5/inengine_early_filter.sparql`
  - `benchmark/queries/exp5/postprocessing_fetch_all.sparql`
- Results:
  - `benchmark/results/exp5`
  - `benchmark/results/exp5/archive`

## Repository Conventions

### Data

- Experiments with one active dataset family use a single `benchmark/data/expN` directory.
- Experiments with multiple active data lines may use subdirectories such as `main` and `permutation`.

### Results

- Current results live at `benchmark/results/expN`.
- Historical material should move to `benchmark/results/expN/archive` where possible.

### Scripts

- Active experiment runners and helpers live under `benchmark/scripts/ExperimentsN`.
- Historical plotting or exploratory scripts should live under `benchmark/scripts/legacy`.

### Queries

- Active experiment queries live under `benchmark/queries/expN`.
- Old exploratory queries should live under `benchmark/queries/legacy`.

## Maintenance Rule

When the benchmark layout changes, update:

1. directory layout
2. default script paths
3. Java benchmark default paths
4. documentation
