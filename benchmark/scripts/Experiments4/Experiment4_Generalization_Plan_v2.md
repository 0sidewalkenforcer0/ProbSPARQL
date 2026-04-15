# Experiment 4: Generalization to Other Distribution Families (v2)

## 1. Research Question

Does the ProbSPARQL framework generalize to distribution families beyond Gaussian Mixture Models?

### Sub-questions

- **RQ4.1:** Can a single polymorphic function (`prob:cdf`, `prob:jsd`, etc.) dispatch correctly across GMM, Histogram, and Dirichlet literals?
- **RQ4.2:** What is the performance profile of each distribution type for core operations?
- **RQ4.3:** Does the sample-based JSD fallback enable cross-type comparison?
- **RQ4.4:** How does representation resolution (GMM components K, histogram bin count N, Dirichlet dimension d) affect accuracy and speed?

---

## 2. Three Distribution Types

| Type | Domain | Nature | Typical Use Case | Literal Datatype |
|------|--------|--------|-----------------|-----------------|
| **GMM** | ℝᵈ | Parametric, continuous, multi-modal | Complex sensor noise, multi-modal measurements | `uq:gmmLiteral` |
| **Histogram** | ℝ | Non-parametric, discrete, piecewise-constant | Binned measurements, empirical distributions | `uq:histogramLiteral` |
| **Dirichlet** | Simplex Δₖ | Parametric, continuous, over probability vectors | Compositional uncertainty, material composition, defect-type proportions | `uq:dirichletLiteral` |

### Why These Three?

They span three fundamentally different representation paradigms:

```
                    Parametric          Non-parametric
                  ┌─────────────┐     ┌──────────────┐
  Continuous      │  GMM         │     │  (KDE)       │
  on ℝᵈ          │  Dirichlet   │     │              │
                  └─────────────┘     └──────────────┘
  Discrete /      │              │     ┌──────────────┐
  Piecewise       │              │     │  Histogram    │
                  └─────────────┘     └──────────────┘
```

Additionally:
- **GMM** on ℝᵈ: the primary type, already fully evaluated in Exp 1–3
- **Histogram** on ℝ: non-parametric alternative with exact JSD (O(N))
- **Dirichlet** on Δₖ: different domain entirely (simplex, not Euclidean), showing the framework handles non-Euclidean distributions

---

## 3. Polymorphic Function Dispatch

### 3.1 Core Design Principle

**The user writes ONE function. The engine dispatches by datatype at runtime.**

```sparql
# This query works on GMM, Histogram, OR Dirichlet literals
# without any change
BIND(prob:cdf(?distribution, 9.8) AS ?p)
BIND(prob:jsd(?dist1, ?dist2) AS ?divergence)
BIND(prob:mean(?distribution) AS ?mu)
```

### 3.2 Dispatch Logic

```java
public class JSDFunction extends FunctionBase2 {
    @Override
    public NodeValue exec(NodeValue d1, NodeValue d2) {
        String type1 = d1.asNode().getLiteralDatatypeURI();
        String type2 = d2.asNode().getLiteralDatatypeURI();

        // Same type: use optimized type-specific implementation
        if (type1.equals(type2)) {
            if (type1.equals(GMMDatatype.URI))
                return gmmJSD((GMMValue) d1, (GMMValue) d2);
            if (type1.equals(HistogramDatatype.URI))
                return histJSD((HistValue) d1, (HistValue) d2);
            if (type1.equals(DirichletDatatype.URI))
                return dirichletJSD((DirichletValue) d1, (DirichletValue) d2);
        }

        // Different types: sample-based fallback (Option C)
        return sampleBasedJSD(d1, d2);
    }
}
```

### 3.3 Same-Type JSD: Optimized Paths

| Type Pair | Algorithm | Complexity |
|-----------|-----------|-----------|
| GMM ↔ GMM | MC sampling from mixture (GT_10K) | O(N × K) |
| Hist ↔ Hist | Exact discrete KL summation | O(N) |
| Dir ↔ Dir | MC sampling from Dirichlet | O(N × d) |

### 3.4 Cross-Type JSD: Sample-Based Fallback

When two distributions have different types, there is no analytical shortcut. The engine uses a universal sample-based approach:

```java
private NodeValue sampleBasedJSD(NodeValue d1, NodeValue d2) {
    // 1. Sample N points from each distribution
    double[][] samples1 = sampleFrom(d1, N_SAMPLES / 2);
    double[][] samples2 = sampleFrom(d2, N_SAMPLES / 2);
    double[][] allSamples = concat(samples1, samples2);

    // 2. Estimate log-densities at all sample points
    double[] logP = estimateLogDensity(d1, allSamples);
    double[] logQ = estimateLogDensity(d2, allSamples);

    // 3. Compute JSD from log-density ratios
    double[] logM = logMixture(logP, logQ);
    double jsd = 0.5 * mean(subtract(logP, logM))
               + 0.5 * mean(subtract(logQ, logM));
    return NodeValue.makeDouble(Math.max(0.0, jsd));
}
```

Each distribution type must implement two methods:
- `sample(n)`: draw n random samples
- `logPdf(x[])`: evaluate log-density at given points

| Type | `sample(n)` | `logPdf(x)` |
|------|-----------|------------|
| GMM | Component selection + Gaussian sampling | Log-sum-exp over K components |
| Histogram | Multinomial over bins + uniform within bin | Log of bin density |
| Dirichlet | Gamma sampling + normalization | Log of Dirichlet PDF |

### 3.5 Polymorphic Function Table

| Function | GMM | Histogram | Dirichlet | Cross-type |
|----------|-----|-----------|-----------|-----------|
| `prob:cdf(dist, x)` | Weighted Gaussian CDF | Cumulative bin sum | Regularized incomplete Beta | N/A (single dist) |
| `prob:mean(dist)` | Weighted component means | Weighted bin centers | α_i / Σα | N/A |
| `prob:std(dist)` | From moments | From bin centers | From Dirichlet variance formula | N/A |
| `prob:map(dist)` | Mode of dominant component | Highest bin center | (α_i - 1) / (Σα - d) | N/A |
| `prob:modeCount(dist)` | Count of significant components | Count of local maxima | 1 (unimodal for α_i > 1) | N/A |
| `prob:jsd(d1, d2)` | MC sampling | Exact discrete | MC sampling | **Sample-based fallback** |
| `prob:pdf(dist, x)` | GMM density | Bin density | Dirichlet density | N/A |

---

## 4. Dirichlet Distribution Details

### 4.1 Definition

A Dirichlet distribution Dir(α₁, ..., αₖ) is a probability distribution over the (k-1)-simplex Δₖ = {x ∈ ℝᵏ : x_i ≥ 0, Σx_i = 1}.

Density: p(x | α) = (1/B(α)) × Π_{i=1}^{k} x_i^{α_i - 1}

where B(α) = Π Γ(α_i) / Γ(Σα_i)

### 4.2 Literal Format

```json
{ "alphas": [2.5, 1.0, 3.0, 0.5] }
```

### 4.3 Semantic Operations

| Operation | Formula | Complexity |
|-----------|---------|-----------|
| Mean | E[X_i] = α_i / α₀ where α₀ = Σα_i | O(k) |
| Std | Var[X_i] = α_i(α₀-α_i) / (α₀²(α₀+1)) | O(k) |
| MAP | mode_i = (α_i - 1) / (α₀ - k) for α_i > 1 | O(k) |
| CDF | Via regularized incomplete Beta (marginal) | O(1) per dimension |
| PDF | Direct from density formula | O(k) |
| Sampling | Draw g_i ~ Gamma(α_i, 1), normalize x_i = g_i / Σg_j | O(k) |
| JSD (Dir↔Dir) | MC sampling: draw from Dir, evaluate log-densities | O(N × k) |

### 4.4 Example Use Case

**Material composition uncertainty:** A refurbished component's material composition is measured and expressed as a probability distribution over composition fractions:

```
Component cfm:hasMaterialComposition ?dist .
?dist is Dir(2.5, 1.0, 3.0, 0.5)
meaning: ~36% steel, ~14% aluminum, ~43% copper, ~7% other (with uncertainty)
```

**Query: Find components with unusual material composition**
```sparql
SELECT ?component ?divergence WHERE {
  ?component cfm:hasMaterialComposition ?measured .
  ?component cfm:hasExpectedComposition ?expected .
  BIND(prob:jsd(?measured, ?expected) AS ?divergence)
  FILTER(?divergence > 0.1)
}
```

**Query: Get the expected fraction of steel in a component**
```sparql
SELECT ?component ?steelFraction WHERE {
  ?component cfm:hasMaterialComposition ?dist .
  BIND(prob:mean(?dist) AS ?meanVector)
  # Extract first dimension (steel)
  BIND(prob:marginalMean(?dist, 1) AS ?steelFraction)
}
```

---

## 5. Experimental Design

### Sub-Experiment 4.1: Polymorphic Dispatch Verification

**Question:** Does a single query work unchanged across all three distribution types?

**Method:** Run the **exact same query** on three datasets — one with GMM literals, one with histogram literals, one with Dirichlet literals.

**Query (unchanged across all three):**
```sparql
SELECT ?entity ?meanVal WHERE {
  ?entity cfm:hasDistribution ?dist .
  BIND(prob:mean(?dist) AS ?meanVal)
}
```

```sparql
SELECT ?entity ?jsd WHERE {
  ?entity cfm:hasDistribution1 ?d1 .
  ?entity cfm:hasDistribution2 ?d2 .
  BIND(prob:jsd(?d1, ?d2) AS ?jsd)
  FILTER(?jsd > 0.1)
}
```

**Expected result:** Same query, same syntax, correct results on all three types. This is a **qualitative** demonstration — not a performance benchmark.

**Output:** Table showing query works on each type:

| Query | GMM | Histogram | Dirichlet | Same SPARQL? |
|-------|-----|-----------|-----------|:---:|
| prob:mean | ✓ | ✓ | ✓ | Yes |
| prob:std | ✓ | ✓ | ✓ | Yes |
| prob:cdf | ✓ | ✓ | ✓ | Yes |
| prob:jsd (same type) | ✓ | ✓ | ✓ | Yes |
| prob:jsd (cross type) | ✓ | ✓ | ✓ | Yes |
| prob:map | ✓ | ✓ | ✓ | Yes |

---

### Sub-Experiment 4.2: Per-Type Operation Performance

**Question:** How does the computational cost of core operations vary across distribution types?

**Method:** Measure per-call time for each operation on each distribution type.

**Setup:**
- 1,000 distribution literals of each type
- Each operation called 1,000 times
- Report median per-call time

**Distribution parameters:**
- GMM: K=3, d=1
- Histogram: N ∈ {20, 50, 100}
- Dirichlet: k ∈ {4, 10, 20}

**Output:**

**Table: Per-operation latency (μs per call)**

| Operation | GMM (K=3) | Hist (N=50) | Dir (k=4) | Dir (k=10) |
|-----------|----------|------------|----------|-----------|
| prob:mean | | | | |
| prob:std | | | | |
| prob:cdf | | | | |
| prob:pdf | | | | |
| prob:map | | | | |
| prob:jsd (same-type pair) | | | | |

**Expected pattern:**

| Operation | GMM | Histogram | Dirichlet |
|-----------|-----|-----------|-----------|
| mean, std, map | O(K) ~μs | O(N) ~μs | O(k) ~μs |
| cdf | O(K) ~μs | O(N) ~μs | O(1) ~μs |
| jsd | O(N×K) ~ms | O(N) ~μs | O(N×k) ~ms |

The key finding: **Histogram JSD is orders of magnitude faster** because it's exact discrete computation, while GMM and Dirichlet require MC sampling.

---

### Sub-Experiment 4.3: Cross-Type JSD via Sample-Based Fallback

**Question:** Does the sample-based fallback produce accurate JSD estimates when comparing distributions of different types?

**Method:** Generate distribution pairs where the same underlying distribution is represented in two different formats. Compute JSD within-type (optimized) and cross-type (sample-based), compare.

**Pairs:**

| Pair | Type 1 | Type 2 | How to create |
|------|--------|--------|--------------|
| GMM ↔ Hist | GMM (K=3) | Histogram (N=100) derived from same GMM | Sample 10K from GMM, bin |
| GMM ↔ Dir | GMM on Δₖ | Dirichlet (k=4) | Fit Dirichlet to GMM samples on simplex |
| Hist ↔ Dir | Histogram on Δₖ | Dirichlet (k=4) | Bin Dirichlet samples |

**For each pair:**
1. Compute same-type JSD (optimized): baseline
2. Compute cross-type JSD (sample-based fallback): test
3. Compare: correlation, mean absolute error

**Number of pairs:** 100 per combination (300 total)
**MC samples for fallback:** N = 10,000
**Repetitions:** 10

**Output:**

**Table: Cross-type JSD accuracy**

| Type Pair | Pearson r | Mean |error| | Avg time/pair (ms) |
|-----------|----------|-----------|-------------------|
| GMM ↔ GMM (baseline) | 1.0 | 0 | ~5 ms |
| GMM ↔ Hist (fallback) | | | |
| Hist ↔ Hist (baseline) | 1.0 | 0 | ~0.01 ms |
| Dir ↔ Dir (baseline) | 1.0 | 0 | ~5 ms |
| GMM ↔ Dir (fallback) | | | |
| Hist ↔ Dir (fallback) | | | |

---

### Sub-Experiment 4.4: Query-Level Performance with Histogram Literals

**Question:** What is the end-to-end query overhead when using histogram literals instead of GMMs?

**Method:** Repeat Experiment 1's Q1 (CDF filter) and Q3 (pairwise JSD) with histogram datasets.

**Datasets:**
- A-gmm: from Experiment 1 (K=3)
- A-hist-N50: derived from A-gmm (sample 10K, 50 bins)
- A-hist-N100: derived from A-gmm (sample 10K, 100 bins)

**Scales:** E3 (100 gears), E5 (1000 gears), E7 (10000 gears)

**Queries (identical SPARQL — polymorphic dispatch):**
```sparql
# Q1: CDF filter (same query for both GMM and Histogram)
SELECT ?gear WHERE {
  ?gear a ag:CrownGear ;
        cfm:hasCharacteristic ?char .
  ?measurement cfm:measuresCharacteristic ?char ;
               cfm:hasProbabilisticValue ?rv .
  ?rv uq:hasDistribution ?distribution .
  BIND(prob:cdf(?distribution, 9.8) AS ?probability)
  FILTER(?probability >= 0.9)
}

# Q3: Pairwise JSD (same query for both)
SELECT ?gear ?jsd WHERE {
  ...
  BIND(prob:jsd(?gmm1, ?gmm2) AS ?jsd)
  FILTER(?jsd > 0.2)
}
```

**Protocol:**
- Warmup: 3 runs
- Measurement: 10 runs
- Report: median

**Output:**

**Table: End-to-end query performance**

| Query | Scale | GMM K=3 (ms) | Hist N=50 (ms) | Hist N=100 (ms) | Hist speedup |
|-------|-------|-------------|---------------|----------------|-------------|
| Q1 CDF | E3 | | | | |
| Q1 CDF | E5 | | | | |
| Q1 CDF | E7 | | | | |
| Q3 JSD | E3 | | | | |
| Q3 JSD | E5 | | | | |
| Q3 JSD | E7 | | | | |

**Expected:** Q3 (JSD) should be **dramatically faster** with histograms because histogram JSD is exact O(N) over bin weights, while GMM JSD is O(N×K) Monte Carlo. Q1 (CDF) should be similar or slightly faster.

---

### Sub-Experiment 4.5: Dirichlet Standalone Demonstration

**Question:** Can ProbSPARQL handle Dirichlet distributions for a realistic query pattern?

**Method:** Create a small dedicated dataset with Dirichlet-encoded material compositions and run representative queries.

**Dataset:** 100 components, each with:
- `cfm:hasMeasuredComposition`: Dir(α_measured) — measured composition
- `cfm:hasExpectedComposition`: Dir(α_expected) — expected composition

**Queries:**

Q-dir-1: Find anomalous compositions
```sparql
SELECT ?component ?divergence WHERE {
  ?component cfm:hasMeasuredComposition ?measured .
  ?component cfm:hasExpectedComposition ?expected .
  BIND(prob:jsd(?measured, ?expected) AS ?divergence)
  FILTER(?divergence > 0.05)
}
```

Q-dir-2: Get mean composition vector
```sparql
SELECT ?component ?meanComposition WHERE {
  ?component cfm:hasMeasuredComposition ?dist .
  BIND(prob:mean(?dist) AS ?meanComposition)
}
```

**Purpose:** Qualitative demonstration only. Shows the query pattern works. Not a performance benchmark.

**Output:** Confirm queries execute and return plausible results.

---

## 6. Datasets

### 6.1 Histogram Datasets (derived from GMM)

| Source | Target | Bin Counts | Purpose |
|--------|--------|-----------|---------|
| Exp1 A-gmm (E3, E5, E7) | A-hist | 50, 100 | Exp 4.4 |
| Exp3 controlled pairs (200) | pairs-hist | 50, 100 | Exp 4.3 cross-type |

Generation: sample 10K from each GMM, then serialize as explicit `bins` + `weights`.

### 6.2 Dirichlet Dataset (new)

| Dataset | Size | Parameters | Purpose |
|---------|------|-----------|---------|
| dir-compositions | 100 components | k=4, α ~ Uniform[0.5, 5.0] | Exp 4.5 |
| dir-pairs | 100 pairs | Controlled JSD | Exp 4.3 cross-type |

### 6.3 Micro-benchmark Dataset

| Type | Count | Parameters | Purpose |
|------|-------|-----------|---------|
| GMM literals | 1,000 | K=3, d=1 | Exp 4.2 per-operation speed |
| Histogram literals | 1,000 × 3 | N=20, 50, 100 | Exp 4.2 |
| Dirichlet literals | 1,000 × 3 | k=4, 10, 20 | Exp 4.2 |

---

## 7. Protocol Summary

| Sub-Exp | Type | Configs | Warmup | Runs | Purpose |
|---------|------|---------|--------|------|---------|
| 4.1 | Qualitative | 3 types × 6 functions | — | 1 | Polymorphic dispatch works |
| 4.2 | Micro-benchmark | 3 types × 6 ops × param variants | 3 | 10 | Per-operation speed |
| 4.3 | Accuracy | 3 type-pairs × 100 pairs × 10 reps | 3 | 10 | Cross-type JSD accuracy |
| 4.4 | End-to-end | 2 queries × 3 scales × 3 types | 3 | 10 | Query-level performance |
| 4.5 | Qualitative | 2 queries × 1 dataset | — | 1 | Dirichlet demonstration |

---

## 8. Expected Key Findings

### 8.1 Polymorphic Dispatch Works

The same `prob:cdf`, `prob:jsd`, `prob:mean` query runs on all three distribution types without modification. This is the clearest demonstration of the "distribution-agnostic" claim.

### 8.2 Computational Cost Spectrum

| Distribution | JSD cost | CDF cost | Parsing cost |
|-------------|---------|---------|-------------|
| Histogram N=100 | ~10 μs (exact) | ~1 μs | ~50 μs |
| GMM K=3 | ~5 ms (MC) | ~10 μs | ~100 μs |
| Dirichlet k=4 | ~5 ms (MC) | ~5 μs | ~30 μs |

Histogram JSD is ~500× faster than GMM/Dirichlet JSD. This is the headline performance finding.

### 8.3 Cross-Type JSD Is Feasible

The sample-based fallback produces JSD estimates with Pearson r > 0.95 compared to same-type baselines. It's slower than optimized same-type paths but enables queries that would otherwise be impossible.

### 8.4 Q3 (Pairwise JSD) Is Dramatically Faster with Histograms

At E7 (10K gears):
- GMM Q3: ~25,000 ms (from Exp 1)
- Histogram Q3: ~50 ms (expected, due to exact O(N) JSD)
- **~500× speedup** for the most expensive query

---

## 9. Output for Paper

### Tables (fit in ~1.5 pages)

**Table 1: Polymorphic dispatch verification**

| Function | GMM | Histogram | Dirichlet | Same query? |
|----------|:---:|:---------:|:---------:|:---:|
| prob:mean | ✓ | ✓ | ✓ | ✓ |
| prob:jsd | ✓ | ✓ | ✓ | ✓ |
| prob:cdf | ✓ | ✓ | ✓ | ✓ |
| ... | ... | ... | ... | ... |

**Table 2: JSD computation performance per pair**

| Type pair | Method | Time/pair | Speedup |
|-----------|--------|----------|---------|
| GMM ↔ GMM | MC (GT_10K) | ~5 ms | 1× |
| Hist ↔ Hist | Exact discrete | ~10 μs | ~500× |
| Dir ↔ Dir | MC | ~5 ms | ~1× |
| GMM ↔ Hist | Sample-based fallback | ~8 ms | — |
| GMM ↔ Dir | Sample-based fallback | ~10 ms | — |

**Table 3: End-to-end Q3 (pairwise JSD) at E7**

| Literal type | Time (ms) | vs GMM |
|-------------|----------|--------|
| GMM K=3 | ~25,000 | 1× |
| Hist N=50 | ~50 | ~500× |
| Hist N=100 | ~100 | ~250× |

### Charts

**Chart 1: Per-operation latency comparison (grouped bar)**
- X: operation (mean, std, cdf, jsd, map)
- Grouped bars: GMM, Hist, Dirichlet
- Y: time (μs, log scale)
- Key visual: JSD bar for Histogram is dramatically shorter

---

## 10. Paper Narrative

### Key claims:

> **Claim 1 (Polymorphic dispatch):** ProbSPARQL functions are type-polymorphic: `prob:cdf`, `prob:jsd`, `prob:mean`, and all other operators dispatch to type-specific implementations at runtime based on the RDF datatype of the distribution literal. Queries are written once and work unchanged across distribution families.

> **Claim 2 (Three distribution paradigms):** We demonstrate the framework with three distribution types spanning different representation paradigms: Gaussian Mixture Models (parametric, continuous, on ℝᵈ), histograms (non-parametric, discrete), and Dirichlet distributions (parametric, continuous, on the simplex Δₖ).

> **Claim 3 (Cross-type interoperability):** When distributions of different types are compared, the engine falls back to a universal sample-based JSD estimator, enabling queries over heterogeneous knowledge graphs where different sensors report uncertainty in different formats.

> **Claim 4 (Performance adaptation):** The framework automatically exploits type-specific optimizations: histogram JSD is computed exactly in O(N) via discrete summation over bin weights, while GMM and Dirichlet JSD use Monte Carlo sampling in O(N×K). This yields up to 500× speedup for pairwise comparison queries on histogram data.

---

## 11. Implementation TODO

### P0: Polymorphic Dispatch (must do before any experiment)

| # | Task | Effort |
|---|------|--------|
| 1 | Refactor `prob:cdf` → polymorphic dispatch by datatype URI | Medium |
| 2 | Refactor `prob:jsd` → polymorphic dispatch + sample-based fallback | Medium |
| 3 | Refactor `prob:mean`, `prob:std`, `prob:map`, `prob:pdf`, `prob:modeCount` | Medium |
| 4 | Add `Sampleable` interface: `sample(n)` + `logPdf(x[])` for all types | Medium |
| 5 | Implement sample-based JSD fallback using `Sampleable` | Medium |
| 6 | Remove type-specific public functions (`prob:histcdf` etc.) | Low |
| 7 | Keep type-specific implementations as internal methods | — |

### P1: Dirichlet Support

| # | Task | Effort |
|---|------|--------|
| 8 | Implement `DirichletDatatype` + `DirichletValue` | Medium |
| 9 | — JSON literal parsing (validate: `alphas` array) | |
| 10 | — `sample(n)`: Gamma sampling + normalization | |
| 11 | — `logPdf(x)`: Dirichlet log-density | |
| 12 | — `mean()`: α_i / α₀ | Low |
| 13 | — `std()`: Dirichlet variance formula | Low |
| 14 | — `map()`: (α_i - 1) / (α₀ - k) | Low |
| 15 | — `cdf(x, dim)`: regularized incomplete Beta (marginal) | Medium |
| 16 | Register in `TypeMapper` and `FunctionRegistry` | Low |

### P2: Datasets + Benchmarks

| # | Task | Effort |
|---|------|--------|
| 17 | Generate histogram datasets from Exp1 GMMs | `generate_histogram_datasets.py` | Low |
| 18 | Generate Dirichlet composition dataset | `generate_dirichlet_dataset.py` | Low |
| 19 | Generate cross-type pairs | `generate_crosstype_pairs.py` | Medium |
| 20 | Exp 4.1 verification script | `Exp4DispatchTest.java` | Low |
| 21 | Exp 4.2 micro-benchmark | `Exp4MicroBenchmark.java` | Medium |
| 22 | Exp 4.3 cross-type JSD benchmark | `Exp4CrossTypeJSD.java` | Medium |
| 23 | Exp 4.4 end-to-end queries | `Exp4EndToEnd.java` | Low |
| 24 | Exp 4.5 Dirichlet demo queries | `Exp4DirichletDemo.java` | Low |
| 25 | Analysis + visualization | `analyze_exp4.py` | Medium |

---

## 12. Potential Pitfalls

| Risk | Mitigation |
|------|-----------|
| Dirichlet CDF is complex (multivariate on simplex) | Implement marginal CDF only (project to single dimension, use regularized Beta) |
| Cross-type JSD has high variance | Use N=10,000 samples; report variance across repetitions |
| Histogram ↔ Dirichlet comparison is semantically questionable (different domains) | Only compare within compatible domains; document this limitation |
| Dirichlet sampling is slow for large k | k=4 and k=10 are sufficient for demonstration |
| Polymorphic dispatch adds per-call overhead (type checking) | Measure overhead; expect <1μs per dispatch — negligible |
| `prob:cdf` semantics differ for Dirichlet (marginal vs joint) | Define `prob:cdf(dist, x)` as marginal CDF of first dimension; document |

---

## 13. Scope Control

**For the paper (~1.5 pages):**
- Exp 4.1 (dispatch verification): 1 small table
- Exp 4.2 (per-operation speed): 1 table
- Exp 4.4 (Q3 histogram speedup): 1 table row added to Exp 1's results
- Exp 4.3 + 4.5: mentioned in 1–2 sentences

**Keep the focus tight:** The goal is to prove generalization works and polymorphic dispatch is real, not to exhaustively benchmark three distribution types. GMM remains the primary type; histogram and Dirichlet are supporting evidence.
