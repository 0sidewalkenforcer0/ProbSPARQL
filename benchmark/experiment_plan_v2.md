# ProbSPARQL Evaluation — Experiment Plan v2

**Updated:** 2025-03-20  
**Based on:** Gap analysis of plan v1 vs actual implementation

---

## Key Changes from v1

| Item | Plan v1 | Plan v2 (aligned with implementation) |
|------|---------|--------------------------------------|
| Similarity threshold θ | 0.2 | **0.3** |
| Sampling methods | 4 (M1–M4) | **5 (V1–V5)** |
| Dataset B format | JSON | **TTL** (loaded directly into Jena) |
| Dataset B "hard" range | JSD ∈ [0.18, 0.22] | **JSD ∈ [0.25, 0.35]** |
| Exp 3.1 repetitions | 10 | **10** (fix from current 3) |

---

## Overview

| Experiment | Section | Status | Action Required |
|-----------|---------|--------|-----------------|
| Exp 1: System Overhead | 6.2.1 | ⚠️ Partially implemented | **Fix**: real deterministic baseline |
| Exp 2: In-Engine vs External | 6.2.2 | ❌ Missing | **Implement from scratch** |
| Exp 3.1: SimJoin Accuracy | 6.2.3 | ⚠️ Implemented, params off | **Fix**: repetitions 3→10, pair count |
| Exp 3.2: SimJoin Convergence | 6.2.3 | ✅ Done | Minor review only |
| Exp 3.3: Selectivity Sensitivity | 6.2.3 | ✅ Done | Minor review only |
| Exp 4: Histogram Generalization | 6.3 | ❌ Missing | **Implement from scratch** |

### Priority Order

```
P0 (blocking paper submission):
  → Fix Exp 1 (deterministic baseline)
  → Implement Exp 4 (histogram generalization)
  
P1 (essential for credibility):
  → Fix Exp 3.1 (repetitions + pair count)
  → Implement Exp 2 (in-engine vs external)

P2 (already done, review only):
  → Exp 3.2 (convergence)
  → Exp 3.3 (selectivity)
```

---

## Sampling Methods (5 methods)

| ID | Name | Description |
|----|------|-------------|
| V1_MC | Naive Monte Carlo | Standard MC sampling from both distributions |
| V2_STRATIFIED | Stratified Sampling | Stratified partitioning of sample space |
| V3_SPRT | Sequential Probability Ratio Test | Early stopping based on statistical confidence |
| V4_BOUNDS | Analytic Bounds | Upper/lower bounds on JS without full sampling |
| V5_ADAPTIVE | Adaptive Sampling | Dynamically adjusts sample size based on intermediate estimates |

---

## Datasets

### Dataset A: Scalable RDF Knowledge Graph (GMM)

- **Used in:** Exp 1, Exp 2
- **Content:** Synthetic angle grinder instances with GMM-encoded measurements
- **Existing script:** `generate_dataset.py`
- **Scales:** ~1K, 5K, 10K, 50K, 100K triples (10–1000 products)
- **Components per product:** 5
- **Measurements per characteristic:** 1–3
- **GMM parameters:**
  - Means: physically plausible ranges per characteristic
  - Variances: log-uniform from [0.01, 0.5]
  - Weights: Dirichlet(1,...,1)
  - K ∈ {1, 3, 5, 10}, d = 1
- **Format:** TTL files, loadable into Jena Fuseki

### Dataset A-det: Deterministic Parallel (NEW — needs implementation)

- **Used in:** Exp 1
- **Content:** Same graph structure as Dataset A, but all probabilistic literals replaced with deterministic point estimates (xsd:double)
- **Purpose:** True deterministic SPARQL baseline for overhead measurement
- **Script needed:** `generate_dataset_deterministic.py`

### Dataset B: Controlled Distribution Pairs

- **Used in:** Exp 3.1, Exp 3.3
- **Existing script:** `generate_sim_join_data.py`
- **Reference threshold:** θ = 0.3
- **Format:** TTL files (`simjoin_easy.ttl`, `simjoin_medium.ttl`, `simjoin_hard.ttl`, `simjoin_mixed.ttl`)

**Difficulty levels (aligned with θ = 0.3):**

| Difficulty | JSD Range | Pairs per dataset | Status |
|-----------|-----------|-------------------|--------|
| Easy | [0.0, 0.1) ∪ (0.5, 0.693] | 200 | ⚠️ Currently ~50, increase to 200 |
| Medium | [0.10, 0.25) ∪ (0.35, 0.5] | 200 | ⚠️ Currently ~100, increase to 200 |
| Hard | [0.25, 0.35] | 200 | ⚠️ Currently ~100, increase to 200 |
| Mixed | 1/3 easy + 1/3 medium + 1/3 hard | 200 | ⚠️ Currently ~100, increase to 200 |

**Action:** Rerun `generate_sim_join_data.py` with `--n 200` to produce 200 pairs per category (total 800 pairs).

### Dataset C: Convergence Pair

- **Used in:** Exp 3.2
- **Content:** One fixed "hard" pair where JS ≈ 0.3
- **Status:** ✅ Embedded in Java benchmark, working correctly

### Dataset D: Histogram Variants (NEW — needs implementation)

- **Used in:** Exp 4
- **Content:** Parallel to Dataset A and Dataset B with histogram literals
- **Generation:** Draw 10⁴ samples from each GMM, bin into B ∈ {20, 50, 100} bins
- **Script needed:** `generate_histogram_variants.py`
- **Output:**
  - `dataset_A_hist_B20.ttl`, `dataset_A_hist_B50.ttl`, `dataset_A_hist_B100.ttl`
  - `simjoin_easy_hist.ttl`, `simjoin_medium_hist.ttl`, `simjoin_hard_hist.ttl`

---

## Experiment Details

---

### Experiment 1: System Overhead (FIX REQUIRED)

**Question:** What is the runtime cost of probabilistic extensions vs standard SPARQL?

**Current problem:** `analyze_exp1_scalability.py` uses SimJoin results as proxy, not a real deterministic baseline.

**Correct design:**

**Step 1:** Load Dataset A (probabilistic) and Dataset A-det (deterministic) into separate Jena Fuseki instances.

**Step 2:** Run equivalent query pairs:

| ID | Deterministic Query (on A-det) | ProbSPARQL Query (on A) |
|----|-------------------------------|------------------------|
| Q1 | `SELECT ?gear WHERE { ?gear cfm:hasValue ?length . FILTER(?length < 9.8) }` | `SELECT ?gear WHERE { ... BIND(prob:cdf(?dist, 9.8) AS ?p) FILTER(?p >= 0.9) }` |
| Q2 | `SELECT ?motor WHERE { ... BIND(?speed * ?torque AS ?power) }` | `SELECT ?motor WHERE { ... BIND(prob:product(?gmmSpeed, ?gmmTorque) AS ?powerDist) }` |
| Q3 | Simple BGP returning xsd:double | Same BGP returning uq:gmmLiteral (literal parsing overhead only) |

**Independent variables:**
- Graph scale: 1K, 5K, 10K, 50K, 100K triples
- GMM complexity: K ∈ {1, 3, 5, 10}

**Protocol:**
1. Load dataset into Jena Fuseki
2. Warm up: 5 runs, discard
3. Measure: **30 runs** (fix from current 5)
4. Record median + IQR
5. Repeat for each (scale × K) combination

**Metrics:** Execution time (ms), overhead ratio = ProbSPARQL / deterministic

**Expected output:**
- Table: rows = scale, columns = K, cells = overhead ratio
- Line chart: x = scale, y = time, lines = deterministic vs ProbSPARQL (per K)

**Implementation TODO:**
- [ ] Create `generate_dataset_deterministic.py`
- [ ] Update `ScalabilityBenchmark.java`: add deterministic queries, increase `BENCHMARK_RUNS` to 30
- [ ] Integrate `ScalabilityBenchmark.java` into `run_all_experiments.sh`
- [ ] Create proper `analyze_exp1.py` (not reusing SimJoin data)

---

### Experiment 2: In-Engine vs External Processing (NEW — implement from scratch)

**Question:** Is native ProbSPARQL faster than exporting data and processing externally?

**Approach A — ProbSPARQL (in-engine):**
```sparql
SELECT ?gear WHERE {
  ?gear a ag:CrownGear ; cfm:hasCharacteristic ?char .
  ?m_ct cfm:measuresCharacteristic ?char ;
        cfm:hasProbabilisticValue ?rv_ct .
  ?rv_ct uq:hasDistribution ?gmm_ct .
  ?m_sl cfm:measuresCharacteristic ?char ;
        cfm:hasProbabilisticValue ?rv_sl .
  ?rv_sl uq:hasDistribution ?gmm_sl .
  BIND(prob:jsd(?gmm_ct, ?gmm_sl) AS ?div)
  FILTER(?div > 0.3)
}
```

**Approach B — Post-processing (external):**
```
Step 1: SPARQL SELECT ?gmm_ct ?gmm_sl WHERE { ... }  (export all pairs)
Step 2: Serialize results to JSON
Step 3: Python script: parse GMMs, compute JSD, filter
Total time = Step 1 + Step 2 + Step 3
```

**Independent variables:**
- Number of distribution pairs: 100, 500, 1K, 5K, 10K
- Selectivity (% passing filter): 10%, 50%, 90%

**Protocol:**
1. Load Dataset A at appropriate scale
2. Measure Approach A: single ProbSPARQL query time (30 runs, median)
3. Measure Approach B: export query time + serialization + Python computation (30 runs, median)
4. Report end-to-end time for both

**Metrics:** End-to-end time (ms), data transfer volume (KB)

**Expected output:**
- Grouped bar chart: x = num pairs, bars = in-engine vs external
- Table with breakdown: query time, transfer time, compute time

**Implementation TODO:**
- [ ] Create `InEngineVsExternalBenchmark.java` (Approach A timing)
- [ ] Create `exp2_external_baseline.py` (Approach B: parse + compute)
- [ ] Create `exp2_run.sh` (orchestrates both)
- [ ] Create `analyze_exp2.py`

---

### Experiment 3.1: SimJoin — Accuracy across Difficulties (FIX REQUIRED)

**Question:** Which sampling method classifies distribution pairs most accurately?

**Current problems:**
- `REPEAT = 3` → should be **10**
- Pair count ~100/category → should be **200**

**Methods:** V1_MC, V2_STRATIFIED, V3_SPRT, V4_BOUNDS, V5_ADAPTIVE

**Parameters:**
- θ = 0.3
- Default sample size: N = 10,000
- Difficulty levels: easy, medium, hard, mixed
- Pairs per difficulty: **200**
- Repetitions per configuration: **10**

**Metrics:** Accuracy (%), Precision, Recall, F1-score

**Protocol:**
1. Load simjoin TTL files (200 pairs per category)
2. For each (method × difficulty × repetition):
   - Run SimJoin query
   - Classify each pair: similar (JS ≤ 0.3) or dissimilar (JS > 0.3)
   - Compare against ground truth
3. Aggregate per (method × difficulty)

**Expected output:**
- Table: rows = difficulty, columns = methods, cells = accuracy ± std
- Grouped bar chart

**Implementation TODO:**
- [ ] Regenerate data: `python generate_sim_join_data.py --n 200`
- [ ] Update `ClassificationAccuracyBenchmark.java`: `REPEAT = 10`
- [ ] Rerun experiment

---

### Experiment 3.2: SimJoin — Convergence Analysis (✅ DONE)

**Status:** Implemented correctly. Review only.

**Parameters (confirmed):**
- Sample sizes: 100, 500, 1K, 5K, 10K, 50K, 100K
- Methods: V1–V5
- Repetitions: 50
- One fixed "hard" pair

**Expected output:**
- Line chart: x = sample size (log), y = estimated JS, dashed line = ground truth
- Line chart: x = sample size (log), y = absolute error
- Shaded ±1 std regions

**TODO:** Review output charts for clarity; no rerun needed.

---

### Experiment 3.3: Selectivity Sensitivity (✅ DONE)

**Status:** Implemented correctly. Review only.

**Parameters (confirmed):**
- θ values: 0.01, 0.05, 0.1, 0.2, 0.3, 0.5
- Methods: V1–V5
- Dataset: simjoin pairs (Cartesian product: 100 left × 100 right = 10,000 pairs)

**Note:** The Cartesian product approach (10,000 pairs) differs from plan v1 (800 pairs with direct pairing), but this is actually more realistic — it simulates a real join workload.

**Expected output:**
- Line chart: x = θ, y = execution time
- Line chart: x = θ, y = accuracy
- Bar chart: x = θ, y = result set size

**TODO:** Review output; ensure charts are publication-ready. Consider regenerating with 200 pairs per side (200×200 = 40,000 pairs) after fixing pair counts.

---

### Experiment 4: Histogram Generalization (NEW — implement from scratch)

**Question:** Does ProbSPARQL work with non-parametric distributions?

**Prerequisites:**
1. Define `uq:histogramLiteral` datatype in Jena (JSON-based encoding)
2. Implement JS divergence computation for histograms (discrete sum)
3. Implement histogram versions of key operators: `prob:cdf`, `prob:mean`, `prob:map`

**Sub-experiment 4.1: Overhead with Histograms**

Repeat Exp 1 with histogram literals.

| Literal Type | Dataset |
|-------------|---------|
| Deterministic (xsd:double) | A-det |
| GMM (uq:gmmLiteral) | A |
| Histogram (uq:histogramLiteral) | A-hist |

- Additional variable: bin count B ∈ {20, 50, 100}
- Graph scale: 10K triples (one representative scale)
- Metrics: execution time, overhead ratio vs deterministic

**Sub-experiment 4.2: SimJoin Accuracy with Histograms**

Repeat Exp 3.1 with histogram pairs.

- Generate histogram pairs from same GMM pairs (Dataset B → B-hist)
- Bin counts: B ∈ {20, 50, 100}
- Methods: V1–V5
- Metrics: accuracy, comparison vs GMM accuracy

**Expected output:**
- Table: overhead ratio for deterministic vs GMM vs histogram (B=20/50/100)
- Table: SimJoin accuracy comparison GMM vs histogram

**Implementation TODO:**
- [ ] Implement `uq:histogramLiteral` in Jena ARQ
- [ ] Implement histogram JS divergence (discrete KL → JS)
- [ ] Implement histogram `prob:cdf`, `prob:mean`, `prob:map`
- [ ] Create `generate_histogram_variants.py` (converts GMM datasets → histogram)
- [ ] Create `HistogramBenchmark.java` (runs Exp 4.1 and 4.2)
- [ ] Create `analyze_exp4.py`

---

## Implementation Checklist

### P0 — Blocking (must complete before submission)

**Exp 1 Fix:**
- [ ] `generate_dataset_deterministic.py` — deterministic parallel of Dataset A
- [ ] Update `ScalabilityBenchmark.java` — real baseline queries, `BENCHMARK_RUNS = 30`
- [ ] Integrate into `run_all_experiments.sh`
- [ ] `analyze_exp1.py` — proper overhead analysis

**Exp 4 New:**
- [ ] `uq:histogramLiteral` datatype in Jena ARQ
- [ ] Histogram operators: JS divergence, CDF, mean, MAP
- [ ] `generate_histogram_variants.py`
- [ ] `HistogramBenchmark.java`
- [ ] `analyze_exp4.py`

### P1 — Essential for credibility

**Exp 3.1 Fix:**
- [ ] Regenerate data: `python generate_sim_join_data.py --n 200`
- [ ] Update `ClassificationAccuracyBenchmark.java`: `REPEAT = 10`
- [ ] Rerun and regenerate charts

**Exp 2 New:**
- [ ] `InEngineVsExternalBenchmark.java`
- [ ] `exp2_external_baseline.py`
- [ ] `exp2_run.sh`
- [ ] `analyze_exp2.py`

### P2 — Review only

- [ ] Review Exp 3.2 output charts for publication quality
- [ ] Review Exp 3.3 output charts for publication quality
- [ ] Consider rerunning Exp 3.3 with 200 pairs after data regeneration

---

## Environment

- **Hardware:** [TODO: specify CPU, RAM, OS]
- **Software:** Apache Jena Fuseki [version], Java [version], Python 3.x
- **Repetitions:**
  - Exp 1, 2: 30 per query configuration
  - Exp 3.1: 10 per configuration
  - Exp 3.2: 50 per configuration (confirmed)
  - Exp 3.3: as implemented
- **Reporting:** Median + IQR (Exp 1, 2); Mean ± std (Exp 3)

---

## Correspondence: Paper Section ↔ Experiment

| Paper Section | Content | Data Source |
|--------------|---------|-------------|
| §6 intro | Experimental data description | Appendix C |
| §6.1 | Query Demonstrations (U1–U5) | Already written |
| §6.2.1 | System Overhead | Exp 1 |
| §6.2.2 | In-Engine vs External | Exp 2 |
| §6.2.3 | Similarity Join Benchmarks | Exp 3.1 + 3.2 + 3.3 |
| §6.3 | Generalization to Histograms | Exp 4 |

---

## Timeline

| Week | Tasks |
|------|-------|
| 1 | **P0:** Fix Exp 1 (deterministic dataset + proper baseline); Start Exp 4 (histogram literal type in Jena) |
| 2 | **P0:** Complete Exp 4 (histogram operators + benchmarks); **P1:** Fix Exp 3.1 (regenerate data, increase reps) |
| 3 | **P1:** Implement Exp 2 (in-engine vs external); **P2:** Review Exp 3.2/3.3 charts |
| 4 | Run all experiments end-to-end; generate final charts; write §6.2 and §6.3 |
