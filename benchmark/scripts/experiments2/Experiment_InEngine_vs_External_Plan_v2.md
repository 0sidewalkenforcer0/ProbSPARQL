# Experiment 2: Value of In-Engine Processing (Revised)

## 1. Research Question

What are the performance benefits of integrating probabilistic operations into the SPARQL query engine, and how much additional gain can advanced execution strategies (pruning, early termination) provide?

### Sub-questions

- **RQ2.1:** How much faster is naive in-engine filtering compared to external post-processing?
- **RQ2.2:** How much additional speedup does a dedicated similarity join operator with pruning achieve over naive in-engine filtering?
- **RQ2.3:** How do these relative advantages change with dataset scale and selectivity?

---

## 2. Motivation

Experiment 1 showed that JSD computation (Q3) is the most expensive ProbSPARQL operation — up to 30 seconds at scale E6/K=10. A reviewer will naturally ask two questions:

1. **"Why not just export and compute in Python/NumPy?"** → Approach A vs B answers this.
2. **"Even inside the engine, isn't brute-force JSD computation too slow?"** → Approach C answers this.

By introducing three approaches, we demonstrate a **performance hierarchy**:

```
Approach B (External)  <<  Approach A (In-engine naive)  <<  Approach C (In-engine optimized)
         ↑                         ↑                              ↑
   serialization cost        avoids export              avoids unnecessary JSD computations
                             early filtering             via pruning and early termination
```

This tells a stronger story than just "in-engine is faster": it shows that engine integration enables **algorithmic optimizations** that are impossible in the external approach.

---

## 3. Three Approaches

### Approach A — In-Engine Naive Filter

A standard ProbSPARQL query that computes JSD for **every** distribution pair produced by the graph pattern, then filters.

```sparql
SELECT ?gear ?jsDivergence
WHERE {
  ?gear a ag:CrownGear ;
        cfm:hasCharacteristic ?char .
  ?m1 cfm:measuresCharacteristic ?char ;
      cfm:hasProbabilisticValue ?rv1 .
  ?rv1 uq:hasDistribution ?gmm1 .
  ?m2 cfm:measuresCharacteristic ?char ;
      cfm:hasProbabilisticValue ?rv2 .
  ?rv2 uq:hasDistribution ?gmm2 .
  FILTER(?m1 != ?m2)
  BIND(prob:jsd(?gmm1, ?gmm2) AS ?jsDivergence)
  FILTER(?jsDivergence > ?threshold)
}
```

**Characteristics:**
- Computes JSD for ALL pairs
- Filters after computation
- No pruning, no early termination
- Baseline for in-engine processing

---

### Approach B — External Post-Processing

Export distribution pairs via standard SPARQL, compute JSD in Python.

**Step 1 — Export query:**
```sparql
SELECT ?gear ?gmm1 ?gmm2
WHERE {
  ?gear a ag:CrownGear ;
        cfm:hasCharacteristic ?char .
  ?m1 cfm:measuresCharacteristic ?char ;
      cfm:hasProbabilisticValue ?rv1 .
  ?rv1 uq:hasDistribution ?gmm1 .
  ?m2 cfm:measuresCharacteristic ?char ;
      cfm:hasProbabilisticValue ?rv2 .
  ?rv2 uq:hasDistribution ?gmm2 .
  FILTER(?m1 != ?m2)
}
```

**Step 2 — Python computation:**
```python
for row in exported_results:
    gmm1, gmm2 = parse_gmm(row['gmm1']), parse_gmm(row['gmm2'])
    jsd = estimate_jsd_mc(gmm1, gmm2, n_samples=10000)
    if jsd > threshold:
        results.append((row['gear'], jsd))
```

**Characteristics:**
- Full materialization of all pairs
- Serialization overhead (JSON over HTTP)
- Re-parsing of GMM literals in Python
- No pruning possible (Python sees raw JSON, not distribution objects)

---

### Approach C — Probabilistic Similarity Join (In-Engine Optimized)

A dedicated join operator that uses **pruning strategies** to avoid computing full JSD for pairs that can be ruled out cheaply.

```sparql
SELECT ?gear
WHERE {
  { ?gear a ag:CrownGear ;
          cfm:hasCharacteristic ?char .
    ?m1 cfm:measuresCharacteristic ?char ;
        cfm:hasProbabilisticValue ?rv1 .
    ?rv1 uq:hasDistribution ?gmm1 . }
  SIMJOIN(?gmm1, ?gmm2, ?threshold)
  { ?m2 cfm:measuresCharacteristic ?char ;
        cfm:hasProbabilisticValue ?rv2 .
    ?rv2 uq:hasDistribution ?gmm2 . }
}
```

**Pruning strategies (executed before full JSD computation):**

#### Pruning Level 1: Dimensionality & Type Check
- Reject pairs with incompatible dimensionality (d₁ ≠ d₂)
- Cost: O(1)

#### Pruning Level 2: Mean-Based Pre-Filter
- Compute |μ₁ − μ₂| where μ₁, μ₂ are the overall means of the two GMMs
- If |μ₁ − μ₂| > δ_upper, the distributions are far apart → skip (JSD certainly > θ, classify as dissimilar)
- If |μ₁ − μ₂| < δ_lower, the distributions are very close → skip (JSD certainly ≤ θ, classify as similar)
- δ_upper and δ_lower are derived from the relationship between mean distance and JSD bounds
- Cost: O(K) — just a weighted mean computation

#### Pruning Level 3: Variance-Based Refinement
- After mean check is inconclusive, compare overall variances
- Distributions with very different variances are likely dissimilar even if means are close
- Cost: O(K)

#### Pruning Level 4: Analytic JSD Bounds
- Compute upper and lower bounds on JSD without full MC sampling
- Use the V4_BOUNDS method from Experiment 3
- If bounds are both above θ or both below θ → classify without sampling
- Cost: much cheaper than full MC sampling

#### Pruning Level 5: Full JSD Computation (fallback)
- Only reached for pairs that cannot be resolved by levels 1–4
- Use V1_MC or V5_ADAPTIVE sampling
- Cost: O(N × K) where N is the sample size

**Characteristics:**
- Exploits engine-internal access to parsed distribution objects (means, variances already in memory)
- Cascading pruning: cheap checks first, expensive computation only when necessary
- The fraction of pairs reaching Level 5 depends on data distribution and θ

---

## 4. What Each Comparison Demonstrates

| Comparison | What It Shows |
|-----------|--------------|
| **A vs B** | Value of **location**: avoiding serialization and enabling engine-internal filtering |
| **C vs A** | Value of **algorithmic optimization**: pruning reduces the number of expensive JSD computations |
| **C vs B** | Combined advantage: location + algorithm |

This three-way comparison is stronger than a two-way comparison because it separates two distinct sources of speedup:
- A reviewer cannot dismiss the result as "just avoiding I/O overhead"
- The pruning advantage demonstrates why distributions should be **first-class engine objects**, not opaque blobs

---

## 5. Datasets

Reuse **Dataset A** from Experiment 1.

### 5.1 Scale Configurations

| Config | Source Dataset | Approx. Distribution Pairs | Purpose |
|--------|--------------|---------------------------|---------|
| P100 | E3 | ~100 | Small |
| P500 | E4 | ~500 | Medium-small |
| P1K | E5 | ~1,000 | Medium |
| P5K | E6 | ~5,000 | Medium-large |
| P10K | E7 | ~10,000 | Large |

**Pre-step:** Run the export query (Approach B Step 1) on each dataset to determine actual pair counts.

### 5.2 Selectivity Calibration

Before benchmarking, compute the empirical JSD distribution for each dataset:

1. Run Approach A with `FILTER(?jsDivergence > 0)` (no real filter)
2. Collect all JSD values
3. Pick θ values at 10th, 50th, 90th percentiles

| Selectivity | Meaning | θ determination |
|------------|---------|----------------|
| 10% (strict) | Only 10% of pairs pass | θ = 90th percentile of JSD values |
| 50% (moderate) | Half the pairs pass | θ = 50th percentile (median JSD) |
| 90% (lenient) | Most pairs pass | θ = 10th percentile of JSD values |

### 5.3 GMM Complexity

Fix **K = 3** for this experiment (moderate complexity). The Exp 1 results showed K has limited impact on Q1/Q2 overhead ratios; the focus here is on scale and selectivity, not on K.

---

## 6. Protocol

### 6.1 Preparation (run once per dataset)

1. Load dataset into Jena Fuseki
2. Run calibration query to determine:
   - Total pair count
   - Empirical JSD distribution
   - θ values for 10%, 50%, 90% selectivity
3. Record and save calibration results

### 6.2 Approach A — In-Engine Naive

For each (dataset × θ):
1. Warm-up: 5 runs, discard
2. Measurement: 30 runs
3. Record: per-run time, result count

### 6.3 Approach B — External

For each (dataset × θ):

**Step 1 — Export:**
1. Warm-up: 5 runs, discard
2. Measurement: 30 runs
3. Record: query time, JSON response size (bytes)

**Step 2 — Python:**
1. Load exported JSON (from one representative Step 1 run)
2. Measurement: 30 runs of parse + compute + filter
3. Record: per-run time, breakdown (parse / compute / filter)

**Total B time** = median(Step 1) + median(Step 2)

**Python implementation notes:**
- Use `json` (standard library, not ujson/orjson)
- JSD via MC sampling, N = 10,000 (same as engine default)
- Single-threaded (fair comparison with single-query Jena)

### 6.4 Approach C — Similarity Join with Pruning

For each (dataset × θ):
1. Warm-up: 5 runs, discard
2. Measurement: 30 runs
3. Record: per-run time, result count, **pruning statistics**

**Pruning statistics to collect (per run):**

| Statistic | Description |
|-----------|-------------|
| total_pairs | Total candidate pairs from graph pattern |
| pruned_by_mean | Pairs resolved by mean-based pre-filter |
| pruned_by_variance | Pairs resolved by variance check |
| pruned_by_bounds | Pairs resolved by analytic JSD bounds |
| computed_full_jsd | Pairs requiring full MC sampling |
| result_count | Final pairs passing threshold |

These statistics are critical for explaining **why** Approach C is faster.

### 6.5 Restart Policy

- Restart Jena between datasets (different scales)
- Do NOT restart between θ values within the same dataset
- Fresh Python process per configuration

### 6.6 Sanity Check

After all runs, verify for each (dataset × θ):
- Approach A result count ≈ Approach B result count ≈ Approach C result count
- Small differences are acceptable due to MC sampling variance
- If large discrepancies exist → debug before proceeding

---

## 7. Metrics

### Primary Metrics

| Metric | Definition |
|--------|-----------|
| Time A (ms) | Median execution time, Approach A |
| Time B (ms) | Median total time, Approach B (export + Python) |
| Time C (ms) | Median execution time, Approach C |
| Speedup A/B | Time B / Time A |
| Speedup C/A | Time A / Time C |
| Speedup C/B | Time B / Time C |

### Secondary Metrics

| Metric | Definition |
|--------|-----------|
| Pruning rate (%) | (total_pairs − computed_full_jsd) / total_pairs × 100 |
| Export size (KB) | JSON response size from Approach B Step 1 |
| B breakdown (ms) | Export time, parse time, compute time, filter time |

---

## 8. Expected Output

### 8.1 Main Result Table

**Table: Execution time (ms) across three approaches**

| Pairs | Sel. | A: Naive (ms) | B: External (ms) | C: SimJoin (ms) | A/B | C/A | C/B |
|-------|------|--------------|-------------------|-----------------|-----|-----|-----|
| ~100 | 10% | | | | | | |
| ~100 | 50% | | | | | | |
| ~100 | 90% | | | | | | |
| ~1K | 10% | | | | | | |
| ~1K | 50% | | | | | | |
| ~1K | 90% | | | | | | |
| ~5K | 10% | | | | | | |
| ~5K | 50% | | | | | | |
| ~5K | 90% | | | | | | |
| ~10K | 10% | | | | | | |
| ~10K | 50% | | | | | | |
| ~10K | 90% | | | | | | |

### 8.2 Pruning Effectiveness Table

**Table: Pruning statistics for Approach C (at ~5K pairs)**

| Selectivity | Total Pairs | Pruned (Mean) | Pruned (Var) | Pruned (Bounds) | Full JSD | Pruning Rate |
|------------|-------------|--------------|-------------|----------------|----------|-------------|
| 10% | | | | | | |
| 50% | | | | | | |
| 90% | | | | | | |

### 8.3 External Breakdown Table

**Table: Approach B time breakdown (ms) at ~5K pairs**

| Component | 10% sel. | 50% sel. | 90% sel. |
|-----------|---------|---------|---------|
| Export query | | | |
| Python: parse | | | |
| Python: JSD compute | | | |
| Python: filter | | | |
| **Total B** | | | |
| **Approach A** | | | |
| **Approach C** | | | |

### 8.4 Charts

**Chart 1: Three-way comparison (main result)**
- X-axis: number of distribution pairs (log scale)
- Y-axis: execution time (ms, log scale)
- Three lines: A (naive), B (external), C (SimJoin)
- Fixed selectivity: 50%
- Expected shape: B >> A > C, gap widens with scale

**Chart 2: Selectivity effect**
- X-axis: selectivity (10%, 50%, 90%)
- Y-axis: execution time (ms)
- Three grouped bars per selectivity level
- Fixed scale: ~5K pairs
- Expected: C's advantage over A is largest at low selectivity (more pairs pruned)

**Chart 3: Pruning breakdown (stacked bar)**
- X-axis: selectivity (10%, 50%, 90%)
- Y-axis: number of pairs
- Stacked segments: pruned-by-mean, pruned-by-variance, pruned-by-bounds, full-JSD
- Fixed scale: ~5K pairs
- Shows how pruning composition changes with selectivity

**Chart 4: Speedup factors**
- X-axis: number of pairs
- Y-axis: speedup factor
- Two lines: Speedup C/B (total advantage), Speedup C/A (pruning advantage)
- Fixed selectivity: 50%

---

## 9. Key Insights to Demonstrate

### 9.1 Location Advantage (A vs B)
In-engine processing avoids serialization, re-parsing, and data transfer. At ~5K pairs, Approach A should be **X× faster** than Approach B. This advantage grows with scale.

### 9.2 Algorithmic Advantage (C vs A)
Pruning eliminates a significant fraction of JSD computations. The pruning rate should be highest at **low selectivity** (10%) because most pairs are far from the threshold and can be resolved by cheap mean/variance checks alone.

### 9.3 Combined Advantage (C vs B)
The total speedup of C over B combines both effects. This is the headline number for the paper.

### 9.4 Pruning Composition
Mean-based pre-filtering should handle the "easy" cases (distributions with very different or very similar means). Analytic bounds handle "medium" cases. Only "hard" cases (distributions with similar means but different shapes) require full sampling. The pruning statistics table makes this explicit.

### 9.5 Why This Requires Engine Integration
Approach C's pruning is only possible because the engine has **direct access to parsed distribution objects**. The mean, variance, and component structure are already in memory as typed Java objects. An external script would need to parse every GMM JSON string before it can even begin to prune — which eliminates much of the pruning benefit.

---

## 10. Implementation TODO

| # | Task | File | Priority |
|---|------|------|----------|
| 1 | Calibration script: pair counts + JSD distribution + θ values | `exp2_calibrate.py` | P0 |
| 2 | Approach A benchmark | `Exp2NaiveBenchmark.java` | P0 |
| 3 | Approach B Step 1: export query | `queries/exp2_export.sparql` | P0 |
| 4 | Approach B Step 2: Python baseline | `exp2_external_baseline.py` | P0 |
| 5 | Approach C: SimJoin with pruning | `Exp2SimJoinBenchmark.java` | P0 |
| 6 | — Pruning Level 1: type/dim check | (in SimJoin operator) | |
| 7 | — Pruning Level 2: mean-based filter | (in SimJoin operator) | |
| 8 | — Pruning Level 3: variance check | (in SimJoin operator) | |
| 9 | — Pruning Level 4: analytic bounds | (reuse V4_BOUNDS) | |
| 10 | — Pruning Level 5: full JSD fallback | (reuse V1_MC / V5_ADAPTIVE) | |
| 11 | — Pruning statistics collection | (counters per level) | |
| 12 | Orchestration script | `exp2_run.sh` | P1 |
| 13 | Analysis + visualization | `analyze_exp2.py` | P1 |

### Implementation Notes

**Approach C pruning implementation:**

```java
public class PrunedSimJoinEvaluator {

    public boolean evaluate(GMMDistribution g1, GMMDistribution g2, 
                           double theta, PruningStats stats) {
        stats.totalPairs++;
        
        // Level 1: dimensionality check
        if (g1.getDim() != g2.getDim()) {
            stats.prunedByDim++;
            return false;
        }
        
        // Level 2: mean-based pre-filter
        double meanDist = euclideanDistance(g1.getOverallMean(), 
                                            g2.getOverallMean());
        if (meanDist > upperBound(theta)) {
            stats.prunedByMean++;
            return false;  // certainly dissimilar
        }
        if (meanDist < lowerBound(theta)) {
            stats.prunedByMean++;
            return true;   // certainly similar
        }
        
        // Level 3: variance-based refinement
        double varRatio = g1.getOverallVariance() / g2.getOverallVariance();
        if (varRatio > varianceUpperBound(theta) || 
            varRatio < 1.0 / varianceUpperBound(theta)) {
            stats.prunedByVariance++;
            return false;  // very different spread → likely dissimilar
        }
        
        // Level 4: analytic JSD bounds
        double[] jsdBounds = computeJSDBounds(g1, g2);  // [lower, upper]
        if (jsdBounds[0] > theta) {
            stats.prunedByBounds++;
            return false;
        }
        if (jsdBounds[1] <= theta) {
            stats.prunedByBounds++;
            return true;
        }
        
        // Level 5: full JSD computation (fallback)
        stats.computedFullJSD++;
        double jsd = computeJSD_MC(g1, g2, N_SAMPLES);
        return jsd <= theta;
    }
}
```

**Determining pruning bounds:**

The relationship between mean distance and JSD is not exact, but bounds can be derived:
- For single-component Gaussians with equal variance σ²: JSD ≈ f(|μ₁-μ₂|/σ), a monotonically increasing function
- For GMMs: use the worst-case component pair to derive an upper bound
- Empirically calibrate δ_upper and δ_lower on Dataset B (from Exp 3) where ground-truth JSD is known

---

## 11. Potential Pitfalls

| Risk | Mitigation |
|------|-----------|
| Pruning bounds are too loose → few pairs pruned | Tighten bounds empirically; report pruning rate honestly |
| Pruning bounds are too tight → false positives/negatives | Validate against Approach A results; ensure classification agreement ≥ 95% |
| Approach C overhead (pruning logic) makes it slower than A for small datasets | Expected at very small scales; narrative focuses on larger scales |
| Python NumPy is actually faster than Java for JSD | Unlikely for MC sampling; even if so, serialization cost should dominate Approach B |
| Mean-based pruning ineffective when all distributions have similar means | This can happen if data generation produces clustered means; verify on calibration step |
| Pruning statistics vary across runs (MC randomness) | Report median pruning rates across 30 runs |

---

## 12. Connection to Paper Narrative

### In the Introduction
Contribution 4 claims "implementation and performance evaluation." This experiment demonstrates that the implementation is not just functional but **benefits from engine-level optimizations** that would be impossible in a post-processing approach.

### In Related Work
The comparison with Orion/MCDB becomes stronger: neither system supports distributional comparison operators, and neither can perform pruning-based similarity joins. The GeoSPARQL analogy also holds: just as spatial indexes enable efficient spatial joins, distribution-aware pruning enables efficient similarity joins.

### In the Evaluation Section
Present the three approaches as a **progression**:
1. External (baseline) — what users do today
2. Naive in-engine — what basic ProbSPARQL provides
3. Optimized in-engine — what a dedicated operator provides

This progression tells a compelling story about the value of deep engine integration.
