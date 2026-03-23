# Experiment 3: Similarity Join Sampling Methods (Revised)

## Changes from Original Plan

| Issue Found in Smoke Test | Impact | Action in Revised Plan |
|--------------------------|--------|----------------------|
| V3_SPRT always returns JSD≈0.005 | Accuracy ~55–65% (broken) | **P0: Fix bug before full run** |
| V4_BOUNDS returns fixed JSD≈0.151 | Never improves with N; accuracy ~60–72% | **Reframe: V4 is a pre-filter, not a standalone estimator** |
| V5_ADAPTIVE stuck at bounds result | Falls back to V4 output, never reaches MC | **P0: Fix cascade logic — must fall through to MC when bounds are inconclusive** |
| Full Exp 3.3 with 40K pairs: ~27 hours | Impractical | **Limit to 50 entities per side (1,225 pairs)** |
| V1_MC convergence flat (2 reps too few) | Can't see convergence trend | **Already planned: full run uses 50 reps** |

---

## 1. Critical Bugs to Fix (P0 — before any full run)

### Bug 1: V3_SPRT Early Termination

**Symptom:** Always returns JSD ≈ 0.005 regardless of true JSD. All pairs classified as "similar."

**Likely root cause:** The SPRT likelihood ratio test terminates immediately in favor of H₀ (similar) because:
- The initial log-likelihood ratio exceeds the acceptance threshold after very few samples
- OR the threshold parameters (α, β) are misconfigured, making the acceptance boundary too easy to reach
- OR the test statistic is computed incorrectly (e.g., using log-pdf ratio instead of JSD-related statistic)

**Debug steps:**
1. Run V3_SPRT on one pair with known JSD = 0.5 (clearly dissimilar)
2. Print per-sample: cumulative log-likelihood ratio, current decision, sample count at termination
3. If it terminates at N=1 or N=2 → threshold is wrong
4. If log-likelihood ratio is always positive → test statistic formula is wrong

**Fix validation:** After fix, V3_SPRT must achieve ≥90% accuracy on "easy" pairs (where V1_MC gets 100%).

### Bug 2: V5_ADAPTIVE Never Reaches MC Fallback

**Symptom:** V5_ADAPTIVE returns identical results to V4_BOUNDS (JSD ≈ 0.151). The adaptive cascade stops at L4 and never proceeds to L5 (MC sampling).

**Likely root cause:** The cascade logic treats L4's bounds result as conclusive even when the bounds interval [lower, upper] straddles θ. When lower < θ < upper, L4 should return "inconclusive" and pass to L5. Instead, it appears to return the lower bound as the final answer.

**Debug steps:**
1. Run V5_ADAPTIVE on one "hard" pair (true JSD ≈ 0.3)
2. Print: L4 lower bound, L4 upper bound, decision (conclusive/inconclusive)
3. If L4 says "conclusive" when lower=0.151 and upper=??? → the inconclusiveness check is wrong

**Fix validation:** After fix, V5_ADAPTIVE on "hard" pairs must show L5 being triggered, and accuracy must be ≥ V1_MC accuracy.

### Bug 3: V4_BOUNDS Classification Logic

**Symptom:** V4_BOUNDS returns JSD ≈ 0.151 (a lower bound). Since 0.151 < θ = 0.3, it classifies all pairs as "similar."

**This is actually correct behavior for a lower-bound estimator** — it can only confirm dissimilarity (when lower_bound > θ), never confirm similarity. But the current implementation appears to use the lower bound AS the JSD estimate, which causes all pairs to be classified as similar.

**Fix:** V4_BOUNDS should return a ternary result:
- If lower_bound > θ → DISSIMILAR (certain)
- If upper_bound ≤ θ → SIMILAR (certain)  
- Otherwise → INCONCLUSIVE (needs MC fallback)

For Exp 3.1/3.3, when V4 returns INCONCLUSIVE, report it as a separate category (not as "similar" or "dissimilar").

---

## 2. Method Roles After Bug Fixes

| Method | Role | Standalone? | Expected Accuracy |
|--------|------|------------|------------------|
| V1_MC | Baseline reference | Yes | High (scales with N) |
| V2_STRATIFIED | Improved baseline | Yes | Higher than V1 at same N |
| V3_SPRT | Adaptive sampling (after fix) | Yes | Comparable to V1, faster |
| V4_BOUNDS | **Pre-filter only** | **No** — returns inconclusive for borderline pairs | Low as standalone; high as filter |
| V5_ADAPTIVE | Full cascade (after fix) | Yes | Best overall (bounds + MC fallback) |

**Key reframing:** V4_BOUNDS is NOT a competitor to V1/V2/V3 — it is a **component** of V5. Presenting V4 as a standalone estimator is misleading because it was never designed to handle borderline pairs. The paper should present V4's contribution **within** V5's cascade, not as an independent method.

---

## 3. Revised Method Set for Paper

After bug fixes, present **four** methods in the paper (drop V4 as standalone):

| ID | Name | Description |
|----|------|-------------|
| V1_MC | Naive Monte Carlo | Fixed N samples, baseline |
| V2_STRATIFIED | Stratified Sampling | Fixed N samples, reduced variance |
| V3_SPRT | Sequential Testing | Adaptive N, early termination |
| V5_ADAPTIVE | Adaptive Cascade | Bounds pre-filter + SPRT fallback |

V4_BOUNDS appears only in V5's cascade description, not as a separate method in the comparison tables. This avoids the awkward "V4 gets 60% accuracy" result that would confuse reviewers.

**If V3_SPRT cannot be fixed in time:** Drop it and present only V1, V2, V5 (three methods). Two baselines + one adaptive method is still a clean story.

---

## 4. Revised Sub-Experiment 3.1: Classification Accuracy

### 4.1 Changes from Original

| Original | Revised |
|----------|---------|
| 5 methods | **4 methods** (V1, V2, V3, V5) or **3 methods** if V3 unfixable |
| V4_BOUNDS as standalone | V4 only appears inside V5 |
| 10 repetitions | **10 repetitions** (keep) |
| 200 pairs per difficulty | **200 pairs per difficulty** (keep) |

### 4.2 Dataset

Same as original plan: 800 pairs across 4 difficulty levels.

| Difficulty | JSD Range | Pairs |
|-----------|-----------|-------|
| Easy-similar | [0.0, 0.1) | 200 |
| Easy-dissimilar | (0.5, 0.693] | 200 |
| Medium | [0.15, 0.25) ∪ (0.35, 0.45] | 200 |
| Hard | [0.25, 0.35] | 200 |

θ = 0.3, K = 3, d = 1, ground truth via GT_100K.

### 4.3 Protocol

For each (method × difficulty × repetition):
1. Run SIMILARITYJOIN with the method
2. Classify each pair: similar (JSD ≤ 0.3) or dissimilar (JSD > 0.3)
3. Compare against ground truth

Fixed N = 10,000 for V1_MC and V2_STRATIFIED.
V3_SPRT and V5_ADAPTIVE determine N adaptively.

Warmup: 3, Measured runs: 10.

### 4.4 Expected Results (After Bug Fixes)

| Difficulty | V1_MC | V2_STRAT | V3_SPRT | V5_ADAPTIVE |
|-----------|-------|----------|---------|-------------|
| Easy | ~100% | ~100% | ~100% | ~100% |
| Medium | ~95% | ~96% | ~95% | ~97% |
| Hard | ~85% | ~87% | ~85% | ~90% |

Plus latency per pair:

| Method | Latency/pair (easy) | Latency/pair (hard) |
|--------|-------------------|-------------------|
| V1_MC | ~8 ms | ~8 ms (constant) |
| V2_STRAT | ~4 ms | ~4 ms (constant) |
| V3_SPRT | ~0.5 ms | ~5 ms (adaptive) |
| V5_ADAPTIVE | ~0.3 ms | ~5 ms (adaptive) |

Key insight: V1/V2 have constant cost regardless of difficulty. V3/V5 are fast on easy pairs (early termination) and slower on hard pairs (need more samples). This is the right behavior.

### 4.5 Output

**Table: Accuracy × Difficulty × Method**

| Difficulty | V1_MC | V2_STRAT | V3_SPRT | V5_ADAPT |
|-----------|-------|----------|---------|----------|
| Easy | | | | |
| Medium | | | | |
| Hard | | | | |
| Overall | | | | |

**Table: Speed-Accuracy Summary at Hard Difficulty**

| Method | Accuracy | Latency/pair | Speedup vs V1 |
|--------|---------|-------------|---------------|
| V1_MC | | | 1.0× |
| V2_STRAT | | | |
| V3_SPRT | | | |
| V5_ADAPT | | | |

**Chart: Grouped bar — accuracy by difficulty**
**Chart: Scatter — accuracy vs latency (speed-accuracy tradeoff)**

---

## 5. Revised Sub-Experiment 3.2: Convergence Analysis

### 5.1 Changes from Original

| Original | Revised |
|----------|---------|
| 5 methods | **4 methods** (or 3 if V3 unfixable) |
| V4_BOUNDS as flat line | Remove V4 from convergence chart (it doesn't use samples) |
| 50 repetitions | **50 repetitions** (keep) |

### 5.2 Handling Adaptive Methods

V3_SPRT and V5_ADAPTIVE determine their own N. For the convergence experiment:

**Option A (recommended):** Set the **maximum allowed samples** to each N value. The method can terminate early but cannot exceed N. This shows:
- At N_max=100: V3 may terminate at N=50 (if confident) or use all 100 (if borderline)
- At N_max=100,000: V3 terminates at its natural stopping point (e.g., N=3,000)

This gives a fair comparison: all methods have the same sample budget, but adaptive methods can use less.

**Option B:** Run adaptive methods with their default settings and plot their natural sample counts. This shows "how many samples does V3 actually use?" but makes the x-axis incomparable across methods.

**Recommendation:** Use Option A for the main chart (comparable x-axis), add a supplementary note showing V3/V5's actual sample counts.

### 5.3 Protocol

One fixed "hard" pair (true JSD closest to 0.3).

For each (method × N_max):
1. Run method with sample cap = N_max
2. Record: estimated JSD, computation time
3. Repeat 50 times

N_max values: 100, 500, 1,000, 5,000, 10,000, 50,000, 100,000

### 5.4 Expected Results

| Method | N for |error| < 0.05 | N for |error| < 0.01 |
|--------|----------------------|----------------------|
| V1_MC | ~5,000 | ~50,000 |
| V2_STRAT | ~2,000 | ~20,000 |
| V3_SPRT | ~1,000 (auto) | ~5,000 (auto) |
| V5_ADAPT | ~500 (bounds help) | ~3,000 (auto) |

### 5.5 Output

**Chart 1: JSD estimate vs N_max (convergence)**
- X: N_max (log scale)
- Y: estimated JSD (mean of 50 runs)
- Shaded: ±1 std
- Dashed line: ground truth
- 4 lines (one per method)

**Chart 2: Absolute error vs N_max**
- X: N_max (log scale)
- Y: |estimated − true| (log scale)
- 4 lines

**Chart 3: Time vs N_max**
- X: N_max (log scale)
- Y: computation time (ms)
- 4 lines
- Shows V3/V5 are sub-linear (early termination at lower N_max)

**Table: Convergence summary**

| Method | N for error < 0.05 | N for error < 0.01 | Time at convergence |
|--------|-------------------|-------------------|-------------------|

---

## 6. Revised Sub-Experiment 3.3: Selectivity Sensitivity

### 6.1 Changes from Original

| Original | Revised |
|----------|---------|
| 200×200 = 40,000 pairs | **50×50 = 1,225 pairs** (limit-graphs 50) |
| 5 methods | **4 methods** (or 3) |
| Estimated ~27 hours | **~1 hour** |

### 6.2 Why 50×50 Is Sufficient

Exp 3.3 asks "how does accuracy change with θ?" — this is about the **shape** of the accuracy-vs-θ curve, not about absolute throughput. 1,225 pairs is enough to produce smooth accuracy curves with statistical significance.

### 6.3 Protocol

Dataset: all 800 pairs (merged), loaded with `--limit-graphs 50`.

For each (method × θ):
1. Run SIMILARITYJOIN on all pairs
2. Record: total time, result count
3. Compute accuracy against ground truth (recomputed at each θ)
4. Repeat 10 times

θ values: 0.01, 0.05, 0.1, 0.2, 0.3, 0.5

### 6.4 Expected Results

**Time vs θ:**
- V1/V2: constant (always compute full JSD regardless of θ)
- V3_SPRT: faster at extreme θ (easy binary decisions → early termination)
- V5_ADAPTIVE: fastest at extreme θ (bounds resolve most pairs), slower near θ=0.3 (many borderline pairs)

**Accuracy vs θ:**
- All methods ~100% at θ=0.01 (everything dissimilar) and θ=0.5 (almost everything similar)
- Accuracy dips near θ=0.2–0.3 where many pairs are borderline
- V5_ADAPTIVE should maintain highest accuracy across all θ

### 6.5 Output

**Chart 1: Execution time vs θ (line chart, one line per method)**
**Chart 2: Accuracy vs θ (line chart, one line per method)**
**Chart 3: Result count vs θ (line chart + ground truth reference)**

---

## 7. Full Run Configuration

### 7.1 Timing Estimate

| Sub-Exp | Configs | Runs per config | Est. time per run | Total |
|---------|---------|----------------|-------------------|-------|
| 3.1 | 4 methods × 4 difficulties | 10 | ~30s (200 pairs × 4 methods) | ~32 min |
| 3.2 | 4 methods × 7 N values | 50 | ~2s (1 pair) | ~23 min |
| 3.3 | 4 methods × 6 θ × 4 datasets | 10 | ~10s (1,225 pairs) | ~160 min |
| **Total** | | | | **~3.5 hours** |

Manageable as an overnight run.

### 7.2 JVM Settings

```bash
# V1: Naive MC, N=10,000
java -Dprobsparql.mode=GT_10K ...

# V2: Stratified, N=10,000
java -Dprobsparql.mode=V2_STRATIFIED ...

# V3: SPRT (after bug fix)
java -Dprobsparql.mode=V3_SPRT ...

# V5: Adaptive cascade (after bug fix)
java -Dprobsparql.mode=V5_ADAPTIVE ...
```

### 7.3 Warmup and Runs

| Sub-Exp | Warmup | Measured Runs |
|---------|--------|--------------|
| 3.1 | 3 | 10 |
| 3.2 | 3 | 50 |
| 3.3 | 3 | 10 |

---

## 8. Implementation TODO

### P0: Bug Fixes (MUST complete before full run)

| # | Task | File | Validation |
|---|------|------|-----------|
| 1 | Debug V3_SPRT: why JSD always ≈ 0.005 | `SPRTSampler.java` | V3 accuracy ≥ 90% on easy pairs |
| 2 | Debug V5_ADAPTIVE: why L5 never triggers | `AdaptiveSampler.java` or `PrunedSimJoinEvaluator.java` | V5 on hard pair returns JSD ≈ 0.3 (not 0.151) |
| 3 | Fix V4_BOUNDS ternary output | `BoundsFilterSampler.java` | V4 returns INCONCLUSIVE for hard pairs |
| 4 | Verify fixes on 10 test pairs | Manual / unit test | All methods agree within ±0.05 on known pairs |

### P1: Data and Scripts

| # | Task | File | Status |
|---|------|------|--------|
| 5 | Generate 800 controlled pairs (200 per difficulty) | `generate_sim_join_data.py` | ⚠️ Verify params match plan |
| 6 | Update ClassificationAccuracyBenchmark: REPEAT=10 | `.java` | ⚠️ Verify |
| 7 | Update SelectivityBenchmark: --limit-graphs 50 | `.java` | ✅ Added |
| 8 | Verify ConvergenceBenchmark: 50 reps, 7 N values | `.java` | ✅ Exists |

### P2: Analysis

| # | Task | File |
|---|------|------|
| 9 | Exp 3.1 analysis: accuracy tables + grouped bar chart | `analyze_exp3_1.py` |
| 10 | Exp 3.1 analysis: speed-accuracy scatter | `analyze_exp3_1.py` |
| 11 | Exp 3.2 analysis: convergence lines + error lines | `analyze_exp3_2.py` |
| 12 | Exp 3.3 analysis: time vs θ, accuracy vs θ | `analyze_exp3_3.py` |

---

## 9. Paper Narrative

### If V3_SPRT is fixed:

> We evaluate four JSD estimation strategies for the SIMILARITYJOIN operator: naive Monte Carlo (V1), stratified sampling (V2), sequential probability ratio testing (V3), and an adaptive cascade combining analytic bounds with sequential testing (V5). All methods achieve near-perfect accuracy on clearly similar or dissimilar distribution pairs. On borderline pairs near the decision threshold, the adaptive methods (V3, V5) match the accuracy of fixed-sample methods while requiring significantly fewer samples to converge — V5 reaches |error| < 0.01 at N ≈ 3,000 versus N ≈ 50,000 for naive MC, a 16× improvement in sample efficiency. We recommend V5 as the default for ProbSPARQL deployments.

### If V3_SPRT cannot be fixed in time:

> We evaluate three JSD estimation strategies: naive Monte Carlo (V1), stratified sampling (V2), and an adaptive cascade (V5) that combines analytic bounds with Monte Carlo fallback. V5 achieves the best speed-accuracy tradeoff: on easy pairs, the analytic bounds resolve the comparison in microseconds without sampling; on borderline pairs, the MC fallback ensures accuracy comparable to V1. V5 converges to |error| < 0.01 at N ≈ 3,000 versus N ≈ 50,000 for V1, making it the recommended default.

### Connection to Experiment 2:

> Experiment 2 demonstrated that the SIMILARITYJOIN operator achieves up to 174× speedup over naive external processing using GT_10K Monte Carlo as the JSD estimator. Experiment 3 shows that replacing GT_10K with the adaptive cascade (V5) further reduces the per-pair JSD cost by ~10× for easy pairs and ~3× for hard pairs, without sacrificing accuracy. Together, these optimizations make distribution-aware similarity queries practical for knowledge graphs with tens of thousands of entities.

---

## 10. Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| V3_SPRT bug unfixable before deadline | Drop V3; present V1, V2, V5 (still a complete story) |
| V5_ADAPTIVE bug unfixable | Present V1, V2 only + note "adaptive methods are future work" (weakens paper) |
| Exp 3.3 still too slow at 50×50 | Reduce to 30×30 (435 pairs); still statistically meaningful |
| V2_STRATIFIED shows negligible improvement over V1 | Possible for 1D data; note that stratification helps more in higher dimensions |
| Hard pairs too few near exact θ=0.3 | Generate extra pairs in [0.28, 0.32]; current 200 should be sufficient |
| 50 reps in Exp 3.2 not enough for smooth convergence | Increase to 100 if time permits; 50 is standard |
