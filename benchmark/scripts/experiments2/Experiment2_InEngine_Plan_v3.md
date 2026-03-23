# Experiment 2: Value of In-Engine Processing (v3)

## 1. Research Question

What are the performance benefits of (a) integrating probabilistic operations into the SPARQL query engine, and (b) using a dedicated similarity join operator with pruning?

### Sub-questions

- **RQ2.1:** How much faster is in-engine JSD computation compared to identical external computation? (A vs B — isolates location advantage)
- **RQ2.2:** How much speedup does pair-level pruning achieve when the underlying JSD computation is uniformly expensive? (C vs A — isolates pruning advantage)
- **RQ2.3:** What is the combined advantage of engine integration + pruning? (C vs B — total improvement)

---

## 2. Critical Design Principle: Fair Comparison

**All three approaches MUST use the identical JSD algorithm: naive Monte Carlo with fixed N=10,000 samples.**

| Approach | JSD Algorithm | Why |
|----------|--------------|-----|
| A (BIND+FILTER) | Java GT_10K naive MC | Baseline in-engine cost |
| B (Python external) | Python naive MC, same estimator | Baseline external cost |
| C (SIMILARITYJOIN) | Pruning cascade; L5 fallback = Java GT_10K naive MC | Pruning skips expensive MC |

**Forbidden:** V5_ADAPTIVE, SPRT, or any early-termination method for A or C. These internal optimizations make per-pair JSD too cheap, masking the pruning advantage. Advanced sampling methods are evaluated separately in Experiment 3.

**Forbidden:** Histogram binning or any different estimator for B. Python must implement the exact same mathematical MC estimator as Java.

**Validation:** Before benchmarking, verify that A, B, and C produce the **same result set** (same gear URIs, same pair count, ±5% numerical tolerance on JSD values). If results diverge, the estimators are not equivalent — debug before proceeding.

---

## 3. Three Approaches

### Approach A — In-Engine Naive Filter (BIND + FILTER)

Computes JSD for **every** candidate pair. No pruning, no early termination.

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
  FILTER(str(?rv1) < str(?rv2))
  BIND(prob:jsd(?gmm1, ?gmm2) AS ?jsDivergence)
  FILTER(?jsDivergence > ?threshold)
}
```

**JVM setting:** `-Dprobsparql.mode=GT_10K`

**Characteristics:**
- Evaluates n(n-1)/2 unique pairs (self-pairs and duplicates excluded by `FILTER(str(?rv1) < str(?rv2))`)
- Streaming evaluation: no materialization of intermediate tables
- Every pair triggers a full 10K-sample MC JSD computation
- This is the **slowest** in-engine approach — intentionally, to make pruning savings visible

---

### Approach B — External Post-Processing (Python)

Export all distribution pairs, compute JSD externally using the same MC algorithm.

**Step 1 — SPARQL export query:**
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
  FILTER(str(?rv1) < str(?rv2))
}
```

**Step 2 — Python JSD computation (must match Java GT_10K exactly):**

```python
import numpy as np

def sample_gmm(weights, means, covs, n):
    """Sample n points from a GMM."""
    components = np.random.choice(len(weights), size=n, p=weights)
    samples = np.array([
        np.random.multivariate_normal(means[c], covs[c])
        for c in components
    ])
    return samples

def log_pdf_gmm(weights, means, covs, x):
    """Log-pdf of GMM evaluated at points x."""
    from scipy.stats import multivariate_normal
    K = len(weights)
    log_components = np.array([
        np.log(weights[k]) + multivariate_normal.logpdf(x, means[k], covs[k])
        for k in range(K)
    ])
    return scipy.special.logsumexp(log_components, axis=0)

def jsd_naive_mc(gmm1, gmm2, n_samples=10000):
    """
    Naive MC JSD estimator — MUST match Java GT_10K implementation.
    Samples from mixture m = 0.5*p + 0.5*q.
    """
    half = n_samples // 2
    samples_p = sample_gmm(*gmm1, half)
    samples_q = sample_gmm(*gmm2, n_samples - half)
    samples = np.vstack([samples_p, samples_q])

    log_p = log_pdf_gmm(*gmm1, samples)
    log_q = log_pdf_gmm(*gmm2, samples)
    log_m = np.logaddexp(log_p, log_q) - np.log(2)

    jsd = 0.5 * np.mean(log_p - log_m) + 0.5 * np.mean(log_q - log_m)
    return max(0.0, jsd)
```

**Requirements for Python implementation:**
- Use `numpy` for sampling and linear algebra (this is fair — it's what any user would do)
- Use `scipy.stats.multivariate_normal.logpdf` for density evaluation
- Do NOT use histogram binning, kernel density estimation, or any other shortcut
- Single-threaded execution (no multiprocessing)
- Standard `json` module for GMM literal parsing

**Total B time** = median(export query time) + median(Python compute time)

---

### Approach C — Probabilistic Similarity Join (Pruning + MC Fallback)

Dedicated join operator with cascading pruning. Only pairs that survive all pruning levels trigger the expensive MC JSD.

```sparql
SELECT ?gear
WHERE {
  { ?gear a ag:CrownGear ;
          cfm:hasCharacteristic ?char .
    ?m1 cfm:measuresCharacteristic ?char ;
        cfm:hasProbabilisticValue ?rv1 .
    ?rv1 uq:hasDistribution ?gmm1 . }
  SIMILARITYJOIN(?gmm1, ?gmm2, ?threshold)
  { ?m2 cfm:measuresCharacteristic ?char ;
        cfm:hasProbabilisticValue ?rv2 .
    ?rv2 uq:hasDistribution ?gmm2 . }
}
```

**JVM settings:**
```
-Dprobsparql.simjoin.pruning=true
-Dprobsparql.mode=GT_10K
```

**Pruning cascade:**

| Level | Technique | Cost | Action |
|-------|-----------|------|--------|
| L1 | Dimensionality check | O(1) | Reject if d₁ ≠ d₂ |
| L2 | Mean-distance bound | O(K) | Reject if ‖μ₁ − μ₂‖ > upper_bound(θ); Accept if ‖μ₁ − μ₂‖ < lower_bound(θ) |
| L3 | Variance bound | O(K) | Reject if variance ratio exceeds compatibility range |
| L4 | Analytic JSD bounds | O(K²) | Reject/accept if analytic [lower, upper] interval resolves against θ |
| L5 | **Full GT_10K MC** | O(N×K) | Fallback — same as Approach A's per-pair cost |

**Key insight:** Every pair that is resolved at L1–L4 saves one full GT_10K MC computation. At 10K-sample MC with K=3, this saving is on the order of milliseconds per pair.

**The n² issue:**
SIMILARITYJOIN currently evaluates n × n pairs (including self-pairs and both orderings a→b and b→a). This produces ~2× more pair evaluations than Approach A's n(n-1)/2. However:
- L2 mean-check resolves self-pairs (identical means → JSD=0 → accept/reject instantly)
- Duplicate orderings (a,b) and (b,a) are both resolved at L2 if means differ sufficiently
- The overhead is 2× on **cheap** pruning checks (microseconds), not on **expensive** MC (milliseconds)
- At high pruning rates (>90%), the 2× overhead on cheap checks is negligible vs the 10× saving on MC

Document this 2× factor honestly in the paper. If needed, add deduplication logic inside the SIMILARITYJOIN operator.

---

## 4. What Each Comparison Demonstrates

| Comparison | Controls For | Isolates |
|-----------|-------------|----------|
| **A vs B** | Same JSD algorithm, same pairs, same results | Location advantage: serialization + re-parsing overhead |
| **C vs A** | Both in-engine, same JSD for computed pairs | Pruning advantage: skipping expensive MC for easy pairs |
| **C vs B** | Same final results | Combined advantage: location + pruning |

---

## 5. Datasets

Reuse **Dataset A** from Experiment 1 (already generated with K=3).

### 5.1 Scales

| Config | Source | Approx. Unique Pairs n(n-1)/2 |
|--------|--------|-------------------------------|
| P100 | E3 | ~100 |
| P500 | E4 | ~500 |
| P1K | E5 | ~1,000 |
| P5K | E6 | ~5,000 |
| P10K | E7 | ~10,000 |

**Pre-step:** Run the export query once per dataset. Count actual pair numbers. Record.

### 5.2 Selectivity Calibration

**Must be done empirically** — do NOT hardcode θ values.

For each dataset:
1. Run A with `FILTER(?jsDivergence > 0)` (i.e., return all pairs with their JSD)
2. Collect all JSD values into a list
3. Sort and pick percentile-based thresholds:

| Target Selectivity | θ = percentile of JSD distribution | Meaning |
|-------------------|--------------------------------------|---------|
| 10% (strict) | 90th percentile | Only 10% of pairs have JSD above θ |
| 50% (moderate) | 50th percentile (median) | Half the pairs pass |
| 90% (lenient) | 10th percentile | Most pairs pass |

Store θ values in a config file. Use the same θ values for all three approaches.

### 5.3 Fixed Parameters

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| K | 3 | Moderate GMM complexity |
| d | 1 | Primary dimensionality |
| MC samples N | 10,000 | Consistent across A, B, C |

---

## 6. Protocol

### 6.1 Preparation (run once per dataset)

1. Load dataset into Jena Fuseki
2. Run calibration:
   - Count unique pairs
   - Compute all pairwise JSD values (using GT_10K)
   - Determine θ for 10%, 50%, 90% selectivity
   - Record ground-truth result counts at each θ
3. Save calibration results to `exp2_calibration.csv`

### 6.2 Approach A

For each (dataset × θ):

```bash
java -Dprobsparql.mode=GT_10K -jar probsparql.jar \
     --query queries/exp2_a.sparql --theta $THETA
```

1. Warm-up: 5 runs, discard
2. Measurement: 30 runs
3. Record: per-run time (ms), result count

### 6.3 Approach B

For each (dataset × θ):

**Step 1 — Export:**
1. Warm-up: 5 runs, discard
2. Measurement: 30 runs
3. Record: query time (ms), JSON response size (bytes)

**Step 2 — Python:**
```bash
python3 exp2_external.py \
    --input exported_pairs.json \
    --theta $THETA \
    --n-samples 10000 \
    --runs 30
```
1. Parse GMM JSON strings
2. Compute JSD using naive MC (N=10,000)
3. Filter by θ
4. Record: per-run total time, breakdown (parse / JSD / filter), result count

**Total B** = median(Step 1) + median(Step 2)

### 6.4 Approach C

For each (dataset × θ):

```bash
java -Dprobsparql.simjoin.pruning=true \
     -Dprobsparql.mode=GT_10K \
     -jar probsparql.jar \
     --query queries/exp2_c.sparql --theta $THETA
```

1. Warm-up: 5 runs, discard
2. Measurement: 30 runs
3. Record: per-run time (ms), result count, **pruning statistics**

Pruning statistics (from `Exp2PruningHolder`):

| Field | Description |
|-------|-------------|
| TotalPairs | Total candidate pairs evaluated |
| PrunedDim | Pairs rejected at L1 |
| PrunedMean | Pairs resolved at L2 |
| PrunedVar | Pairs resolved at L3 |
| PrunedBounds | Pairs resolved at L4 |
| FullJSD | Pairs requiring full GT_10K MC at L5 |
| ResultCount | Pairs passing threshold |

### 6.5 Sanity Checks (after all runs, before analysis)

| Check | Criterion | Action if failed |
|-------|-----------|-----------------|
| A result count = C result count | Exact match for all 15 configs | Debug join semantics |
| A result count ≈ B result count | Within ±5% | Debug Python MC estimator |
| A JSD values ≈ B JSD values | Pearson correlation > 0.95 | Debug MC algorithm alignment |
| FullJSD (C) + pruned counts = TotalPairs | Exact | Debug pruning statistics |

---

## 7. Metrics

### Primary

| Metric | Definition |
|--------|-----------|
| Time_A (ms) | Median of 30 Approach A runs |
| Time_B (ms) | Median(export) + Median(Python total) |
| Time_C (ms) | Median of 30 Approach C runs |
| Speedup_location | Time_B / Time_A (location advantage) |
| Speedup_pruning | Time_A / Time_C (pruning advantage) |
| Speedup_total | Time_B / Time_C (combined advantage) |

### Secondary

| Metric | Definition |
|--------|-----------|
| Pruning rate (%) | (TotalPairs − FullJSD) / TotalPairs × 100 |
| L2 contribution (%) | PrunedMean / TotalPairs × 100 |
| L3 contribution (%) | PrunedVar / TotalPairs × 100 |
| L4 contribution (%) | PrunedBounds / TotalPairs × 100 |
| B export size (KB) | JSON response size |
| B breakdown (ms) | Export, parse, JSD compute, filter |

---

## 8. Expected Results

### 8.1 Why This Design Makes C Beat A

With naive GT_10K MC, each JSD computation is expensive (~1–5 ms per pair in Java). Consider E6 (~5000 pairs):

| Approach | Pairs evaluated with full MC | Estimated time |
|----------|----------------------------:|---------------:|
| A | 5,000 (all) | 5,000 × 2ms = **10,000 ms** |
| C (10% selectivity, ~95% pruning) | ~250 (5% of pairs) | 250 × 2ms + 4,750 × 0.01ms = **548 ms** |
| C (50% selectivity, ~50% pruning) | ~2,500 | 2,500 × 2ms + 2,500 × 0.01ms = **5,025 ms** |

Expected speedup C/A: **~2× at 50% selectivity, ~18× at 10% selectivity.**

### 8.2 Why B Is Slowest

Python's MC JSD is slower than Java's due to:
- JSON parsing overhead (re-parsing every GMM string)
- Python interpreter overhead vs JVM JIT compilation
- numpy overhead for small-K GMMs (function call overhead dominates)

Expected: B is ~2–4× slower than A for the same computation, plus export overhead.

### 8.3 Expected Performance Table (50% selectivity)

| Scale | A (ms) | B (ms) | C (ms) | B/A | A/C | B/C |
|------:|-------:|-------:|-------:|----:|----:|----:|
| 100 | ~200 | ~600 | ~120 | 3× | 1.7× | 5× |
| 1K | ~2,000 | ~6,000 | ~1,100 | 3× | 1.8× | 5.5× |
| 5K | ~10,000 | ~30,000 | ~5,000 | 3× | 2× | 6× |
| 10K | ~20,000 | ~60,000 | ~10,000 | 3× | 2× | 6× |

### 8.4 Expected Performance Table (10% selectivity)

| Scale | A (ms) | B (ms) | C (ms) | B/A | A/C | B/C |
|------:|-------:|-------:|-------:|----:|----:|----:|
| 100 | ~200 | ~600 | ~30 | 3× | 7× | 20× |
| 1K | ~2,000 | ~6,000 | ~150 | 3× | 13× | 40× |
| 5K | ~10,000 | ~30,000 | ~600 | 3× | 17× | 50× |
| 10K | ~20,000 | ~60,000 | ~1,200 | 3× | 17× | 50× |

---

## 9. Output for Paper

### 9.1 Tables

**Table 1: Three-way performance comparison (ms)**

| Scale | Sel. | A: In-engine naive | B: External | C: SimJoin pruned | B/A | A/C | B/C |
|------:|-----:|-------------------:|------------:|------------------:|----:|----:|----:|
| ... | 10% | | | | | | |
| ... | 50% | | | | | | |
| ... | 90% | | | | | | |

**Table 2: Pruning cascade breakdown (Approach C)**

| Scale | Sel. | Total | L2 Mean (%) | L3 Var (%) | L4 Bounds (%) | L5 Full MC (%) | Pruning Rate |
|------:|-----:|------:|------------:|-----------:|--------------:|---------------:|-------------:|

**Table 3: External processing breakdown (Approach B)**

| Scale | Export (ms) | Parse (ms) | JSD compute (ms) | Filter (ms) | Total (ms) |
|------:|------------:|-----------:|------------------:|------------:|-----------:|

### 9.2 Charts

**Chart 1 (main result): Execution time vs scale**
- X-axis: number of pairs (log scale)
- Y-axis: execution time (ms, log scale)
- Three lines: A, B, C
- Fixed selectivity: 50%
- Expected: three clearly separated lines, B >> A > C

**Chart 2: Selectivity effect at fixed scale (~5K pairs)**
- X-axis: selectivity (10%, 50%, 90%)
- Three grouped bars per selectivity
- Shows C's advantage grows at low selectivity

**Chart 3: Pruning breakdown (stacked bar)**
- X-axis: selectivity (10%, 50%, 90%)
- Stacked segments: L2, L3, L4, L5
- Fixed scale: ~5K pairs
- Shows which pruning level dominates at each selectivity

**Chart 4: Speedup factors vs scale**
- X-axis: number of pairs
- Two lines: B/C (total speedup), A/C (pruning speedup)
- Fixed selectivity: 10%
- Shows speedup grows with scale

---

## 10. Key Insights for Paper Narrative

### 10.1 Location Advantage (A vs B)

> Even with identical JSD algorithms, in-engine processing is ~3× faster than external post-processing, due to avoiding JSON serialization, HTTP transfer, and re-parsing of GMM literals.

### 10.2 Pruning Advantage (C vs A)

> The SIMILARITYJOIN operator with cascading pruning achieves X× speedup over naive in-engine filtering by resolving Y% of distribution pairs through cheap mean-distance and variance checks alone, avoiding the expensive Monte Carlo JSD estimation for the majority of pairs.

### 10.3 Combined Advantage (C vs B)

> Together, engine integration and algorithmic pruning yield a Z× total speedup over the external post-processing approach at scale, demonstrating that probabilistic operations benefit from being first-class citizens in the query engine.

### 10.4 Why This Requires Engine Integration

The pruning cascade in Approach C is only possible because the engine has **direct access to parsed distribution objects**:
- L2 reads `GMMValue.getOverallMean()` — already in memory as a typed Java object
- L3 reads `GMMValue.getOverallVariance()` — no re-parsing needed
- An external script would need to parse every GMM JSON string before it can even begin to prune, eliminating the pruning benefit for L2 and L3

---

## 11. Implementation TODO

| # | Task | File | Status |
|---|------|------|--------|
| 1 | Calibration script | `exp2_calibrate.py` | ❌ |
| 2 | Approach A benchmark with GT_10K forced | `Exp2BenchmarkA.java` | ⚠️ Modify existing |
| 3 | — Ensure `-Dprobsparql.mode=GT_10K` is respected by `prob:jsd` | `JSDivergenceFunction.java` | Verify |
| 4 | Approach B export query | `queries/exp2_export.sparql` | ❌ |
| 5 | Approach B Python script — matching MC estimator | `exp2_external_mc.py` | ❌ Rewrite |
| 6 | — Implement `sample_gmm()` matching Java | | |
| 7 | — Implement `log_pdf_gmm()` matching Java | | |
| 8 | — Implement `jsd_naive_mc()` matching Java GT_10K | | |
| 9 | — Timing breakdown: parse / JSD / filter | | |
| 10 | Approach C benchmark with pruning + GT_10K fallback | `Exp2BenchmarkC.java` | ⚠️ Modify existing |
| 11 | — Ensure L5 fallback uses GT_10K, not V5_ADAPTIVE | | Verify |
| 12 | — Collect pruning statistics via Exp2PruningHolder | | ✅ Exists |
| 13 | Correctness validation script | `exp2_validate.py` | ❌ |
| 14 | — Compare result counts A vs C (exact) | | |
| 15 | — Compare result counts A vs B (±5%) | | |
| 16 | — Compare JSD values A vs B (correlation > 0.95) | | |
| 17 | Orchestration | `exp2_run.sh` | ❌ |
| 18 | Analysis + visualization | `analyze_exp2.py` | ❌ |

### Implementation Notes

**Verifying Java GT_10K matches Python MC:**
Before the full benchmark, run a mini-test:
1. Pick 10 GMM pairs
2. Compute JSD with Java GT_10K
3. Compute JSD with Python `jsd_naive_mc`
4. Compare: values should agree within ±0.02 (MC variance)
5. If systematic bias exists, debug the estimator

**L4 (BoundsFilterSampler) consideration:**
Previous results showed L4 contributed 0% pruning. Two options:
- **Option A:** Keep L4, accept zero contribution, document honestly
- **Option B:** Investigate why L4 never triggers; tighten bounds if possible
- For paper: report L4=0% if that's the result — it's informative, showing that mean+variance checks handle most cases

---

## 12. Potential Pitfalls

| Risk | Mitigation |
|------|-----------|
| GT_10K makes A very slow, experiment takes too long | Reduce to GT_5K if needed; keep consistent across A/B/C |
| Python MC produces different results than Java MC | Mini-test with 10 pairs first; debug estimator alignment |
| C's n² overhead exceeds pruning savings at small scale | Expected at n<500; focus paper narrative on larger scales |
| L2 pruning bounds too conservative (low pruning rate) | Calibrate bounds empirically on Dataset B ground-truth pairs |
| L2 pruning bounds too aggressive (false accepts/rejects) | Sanity check: A result count must equal C result count |
| JVM GC spikes during GT_10K runs | 30 runs + median reporting absorbs outliers |

---

## 13. Connection to Paper Sections

| Paper Section | Content from This Experiment |
|--------------|------------------------------|
| §1 Introduction, Contribution 4 | "We evaluate performance including the cost of joins between probability distributions" |
| §6.2.2 | Main results: three-way comparison table, charts, pruning analysis |
| §7 Related Work, Orion/MCDB comparison | "Neither Orion nor MCDB supports distribution comparison or similarity-based joining" |
| §7 Related Work, GeoSPARQL analogy | "Just as spatial indexes enable efficient spatial joins, distribution-aware pruning enables efficient similarity joins" |
