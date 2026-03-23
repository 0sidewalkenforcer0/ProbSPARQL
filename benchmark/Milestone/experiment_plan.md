# ProbSPARQL Evaluation Experiment Plan

## Overview

| Experiment | Section | Goal | Priority |
|-----------|---------|------|----------|
| Exp 1: System Overhead | 6.2.1 | Quantify cost of probabilistic extensions vs. standard SPARQL | High |
| Exp 2: In-Engine vs. External | 6.2.2 | Justify native probabilistic processing inside the query engine | Medium |
| Exp 3.1: SimJoin Accuracy | 6.2.3 | Compare sampling methods across difficulty levels | High |
| Exp 3.2: SimJoin Convergence | 6.2.3 | Determine how many samples are needed for reliable results | High |
| Exp 3.3: Selectivity Sensitivity | 6.2.3 | Observe behavior under varying similarity thresholds | Medium |
| Exp 4: Generalization (Histogram) | 6.3 | Demonstrate the framework works beyond GMMs | High |

---

## Datasets

### Dataset A: Scalable RDF Knowledge Graph (GMM)
- **Used in:** Exp 1, Exp 2
- **Content:** Synthetic angle grinder instances with GMM-encoded measurements
- **Scales:** ~1K, 5K, 10K, 50K, 100K triples (10–1000 products)
- **Components per product:** 5 (crown gear, motor, spindle, bearing, housing)
- **Measurements per characteristic:** 1–3
- **GMM parameters:**
  - Means: sampled from physically plausible ranges per characteristic
  - Variances: log-uniform from [0.01, 0.5]
  - Weights: Dirichlet(1,...,1)
  - K ∈ {1, 3, 5, 10}
  - d = 1 (primary)
- **Format:** RDF/Turtle files, loadable into Jena Fuseki
- **Deterministic parallel:** Same graph but with point estimates (xsd:double) instead of GMM literals

### Dataset B: Controlled Distribution Pairs
- **Used in:** Exp 3.1, Exp 3.3
- **Content:** Pairs of GMMs with known ground-truth JS divergence
- **Generation method:**
  1. Generate base GMM G₁
  2. Perturb means/variances to create G₂
  3. Compute ground-truth JS with 10⁶ samples
  4. Accept/reject pair based on target difficulty
- **Difficulty levels (relative to θ = 0.2):**
  - Easy-similar: JS ∈ [0.01, 0.05], 200 pairs
  - Easy-dissimilar: JS ∈ [0.4, 0.6], 200 pairs
  - Medium: JS ∈ [0.10, 0.15] ∪ [0.25, 0.30], 200 pairs
  - Hard: JS ∈ [0.18, 0.22], 200 pairs
- **Default parameters:** K = 2, d = 1
- **Format:** JSON file mapping pair_id → (GMM₁, GMM₂, true_JS, difficulty_label)

### Dataset C: Convergence Pair
- **Used in:** Exp 3.2
- **Content:** One fixed "hard" pair of GMMs where JS ≈ θ (e.g., JS = 0.199 when θ = 0.2)
- **Parameters:** K = 2, d = 1
- **Ground truth:** Computed with 10⁶ samples

### Dataset D: Histogram Variants
- **Used in:** Exp 4
- **Content:** Parallel to Dataset A and Dataset B, but with histogram literals
- **Generation:** Draw 10⁴ samples from each GMM, bin into B ∈ {20, 50, 100} bins
- **Format:** Parallel Turtle files (Dataset A') and JSON file (Dataset B')

---

## Experiment Details

---

### Experiment 1: System Overhead

**Question:** What is the runtime cost introduced by probabilistic literals and operations compared to standard SPARQL?

**Method:** Compare pairs of logically equivalent queries — one deterministic, one probabilistic.

**Query pairs:**

| ID | Deterministic (baseline) | ProbSPARQL |
|----|-------------------------|------------|
| Q1a | `FILTER(?length < 9.8)` | `BIND(prob:cdf(?dist, 9.8) AS ?p) FILTER(?p >= 0.9)` |
| Q1b | `FILTER(?speed * ?torque > 500)` | `BIND(prob:product(?gmmSpeed, ?gmmTorque) AS ?powerDist) BIND(prob:mean(?powerDist) AS ?power)` |
| Q1c | Simple BGP with xsd:double | Same BGP with uq:gmmLiteral (measures literal parsing overhead) |

**Independent variables:**
- Graph scale: 1K, 5K, 10K, 50K, 100K triples
- GMM complexity: K = 1, 3, 5, 10

**Dependent variable:** Query execution time (ms)

**Protocol:**
1. Load Dataset A (specific scale) into Jena Fuseki
2. Warm up: run each query 5 times, discard results
3. Measure: run each query 30 times
4. Record median and interquartile range
5. Repeat for each (scale × K) combination

**Expected output:**
- Table: rows = graph scale, columns = K values, cells = overhead ratio (ProbSPARQL time / baseline time)
- Line chart: x = graph scale, y = execution time, separate lines for baseline vs. ProbSPARQL

**Key insight to demonstrate:** Overhead is bounded and predictable; it scales with K (distribution complexity), not with graph size.

---

### Experiment 2: Value of In-Engine Processing

**Question:** Is it faster to compute probabilistic operations inside the SPARQL engine or to export data and process externally?

**Method:** Compare two workflows for the same task (computing JS divergence between distribution pairs and filtering).

**Approach A — ProbSPARQL (in-engine):**
```sparql
SELECT ?gear
WHERE {
  ?gear a ag:CrownGear ; cfm:hasCharacteristic ?char .
  ?m_ct cfm:measuresCharacteristic ?char ;
        cfm:hasProbabilisticValue ?rv_ct .
  ?rv_ct uq:hasDistribution ?gmm_ct .
  ?m_sl cfm:measuresCharacteristic ?char ;
        cfm:hasProbabilisticValue ?rv_sl .
  ?rv_sl uq:hasDistribution ?gmm_sl .
  BIND(prob:jsd(?gmm_ct, ?gmm_sl) AS ?div)
  FILTER(?div > 0.2)
}
```

**Approach B — Post-processing (external):**
1. SPARQL query: SELECT all (?gmm_ct, ?gmm_sl) pairs
2. Export results as JSON/CSV
3. Python script: parse GMMs, compute JS divergence, filter
4. Total time = query time + export time + Python time

**Independent variables:**
- Number of distribution pairs: 100, 500, 1K, 5K, 10K
- Selectivity (% of pairs passing filter): 10%, 50%, 90%

**Dependent variable:** End-to-end time (ms)

**Protocol:**
1. Load Dataset A at appropriate scale
2. Measure Approach A: ProbSPARQL query time
3. Measure Approach B: SPARQL export time + data transfer + Python computation time
4. Repeat 30 times each

**Expected output:**
- Bar chart: grouped by number of pairs, two bars per group (in-engine vs. external)
- Highlight: at low selectivity (10%), in-engine should be dramatically faster due to early filtering

**Key insight to demonstrate:** In-engine processing avoids materializing intermediate results and benefits from early filtering.

---

### Experiment 3.1: Similarity Join — Accuracy across Difficulties

**Question:** Which sampling method most accurately classifies distribution pairs as similar/dissimilar?

**Method:** Run each sampling method on Dataset B; compare predicted classification against ground truth.

**Four sampling methods:**
- M1: Naive Monte Carlo
- M2: Importance sampling
- M3: Stratified sampling
- M4: [Your fourth method — TBD]

**Independent variables:**
- Difficulty level: easy-similar, easy-dissimilar, medium, hard
- Sampling method: M1, M2, M3, M4
- Fixed sample size: [choose a reasonable default, e.g., N = 10,000]
- θ = 0.2

**Dependent variables:**
- Accuracy (%)
- Precision
- Recall
- F1-score

**Protocol:**
1. For each pair in Dataset B:
   a. Run each sampling method with N samples
   b. Compute estimated JS divergence
   c. Classify: similar (JS ≤ θ) or dissimilar (JS > θ)
   d. Compare against ground-truth label
2. Aggregate metrics per (method × difficulty)
3. Repeat 10 times to capture variance

**Expected output:**
- Table: rows = difficulty level, columns = methods, cells = accuracy (± std)
- Grouped bar chart: x = difficulty, y = accuracy, grouped by method
- All methods should perform well on "easy"; differences emerge on "medium" and "hard"

---

### Experiment 3.2: Similarity Join — Convergence Analysis

**Question:** How quickly does each sampling method converge to the ground truth?

**Method:** Fix one "hard" pair (Dataset C); run each method with increasing sample sizes.

**Independent variables:**
- Sample size N: 100, 500, 1K, 5K, 10K, 50K, 100K
- Sampling method: M1, M2, M3, M4

**Dependent variables:**
- Estimated JS divergence
- Absolute error: |estimated JS − true JS|
- Standard deviation across runs
- Computation time (ms)

**Protocol:**
1. For each (method × sample size):
   a. Run the method 50 times
   b. Record estimated JS each time
2. Compute mean, std, absolute error
3. Also record wall-clock time per run

**Expected output:**
- Line chart: x = sample size (log scale), y = estimated JS, one line per method, horizontal dashed line = ground truth
- Line chart: x = sample size (log scale), y = absolute error, one line per method
- Shaded regions showing ±1 std
- Secondary plot: x = sample size, y = time (ms), one line per method

**Key insight to demonstrate:** Method X converges at N = Y samples, which is Z× faster than naive Monte Carlo.

---

### Experiment 3.3: Selectivity Sensitivity

**Question:** How does system behavior change when the similarity threshold varies?

**Method:** Fix Dataset B; run SimJoin with each method across varying θ values.

**Independent variables:**
- θ values: 0.01, 0.05, 0.1, 0.2, 0.3, 0.5
- Sampling method: M1, M2, M3, M4
- Fixed sample size: N = 10,000

**Dependent variables:**
- Execution time (ms)
- Number of pairs returned (result set size)
- Accuracy (compared to ground truth at each θ)

**Protocol:**
1. For each (method × θ):
   a. Run SimJoin on all 800 pairs from Dataset B
   b. Record time, result count, per-pair classification
   c. Compare classification against ground truth (recomputed for each θ)
2. Repeat 10 times

**Expected output:**
- Line chart: x = θ, y = execution time, one line per method
- Line chart: x = θ, y = accuracy, one line per method
- Bar chart: x = θ, y = result set size

**Key insight to demonstrate:** Practical guidance for users — how to choose θ and which method to prefer under different strictness levels.

---

### Experiment 4: Generalization — Histogram Distributions

**Question:** Does ProbSPARQL work with non-parametric distributions?

**Method:** Repeat Experiment 1 (overhead) and Experiment 3.1 (accuracy) using histogram literals instead of GMM literals.

**Sub-experiment 4.1: Overhead with Histograms**
- Same as Exp 1, but using Dataset A' (histogram)
- Compare: GMM overhead vs. histogram overhead vs. deterministic baseline
- Additional variable: bin count B ∈ {20, 50, 100}

**Sub-experiment 4.2: SimJoin Accuracy with Histograms**
- Same as Exp 3.1, but using Dataset B' (histogram)
- JS divergence computed via discrete sum over bins
- Compare accuracy of sampling methods on histograms vs. GMMs

**Expected output:**
- Table comparing overhead ratios: deterministic vs. GMM vs. histogram (at different bin counts)
- Accuracy comparison table: GMM pairs vs. histogram pairs

**Key insight to demonstrate:** The framework is not GMM-specific; histograms work with comparable performance and accuracy.

---

## Implementation Checklist

### Data Generation Scripts
- [ ] `generate_rdf_kg.py` — Generates Dataset A (Turtle files at each scale)
- [ ] `generate_rdf_kg_deterministic.py` — Generates deterministic parallel of Dataset A
- [ ] `generate_controlled_pairs.py` — Generates Dataset B (JSON)
- [ ] `generate_convergence_pair.py` — Generates Dataset C (JSON)
- [ ] `generate_histogram_variants.py` — Generates Dataset D from A and B

### Experiment Scripts
- [ ] `exp1_overhead.sh` — Runs Exp 1 queries at each (scale × K)
- [ ] `exp2_in_engine_vs_external.sh` — Runs Exp 2 both approaches
- [ ] `exp2_external_baseline.py` — Python post-processing baseline
- [ ] `exp3_1_accuracy.py` — Runs Exp 3.1 (all methods × all pairs)
- [ ] `exp3_2_convergence.py` — Runs Exp 3.2 (fixed pair × varying N)
- [ ] `exp3_3_selectivity.py` — Runs Exp 3.3 (varying θ)
- [ ] `exp4_histogram.sh` — Runs Exp 4 (histogram variants)

### Analysis & Visualization
- [ ] `analyze_exp1.py` — Produces overhead table and chart
- [ ] `analyze_exp2.py` — Produces in-engine vs. external chart
- [ ] `analyze_exp3_1.py` — Produces accuracy table and grouped bar chart
- [ ] `analyze_exp3_2.py` — Produces convergence line charts
- [ ] `analyze_exp3_3.py` — Produces selectivity charts
- [ ] `analyze_exp4.py` — Produces generalization comparison

---

## Environment

- **Hardware:** [TODO: specify CPU, RAM, OS]
- **Software:** Apache Jena Fuseki [version], Java [version], Python 3.x
- **Repetitions:** 30 per query (Exp 1, 2), 10–50 per configuration (Exp 3)
- **Reporting:** Median + interquartile range (Exp 1, 2); Mean ± std (Exp 3)

---

## Timeline Suggestion

| Week | Task |
|------|------|
| 1 | Implement data generation scripts; generate all datasets |
| 2 | Implement Exp 1 + Exp 3.1 (highest priority experiments) |
| 3 | Implement Exp 3.2 + Exp 4 (high priority) |
| 4 | Implement Exp 2 + Exp 3.3 (medium priority) |
| 5 | Analysis, visualization, write Section 6.2 and 6.3 |
