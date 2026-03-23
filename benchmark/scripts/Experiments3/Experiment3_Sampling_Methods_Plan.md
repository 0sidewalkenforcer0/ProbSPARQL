# Experiment 3: Similarity Join Sampling Methods

## 1. Research Question

How do different sampling strategies for estimating Jensen-Shannon divergence compare in terms of accuracy, convergence speed, and runtime when used inside the SIMILARITYJOIN operator?

### Sub-questions

- **RQ3.1 (Accuracy):** Which method most reliably classifies distribution pairs as similar/dissimilar across varying difficulty levels?
- **RQ3.2 (Convergence):** How many samples does each method need to converge to the ground-truth JSD?
- **RQ3.3 (Selectivity):** How does system behavior change when the similarity threshold θ varies?

---

## 2. Context: Why This Experiment Matters

Experiment 2 used a fixed JSD estimation method (GT_10K naive MC in Approach A, DPI+GT_10K in Approach C). But the SIMILARITYJOIN operator supports multiple JSD estimation strategies. This experiment evaluates them head-to-head to answer: **which method should be the default?**

The choice of sampling method affects:
- **Accuracy:** Wrong JSD estimates → wrong join results (false positives/negatives)
- **Speed:** Fewer samples → faster per-pair computation → faster joins
- **Robustness:** Some methods may fail on "hard" pairs near the decision boundary

---

## 3. Five Sampling Methods

| ID | Name | Algorithm | Key Property |
|----|------|-----------|-------------|
| V1_MC | Naive Monte Carlo | Draw N fixed samples from mixture m=(p+q)/2, estimate JSD via log-density ratios | Simple baseline; accuracy scales as O(1/√N) |
| V2_STRATIFIED | Stratified Sampling | Partition sample space into strata, draw proportionally from each | Reduces variance vs naive MC for same N |
| V3_SPRT | Sequential Probability Ratio Test | Draw samples sequentially; stop early when statistical confidence in H₀: JSD ≤ θ or H₁: JSD > θ is reached | Adaptive sample count; fast for "easy" pairs |
| V4_BOUNDS | Analytic Bounds | Compute upper/lower bounds on JSD without sampling (e.g., via moment matching, Pinsker inequality) | Zero sampling cost; may be inconclusive for borderline pairs |
| V5_ADAPTIVE | Adaptive Cascade | L1(dim) → L2(mean) → L3(var) → L4(bounds) → L5(V3_SPRT fallback) | Combines cheap checks with adaptive sampling; current default |

### Method Relationships

```
V4_BOUNDS ← cheapest, but may be inconclusive
V1_MC ← simplest, fixed cost
V2_STRATIFIED ← refinement of V1, same cost, lower variance
V3_SPRT ← adaptive cost, early termination
V5_ADAPTIVE ← cascading: V4 first, then V3 as fallback
```

---

## 4. Sub-Experiment 3.1: Accuracy across Difficulties

### 4.1 Question

Which method most reliably produces the correct binary classification (similar vs dissimilar) for distribution pairs of varying difficulty?

### 4.2 Dataset: Controlled Distribution Pairs

Generate pairs of GMMs with **known ground-truth JSD**, grouped by difficulty relative to threshold θ = 0.3.

| Difficulty | JSD Range | Distance to θ | # Pairs | Why |
|-----------|-----------|---------------|---------|-----|
| Easy-similar | [0.0, 0.1) | JSD ≪ θ | 200 | Clearly below threshold |
| Easy-dissimilar | (0.5, 0.693] | JSD ≫ θ | 200 | Clearly above threshold |
| Medium | [0.15, 0.25) ∪ (0.35, 0.45] | Moderate distance | 200 | Somewhat near boundary |
| Hard | [0.25, 0.35] | JSD ≈ θ | 200 | Right at the decision boundary |

**Total: 800 pairs**

**Ground truth computation:** For each pair, compute JSD using GT_100K (100,000 samples MC) and classify:
- Similar: JSD ≤ 0.3
- Dissimilar: JSD > 0.3

**GMM parameters:**
- d = 1, K = 3 (consistent with Exp 1 and Exp 2)
- Generation: create base GMM, perturb means/variances to produce partner with target JSD
- Accept/reject sampling until pair falls in target JSD range

**Format:** TTL files loaded into Jena (`simjoin_easy.ttl`, `simjoin_medium.ttl`, `simjoin_hard.ttl`)

### 4.3 Protocol

For each (method × difficulty):

1. Load dataset into Jena
2. Run SIMILARITYJOIN with the specific method (via `-Dprobsparql.mode=V1_MC` etc.)
3. For each pair: method produces binary classification (similar/dissimilar)
4. Compare against ground truth
5. **Repeat 10 times** to capture variance from MC randomness

Fixed parameters:
- θ = 0.3
- Sample size N = 10,000 (for V1_MC, V2_STRATIFIED; V3_SPRT and V5_ADAPTIVE determine N adaptively)

### 4.4 Metrics

| Metric | Definition |
|--------|-----------|
| Accuracy (%) | (True positives + True negatives) / Total pairs × 100 |
| Precision | TP / (TP + FP) |
| Recall | TP / (TP + FN) |
| F1 | 2 × Precision × Recall / (Precision + Recall) |
| Avg time per pair (ms) | Total query time / number of pairs |

All metrics reported as mean ± std across 10 repetitions.

### 4.5 Expected Output

**Table: Accuracy by difficulty and method**

| Difficulty | V1_MC | V2_STRAT | V3_SPRT | V4_BOUNDS | V5_ADAPTIVE |
|-----------|-------|----------|---------|-----------|-------------|
| Easy-sim | ~100% | ~100% | ~100% | ~100% | ~100% |
| Easy-dis | ~100% | ~100% | ~100% | ~100% | ~100% |
| Medium | ~95% | ~96% | ~97% | ~85%? | ~97% |
| Hard | ~80% | ~83% | ~88% | ~60%? | ~90% |

All methods should score ~100% on easy pairs. Differences emerge on medium and especially hard pairs. V4_BOUNDS may struggle because analytic bounds are loose near the boundary. V3_SPRT and V5_ADAPTIVE should perform best because they adapt their effort to pair difficulty.

**Chart: Grouped bar chart**
- X: difficulty level
- Grouped bars: one per method
- Y: accuracy (%)
- Error bars: ±1 std from 10 repetitions

**Chart: Per-pair latency by difficulty**
- X: difficulty level
- Grouped bars: one per method
- Y: avg time per pair (ms)
- Shows the speed-accuracy tradeoff

---

## 5. Sub-Experiment 3.2: Convergence Analysis

### 5.1 Question

How quickly does each method converge to the ground-truth JSD estimate as sample size increases?

### 5.2 Dataset: One Fixed "Hard" Pair

Select one pair from the "hard" set where JSD ≈ 0.3 (e.g., true JSD = 0.298).

This pair is the worst case: right at the decision boundary, where convergence speed matters most.

### 5.3 Protocol

For each (method × sample_size):

1. Run the method on the fixed pair
2. Record: estimated JSD value, computation time
3. **Repeat 50 times** to capture variance

Sample sizes: **100, 500, 1,000, 5,000, 10,000, 50,000, 100,000**

**Note on adaptive methods:** V3_SPRT and V5_ADAPTIVE determine their own sample count. For these methods:
- V3_SPRT: set the maximum allowed samples to each N value, observe when it stops early
- V5_ADAPTIVE: similarly, cap the fallback MC at each N value
- V4_BOUNDS: does not use sampling — report its fixed estimate at all N values (horizontal line)

### 5.4 Metrics

| Metric | Definition |
|--------|-----------|
| Estimated JSD | Mean of 50 runs |
| Absolute error | |Estimated JSD − True JSD| |
| Std | Standard deviation across 50 runs |
| Time (ms) | Mean computation time per run |

### 5.5 Expected Output

**Chart 1: Convergence of JSD estimate**
- X: sample size (log scale)
- Y: estimated JSD
- One line per method (with ±1 std shaded region)
- Horizontal dashed line: ground truth JSD
- Expected: all methods converge to ground truth; V2_STRATIFIED converges faster (lower variance); V3_SPRT shows a step-function pattern (stops early at different N)

**Chart 2: Absolute error vs sample size**
- X: sample size (log scale)
- Y: |estimated − true| (log scale)
- One line per method
- Expected: V1_MC error ∝ 1/√N; V2_STRATIFIED has lower constant; V3_SPRT/V5_ADAPTIVE reach target accuracy at lower N

**Chart 3: Time vs sample size**
- X: sample size (log scale)
- Y: computation time (ms)
- One line per method
- Expected: V1/V2 linear in N; V3 sub-linear (early termination); V4 constant (no sampling); V5 between V3 and V4

**Table: Sample size needed for |error| < 0.01**

| Method | N required for error < 0.01 | Time at that N |
|--------|---------------------------|----------------|
| V1_MC | ~50,000? | |
| V2_STRATIFIED | ~20,000? | |
| V3_SPRT | ~5,000? (auto-determined) | |
| V4_BOUNDS | N/A (analytic) | |
| V5_ADAPTIVE | ~3,000? (auto-determined) | |

---

## 6. Sub-Experiment 3.3: Selectivity Sensitivity

### 6.1 Question

How does system behavior change when the similarity threshold θ varies?

### 6.2 Dataset

Reuse the **800 pairs** from Experiment 3.1 (all difficulties combined).

### 6.3 Protocol

For each (method × θ):

1. Run SIMILARITYJOIN on all 800 pairs with the given θ
2. Record: total time, result count, per-pair classification
3. Compute accuracy against ground truth (recomputed for each θ)
4. **Repeat 10 times**

θ values: **0.01, 0.05, 0.1, 0.2, 0.3, 0.5**

Fixed sample size: N = 10,000 (for V1, V2; adaptive for V3, V5)

### 6.4 Metrics

| Metric | Definition |
|--------|-----------|
| Execution time (ms) | Median of 10 runs |
| Result count | Pairs classified as similar (JSD ≤ θ) |
| Accuracy (%) | Agreement with ground truth at each θ |
| F1 score | Harmonic mean of precision and recall |

### 6.5 Expected Output

**Chart 1: Execution time vs θ**
- X: θ
- Y: time (ms)
- One line per method
- Expected: V1/V2 constant (always compute full JSD regardless of θ); V3 faster at extreme θ (easy decisions → early termination); V4 fastest but potentially less accurate; V5 adapts

**Chart 2: Accuracy vs θ**
- X: θ
- Y: accuracy (%)
- One line per method
- Expected: all methods near 100% at θ=0.01 (everything is dissimilar) and θ=0.5 (almost everything is similar); accuracy dips at θ=0.2–0.3 where the data has many borderline pairs

**Chart 3: Result count vs θ**
- X: θ
- Y: number of pairs returned
- One line per method, plus ground-truth line
- Expected: all methods track ground truth closely; V4_BOUNDS may diverge at intermediate θ

**Table: Method comparison at θ = 0.3 (hardest point)**

| Method | Time (ms) | Accuracy | F1 | Result Count |
|--------|----------|---------|-----|-------------|
| V1_MC | | | | |
| V2_STRAT | | | | |
| V3_SPRT | | | | |
| V4_BOUNDS | | | | |
| V5_ADAPTIVE | | | | |

---

## 7. Unified Dataset Generation

### 7.1 Pair Generation Script

```
generate_sim_join_data.py
  --n 200              # pairs per difficulty
  --theta 0.3          # reference threshold
  --K 3                # GMM components
  --d 1                # dimensionality
  --gt-samples 100000  # ground truth MC samples
  --output-dir benchmark/data/exp3/
```

Output:
- `simjoin_easy_similar.ttl` (200 pairs, JSD ∈ [0.0, 0.1))
- `simjoin_easy_dissimilar.ttl` (200 pairs, JSD ∈ (0.5, 0.693])
- `simjoin_medium.ttl` (200 pairs, JSD ∈ [0.15, 0.25) ∪ (0.35, 0.45])
- `simjoin_hard.ttl` (200 pairs, JSD ∈ [0.25, 0.35])
- `simjoin_all.ttl` (all 800 pairs merged, for Exp 3.3)
- `ground_truth.csv` (pair_id, gmm1_uri, gmm2_uri, true_jsd, difficulty)

### 7.2 Convergence Pair

Pick one pair from `simjoin_hard.ttl` where true JSD is closest to 0.3. Record its URIs in a config file.

---

## 8. Protocol Summary

| Sub-Exp | Dataset | Methods | Variables | Repetitions | Configs |
|---------|---------|---------|-----------|-------------|---------|
| 3.1 | 800 pairs (4 difficulties) | 5 | 4 difficulties | 10 | 5 × 4 = 20 |
| 3.2 | 1 hard pair | 5 | 7 sample sizes | 50 | 5 × 7 = 35 |
| 3.3 | 800 pairs (all) | 5 | 6 θ values | 10 | 5 × 6 = 30 |

Total configurations: 85
Total runs: 20×10 + 35×50 + 30×10 = 200 + 1,750 + 300 = **2,250 runs**

### Execution Settings

| Parameter | Value |
|-----------|-------|
| Warmup | 3 runs per config |
| Measured runs | As above (10 or 50) |
| Reporting | Mean ± std (accuracy); Median (time) |
| Default N | 10,000 (V1, V2); adaptive (V3, V5); N/A (V4) |
| θ (3.1 default) | 0.3 |
| K | 3 |
| d | 1 |

### JVM Configuration

```bash
# V1: Naive MC
java -Dprobsparql.mode=GT_10K ...

# V2: Stratified
java -Dprobsparql.mode=V2_STRATIFIED ...

# V3: SPRT
java -Dprobsparql.mode=V3_SPRT ...

# V4: Bounds only
java -Dprobsparql.mode=V4_BOUNDS ...

# V5: Adaptive cascade
java -Dprobsparql.mode=V5_ADAPTIVE ...
```

---

## 9. Expected Key Findings

### 9.1 Accuracy Ranking (at Hard difficulty)

```
V5_ADAPTIVE ≥ V3_SPRT > V2_STRATIFIED ≥ V1_MC >> V4_BOUNDS
```

V5 and V3 adapt their effort to pair difficulty. V4 uses only analytic bounds and will be least accurate for borderline pairs.

### 9.2 Speed Ranking (at Hard difficulty)

```
V4_BOUNDS << V5_ADAPTIVE < V3_SPRT < V2_STRATIFIED ≈ V1_MC
```

V4 is fastest (no sampling). V5 and V3 use early termination. V1 and V2 always draw full N samples.

### 9.3 Speed-Accuracy Tradeoff

| Method | Speed | Accuracy | Best Use Case |
|--------|-------|---------|--------------|
| V1_MC | Slow | Good | Baseline reference |
| V2_STRATIFIED | Slow | Better | When accuracy matters and budget is fixed |
| V3_SPRT | Fast | Good | General-purpose adaptive |
| V4_BOUNDS | Fastest | Lowest | Pre-filter only (not standalone) |
| V5_ADAPTIVE | Fast | Best | **Default recommendation** |

### 9.4 Convergence

V3_SPRT and V5_ADAPTIVE should reach |error| < 0.01 at N ≈ 3,000–5,000, while V1_MC needs N ≈ 50,000. This means adaptive methods are **10× more sample-efficient**.

---

## 10. Output for Paper

### Tables

**Table 1 (Exp 3.1): Classification accuracy by difficulty**

| Difficulty | V1_MC | V2_STRAT | V3_SPRT | V4_BOUNDS | V5_ADAPT |
|-----------|-------|----------|---------|-----------|----------|
| Easy | | | | | |
| Medium | | | | | |
| Hard | | | | | |
| **Overall** | | | | | |

**Table 2 (Exp 3.1): Speed-accuracy summary at Hard difficulty**

| Method | Accuracy (%) | Avg time/pair (ms) | Speedup vs V1 |
|--------|-------------|-------------------|---------------|
| V1_MC | | | 1.0× |
| V2_STRAT | | | |
| V3_SPRT | | | |
| V4_BOUNDS | | | |
| V5_ADAPT | | | |

**Table 3 (Exp 3.2): Samples needed for convergence**

| Method | N for |error| < 0.05 | N for |error| < 0.01 | Time at convergence |
|--------|----------------------|----------------------|-------------------|

### Charts

**Chart 1 (Exp 3.1):** Accuracy by difficulty (grouped bar)
**Chart 2 (Exp 3.2):** JSD estimate vs sample size (convergence lines with std bands)
**Chart 3 (Exp 3.2):** Absolute error vs sample size (log-log)
**Chart 4 (Exp 3.3):** Accuracy vs θ (line chart per method)
**Chart 5 (Exp 3.3):** Execution time vs θ (line chart per method)

---

## 11. Paper Narrative

### Three-sentence summary:

> We compare five JSD estimation strategies for the SIMILARITYJOIN operator. Naive Monte Carlo (V1) and stratified sampling (V2) provide reliable accuracy but at fixed computational cost. The adaptive cascade (V5), which combines cheap analytic bounds with sequential testing, achieves comparable accuracy to fixed-sample methods while requiring 10× fewer samples for convergence, making it the recommended default for ProbSPARQL deployments.

### Connection to Experiment 2:

> Experiment 2 demonstrated that the SIMILARITYJOIN operator achieves up to 174× speedup over naive external processing. Experiment 3 refines this result by showing that the choice of JSD estimation strategy within the operator provides an additional dimension of optimization: the adaptive cascade (V5) converges in ~3,000 samples versus ~50,000 for naive MC, further reducing per-pair computation cost without sacrificing classification accuracy.

---

## 12. Implementation TODO

| # | Task | File | Status |
|---|------|------|--------|
| 1 | Generate 800 controlled pairs | `generate_sim_join_data.py` | ⚠️ Update params |
| 2 | Compute ground truth (GT_100K) | Included in generation script | |
| 3 | Select convergence pair | Manual from hard set | |
| 4 | Exp 3.1 benchmark | `ClassificationAccuracyBenchmark.java` | ⚠️ Update REPEAT=10, pairs=200/category |
| 5 | Exp 3.2 benchmark | `MultiMethodConvergenceBenchmark.java` | ✅ Exists (verify params) |
| 6 | Exp 3.3 benchmark | `SelectivityBenchmark.java` | ✅ Exists (verify params) |
| 7 | Ensure all 5 methods switchable via JVM property | `JSDivergenceFunction.java` | ✅ Exists |
| 8 | Analysis: accuracy tables + charts | `analyze_exp3_1.py` | ❌ |
| 9 | Analysis: convergence charts | `analyze_exp3_2.py` | ❌ |
| 10 | Analysis: selectivity charts | `analyze_exp3_3.py` | ❌ |

---

## 13. Potential Pitfalls

| Risk | Mitigation |
|------|-----------|
| V4_BOUNDS produces no usable result (always inconclusive) | Report it honestly; V4 is a pre-filter, not a standalone estimator |
| V3_SPRT never terminates for hard pairs (JSD ≈ θ) | Set maximum sample cap; report the cap-hit rate |
| V2_STRATIFIED shows negligible improvement over V1 | Possible for 1D GMMs where stratification adds little; note this |
| Ground truth (GT_100K) itself has variance | At 100K samples, std of JSD estimate is ~0.001; negligible vs pair difficulty ranges |
| 10 repetitions not enough for stable accuracy estimates | If std > 3%, increase to 20 repetitions |
| Hard pairs too few near exact θ | Generate extra hard pairs in [0.28, 0.32] sub-range |
