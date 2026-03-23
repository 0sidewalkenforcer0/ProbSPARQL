# Experiment 2: Value of In-Engine Processing (v5)

## Key Changes from v4

| v4 Design | Problem | v5 Design |
|-----------|---------|-----------|
| B uses Python | Language difference confounds comparison | **B uses same Java code** |
| Focus on raw speed | Misses the real advantage | **Focus on filter pushdown and pipeline optimization** |
| Simple single-filter query | Doesn't showcase engine optimization | **Multi-filter queries with early elimination** |
| d=3 K=5 to make A>B | Artificial parameter tuning | **Not needed — filter pushdown naturally makes A>B** |

---

## 1. Core Insight

The real value of in-engine probabilistic processing is NOT raw computation speed. It is the ability of the SPARQL query engine to **optimize the execution pipeline**:

1. **Filter pushdown:** Evaluate cheap filters early, before expensive joins
2. **Early pipeline termination:** Skip downstream work when an early filter fails
3. **No intermediate materialization:** Stream results through the pipeline without exporting all intermediate data

An external approach **cannot** benefit from any of these — it must:
1. Export ALL intermediate data first
2. Apply all filters sequentially in external code
3. Materialize every intermediate result

---

## 2. Three Approaches (Revised)

### Approach A — In-Engine (SPARQL Pipeline with Filters)

A single ProbSPARQL query with multiple filters. The engine optimizes filter evaluation order and pushes filters as early as possible in the pipeline.

```sparql
SELECT ?gear ?jsd
WHERE {
  ?gear a ag:CrownGear ;
        cfm:hasCharacteristic ?char .
  ?m1 cfm:measuresCharacteristic ?char ;
      cfm:hasProbabilisticValue ?rv1 .
  ?rv1 uq:hasDistribution ?gmm1 .
  FILTER(prob:modeCount(?gmm1) > 1)              # F1: early filter
  ?m2 cfm:measuresCharacteristic ?char ;
      cfm:hasProbabilisticValue ?rv2 .
  ?rv2 uq:hasDistribution ?gmm2 .
  FILTER(prob:modeCount(?gmm2) > 1)              # F2: early filter
  BIND(prob:jsd(?gmm1, ?gmm2) AS ?jsd)
  FILTER(?jsd > ?threshold)                       # F3: expensive filter
}
```

**What the engine does internally:**

```
For each gmm1 binding:
  Evaluate F1: prob:modeCount(gmm1) > 1          ← cheap, O(K)
  If F1 fails → skip to next gmm1 (never touch gmm2)
  For each gmm2 binding:
    Evaluate F2: prob:modeCount(gmm2) > 1        ← cheap, O(K)
    If F2 fails → skip to next gmm2 (never compute JSD)
    Compute JSD(gmm1, gmm2)                      ← expensive, O(N×K)
    Evaluate F3: jsd > θ
    If F3 passes → emit result
```

If 60% of distributions are unimodal:
- F1 eliminates 60% of gmm1 candidates immediately
- Of the 40% surviving, F2 eliminates 60% of gmm2 candidates
- Only 40% × 40% = **16%** of all possible pairs reach the JSD computation
- **84% of expensive JSD computations are avoided**

### Approach B — External (Same Java, No Pipeline Optimization)

Export ALL data, then apply the same filters externally using identical Java code.

**Step 1 — SPARQL export (no filters, just data retrieval):**
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
}
```

**Step 2 — External Java processing (same functions, sequential):**
```java
// Parse exported JSON response
List<ExportedRow> allPairs = parseJsonResponse(httpResponse);

// Must process ALL pairs — no pipeline optimization possible
List<Result> results = new ArrayList<>();
for (ExportedRow row : allPairs) {
    // Re-parse GMM literals from exported strings
    GMMValue gmm1 = GMMDatatype.parse(row.gmm1String);
    GMMValue gmm2 = GMMDatatype.parse(row.gmm2String);

    // Apply F1 — but we already exported this pair!
    if (ProbFunctions.modeCount(gmm1) <= 1) continue;

    // Apply F2
    if (ProbFunctions.modeCount(gmm2) <= 1) continue;

    // Apply F3 — expensive JSD
    double jsd = ProbFunctions.jsdMC(gmm1, gmm2, 10000);
    if (jsd > theta) {
        results.add(new Result(row.gear, jsd));
    }
}
```

**Critical difference:** B exports ALL pairs (including those where gmm1 or gmm2 is unimodal), then filters. The export query has no `FILTER` clauses because filters depend on probabilistic functions not available in standard SPARQL.

**Cost breakdown of B:**
```
T_B = T_export_all_pairs              ← must fetch ALL pairs
    + T_http_transfer                  ← transfer ALL pair data
    + T_reparse_all_gmms              ← re-parse every GMM string
    + T_modecount_all                 ← compute modeCount for all (wasted for unimodal)
    + T_jsd_survivors_only            ← JSD only for F1+F2 survivors
```

**Cost breakdown of A:**
```
T_A = T_graph_traversal
    + T_modecount_survivors_F1_only   ← only gmm1's that pass F1
    + T_jsd_survivors_F1_F2_only      ← only pairs that pass both F1 and F2
```

### Approach C — SIMILARITYJOIN (Pruning Operator)

Same as before: dedicated operator with L1–L5 pruning cascade.

```sparql
SELECT ?gear
WHERE {
  { ?gear a ag:CrownGear ;
          cfm:hasCharacteristic ?char .
    ?m1 cfm:measuresCharacteristic ?char ;
        cfm:hasProbabilisticValue ?rv1 .
    ?rv1 uq:hasDistribution ?gmm1 .
    FILTER(prob:modeCount(?gmm1) > 1) }
  SIMILARITYJOIN(?gmm1, ?gmm2, ?threshold)
  { ?m2 cfm:measuresCharacteristic ?char ;
        cfm:hasProbabilisticValue ?rv2 .
    ?rv2 uq:hasDistribution ?gmm2 .
    FILTER(prob:modeCount(?gmm2) > 1) }
}
```

C benefits from BOTH filter pushdown (F1/F2 inside sub-patterns) AND pruning (L2–L5 on surviving pairs).

---

## 3. Why This Design Guarantees C < A < B

### A < B (engine beats external)

**Source of advantage:** Filter pushdown.

B must export ALL n² pairs, then filter. A filters as it goes — pairs that fail F1 never generate any downstream work.

| What B does | What A avoids |
|------------|--------------|
| Export ALL pairs (including unimodal) | Never exports anything |
| Re-parse ALL GMM strings | GMMs already parsed in-engine |
| Compute modeCount for ALL gmm1 and gmm2 | Only computes for gmm1 that survive F1 |
| Transfer large JSON over HTTP | No data transfer |

Even with identical Java JSD code, A is faster because it does **less work** thanks to the query pipeline.

### C < A (pruning beats naive)

Among the pairs that survive F1 and F2, C additionally prunes pairs via mean/variance checks before computing JSD. A computes JSD for all surviving pairs.

### The Math

Assume:
- n = 100 entities, n(n-1)/2 = 4,950 unique pairs
- 60% of distributions are unimodal (modeCount = 1)
- θ chosen for 10% selectivity among multi-modal pairs

| Step | B (external) | A (in-engine) | C (SimJoin) |
|------|-------------|--------------|-------------|
| Pairs exported/considered | 4,950 | 4,950 | 4,950 |
| After F1 (modeCount gmm1) | all 4,950 exported, then 1,980 survive | 1,980 (F1 pushdown) | 1,980 (F1 in sub-pattern) |
| After F2 (modeCount gmm2) | 1,980 → 792 | 792 | 792 |
| Pairs needing JSD | 792 | 792 | ~160 (80% pruned) |
| GMM re-parses | 9,900 (all pairs × 2) | 0 | 0 |

**B does 792 JSD + 9,900 re-parses + HTTP transfer of 4,950 pairs**
**A does 792 JSD + 0 overhead**
**C does ~160 JSD + cheap pruning on 792 pairs**

---

## 4. Controlling the Early-Filter Selectivity

The fraction of distributions that are unimodal (modeCount = 1) determines how much work F1/F2 eliminates. We control this as an **experimental variable**.

### 4.1 Data Generation

When generating Dataset A, control the mix of unimodal vs multi-modal distributions:

| Config | % Unimodal (K=1) | % Multi-modal (K=3 or K=5) | F1/F2 filter rate |
|--------|------------------|---------------------------|-------------------|
| Mix-20 | 20% | 80% | Low early filtering |
| Mix-50 | 50% | 50% | Moderate early filtering |
| Mix-80 | 80% | 20% | High early filtering |

This shows how the engine's advantage grows as early filters become more selective.

### 4.2 Alternative Early Filters

Instead of modeCount (or in addition), use other cheap probabilistic filters:

| Filter | Condition | Cost | Rationale |
|--------|-----------|------|-----------|
| F_mode | `prob:modeCount(?gmm) > 1` | O(K) | Only compare multi-modal distributions |
| F_std | `prob:std(?gmm) < 2.0` | O(K) | Only compare low-variance distributions |
| F_mean | `prob:mean(?gmm) > 5.0` | O(K) | Only compare distributions in a specific range |

The specific filter doesn't matter — what matters is that it's **cheap and selective**, demonstrating the filter-pushdown advantage.

---

## 5. Datasets

### 5.1 GMM Parameters

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| d | 1 | Keep simple (the advantage comes from pipeline, not from computation complexity) |
| K | 1 or 3 (mixed) | Controls modeCount filter selectivity |
| N (MC samples) | 10,000 | Fixed GT_10K |

**No need for d=3 K=5.** The performance ordering C < A < B is guaranteed by the pipeline architecture, not by making JSD expensive per pair.

### 5.2 Scales

| Config | Entities | Unique Pairs | Purpose |
|--------|---------|-------------|---------|
| S1 | 15 | 105 | Small |
| S2 | 33 | 528 | Medium-small |
| S3 | 46 | 1,035 | Medium |
| S4 | 101 | 5,050 | Large |
| S5 | 142 | 10,011 | Stress |

### 5.3 Configurations

| Variable | Values |
|----------|--------|
| Scale | S1–S5 (5 levels) |
| Unimodal fraction | 20%, 50%, 80% (3 levels) |
| JSD selectivity | 10%, 50%, 90% (3 levels) |
| Total configs | 5 × 3 × 3 = 45 per approach |

---

## 6. Protocol

### 6.1 Fairness Guarantees

| Aspect | A | B | C |
|--------|---|---|---|
| JSD algorithm | Java GT_10K | **Same** Java GT_10K | L5 fallback = **Same** Java GT_10K |
| modeCount implementation | `ProbFunctions.modeCount()` | **Same** `ProbFunctions.modeCount()` | **Same** (inside sub-pattern) |
| Language | Java | **Java** | Java |
| GMM parsing | Engine-internal (parsed once on load) | Re-parsed from exported string | Engine-internal |

The ONLY differences are:
- A: engine pipeline with filter pushdown
- B: export everything, then filter externally with identical code
- C: engine pipeline + pruning operator

### 6.2 Approach B Implementation

```java
public class Exp2ExternalBaseline {

    public static void main(String[] args) {
        String endpoint = args[0];
        double theta = Double.parseDouble(args[1]);
        int nSamples = 10000;

        // Step 1: HTTP request to Fuseki (export ALL pairs, NO filters)
        long t0 = System.nanoTime();
        String jsonResponse = httpGet(endpoint, EXPORT_QUERY);
        long fetchTime = System.nanoTime() - t0;

        // Step 2: Parse SPARQL JSON response
        t0 = System.nanoTime();
        List<ExportedBinding> bindings = parseSparqlJson(jsonResponse);
        long parseResponseTime = System.nanoTime() - t0;

        // Step 3: Re-parse GMM literals + apply filters + compute JSD
        t0 = System.nanoTime();
        int resultCount = 0;
        int totalParsed = 0;
        int survivedF1F2 = 0;

        for (ExportedBinding b : bindings) {
            // Re-parse GMM strings (this is the overhead A avoids)
            GMMValue gmm1 = GMMDatatype.INSTANCE.parse(b.gmm1String);
            GMMValue gmm2 = GMMDatatype.INSTANCE.parse(b.gmm2String);
            totalParsed += 2;

            // F1: modeCount filter (same Java function as engine uses)
            if (ProbFunctions.modeCount(gmm1) <= 1) continue;
            // F2: modeCount filter
            if (ProbFunctions.modeCount(gmm2) <= 1) continue;
            survivedF1F2++;

            // F3: JSD threshold (same MC algorithm as engine uses)
            double jsd = ProbFunctions.jsdMC(gmm1, gmm2, nSamples);
            if (jsd > theta) {
                resultCount++;
            }
        }
        long computeTime = System.nanoTime() - t0;

        // Report
        System.out.printf("Fetch: %.1f ms%n", fetchTime / 1e6);
        System.out.printf("Parse response: %.1f ms%n", parseResponseTime / 1e6);
        System.out.printf("Compute+filter: %.1f ms%n", computeTime / 1e6);
        System.out.printf("Total pairs exported: %d%n", bindings.size());
        System.out.printf("GMMs re-parsed: %d%n", totalParsed);
        System.out.printf("Survived F1+F2: %d%n", survivedF1F2);
        System.out.printf("Final results: %d%n", resultCount);
    }
}
```

### 6.3 Execution

For each (scale × unimodal_fraction × θ):

**A:**
1. Warm-up: 5 runs
2. Measurement: 10 runs
3. Record: time, result count

**B:**
1. Warm-up: 3 runs
2. Measurement: 10 runs
3. Record: fetch_ms, parse_ms, compute_ms, total_ms, result count, pairs_exported, gmms_reparsed, survived_F1F2

**C:**
1. Warm-up: 5 runs
2. Measurement: 10 runs
3. Record: time, result count, pruning stats

### 6.4 Validation

For every configuration:

| Check | Criterion |
|-------|-----------|
| ResultCount_A = ResultCount_C | Exact match (C recall = 100%) |
| ResultCount_A ≈ ResultCount_B | Within ±2% (MC variance) |
| ResultCount_A = ResultCount_C | If not → fix pruning bounds before proceeding |

---

## 7. Expected Results

### 7.1 At Mix-50 (50% unimodal), 10% JSD selectivity

| Scale | All pairs | After F1/F2 | JSD computed (A) | JSD computed (C) | B exports |
|------:|----------:|------------:|-----------------:|-----------------:|----------:|
| 105 | 105 | ~26 | 26 | ~8 | 105 |
| 1,035 | 1,035 | ~259 | 259 | ~78 | 1,035 |
| 5,050 | 5,050 | ~1,263 | 1,263 | ~379 | 5,050 |
| 10,011 | 10,011 | ~2,503 | 2,503 | ~751 | 10,011 |

**B overhead:** Exports and re-parses 10,011 pairs × 2 GMMs = 20,022 GMM string parses, PLUS computes modeCount for ALL, PLUS HTTP transfer.

**A saves:** Only parses/touches 2,503 pairs worth of gmm2 bindings.

**C saves additionally:** Only computes JSD for ~751 pairs (70% pruned among F1/F2 survivors).

### 7.2 Expected Performance

| Scale | A (ms) | B (ms) | C (ms) | A/B | C/A | C/B |
|------:|-------:|-------:|-------:|----:|----:|----:|
| 105 | ~130 | ~350 | ~55 | 2.7× | 2.4× | 6.4× |
| 1K | ~1,300 | ~3,800 | ~500 | 2.9× | 2.6× | 7.6× |
| 5K | ~6,500 | ~20,000 | ~2,400 | 3.1× | 2.7× | 8.3× |
| 10K | ~13,000 | ~40,000 | ~4,800 | 3.1× | 2.7× | 8.3× |

### 7.3 Impact of Unimodal Fraction

At fixed scale (5K pairs), 10% JSD selectivity:

| Unimodal % | F1/F2 pass rate | A (ms) | B (ms) | C (ms) | A/B |
|-----------|----------------|-------:|-------:|-------:|----:|
| 20% | 64% | ~9,000 | ~22,000 | ~3,500 | 2.4× |
| 50% | 25% | ~6,500 | ~20,000 | ~2,400 | 3.1× |
| 80% | 4% | ~2,500 | ~18,000 | ~900 | 7.2× |

**Key insight:** A's advantage over B **grows** as early filters become more selective. At 80% unimodal, A is 7.2× faster than B because the engine eliminates 96% of pairs before ever computing JSD. B still exports and re-parses all 5,050 pairs.

---

## 8. Metrics

### Primary

| Metric | Definition |
|--------|-----------|
| Time_A | Median of 10 runs (ms) |
| Time_B | Median of 10 runs, total (fetch + parse + compute) (ms) |
| Time_C | Median of 10 runs (ms) |
| Speedup A/B | Time_B / Time_A |
| Speedup C/A | Time_A / Time_C |
| Speedup C/B | Time_B / Time_C |
| Recall_C | ResultCount_C / ResultCount_A (must be ≥ 99%) |

### Secondary

| Metric | Definition |
|--------|-----------|
| B: pairs exported | Total pairs before any filtering |
| B: GMMs re-parsed | 2 × pairs exported |
| B: survived F1/F2 | Pairs after modeCount filter |
| B: fetch_ms | HTTP export time |
| B: compute_ms | Filter + JSD time |
| A: pairs evaluated | F1/F2 survivors (engine pushdown count) |
| C: pruning rate | Among F1/F2 survivors, % pruned by L2–L4 |
| C: full JSD count | Pairs reaching L5 |

---

## 9. Output for Paper

### 9.1 Tables

**Table 1: Three-way comparison (Mix-50, 10% selectivity)**

| Scale | Pairs | A (ms) | B (ms) | C (ms) | A/B | C/A | C/B |
|------:|------:|-------:|-------:|-------:|----:|----:|----:|

**Table 2: Effect of early-filter selectivity (5K pairs, 10% JSD selectivity)**

| Unimodal % | F1/F2 pass rate | A (ms) | B (ms) | C (ms) | A/B |
|-----------|----------------|-------:|-------:|-------:|----:|

**Table 3: B overhead breakdown (5K pairs, Mix-50, 50% sel.)**

| Component | Time (ms) | Notes |
|-----------|----------:|-------|
| HTTP export | | All 5,050 pairs |
| JSON response parse | | |
| GMM literal re-parse | | 10,100 GMM strings |
| modeCount + filter | | Applied to all |
| JSD compute + filter | | Only F1/F2 survivors |
| **Total B** | | |
| **A** | | |
| **C** | | |

**Table 4: C pruning breakdown (among F1/F2 survivors)**

| Scale | Sel. | F1/F2 survivors | L2 pruned | L3 pruned | L5 full JSD | Prune rate |
|------:|-----:|----------------:|----------:|----------:|------------:|-----------:|

### 9.2 Charts

**Chart 1: Three-way comparison (log-log)**
- X: pairs, Y: time (ms)
- Lines: A, B, C
- Fixed: Mix-50, 10% selectivity

**Chart 2: Early-filter selectivity effect**
- X: unimodal fraction (20%, 50%, 80%)
- Bars: A, B, C (grouped)
- Fixed: 5K pairs, 10% selectivity
- Highlights: A/B gap widens as early filtering increases

**Chart 3: B overhead decomposition (stacked bar)**
- Segments: HTTP, JSON parse, GMM reparse, modeCount, JSD
- Comparison bars for A and C alongside

---

## 10. Paper Narrative

### Three key claims:

> **Claim 1 (A vs B — filter pushdown):** In-engine processing is X× faster than external processing using identical computation code, because the SPARQL pipeline evaluates cheap probabilistic filters early, avoiding expensive downstream operations for eliminated candidates. The advantage grows with the selectivity of early filters — at 80% early-filter elimination, the speedup reaches Y×.

> **Claim 2 (C vs A — pruning):** The dedicated SIMILARITYJOIN operator achieves an additional Z× speedup over naive in-engine filtering by resolving distribution pairs through cheap statistical checks before resorting to Monte Carlo JSD estimation.

> **Claim 3 (combined):** Together, pipeline optimization and pruning yield a W× total speedup over external processing, demonstrating that probabilistic operations benefit from deep integration into the query engine — not merely for computation speed, but for enabling query optimization strategies impossible in export-then-compute workflows.

### Why this is a stronger argument than v4:

- v4 tried to show A>B by making JSD expensive (d=3, K=5) → artificial
- v5 shows A>B by showing the engine does **less work** → fundamental architectural advantage
- This argument generalizes: ANY multi-step probabilistic query benefits from pipeline optimization, not just JSD queries

---

## 11. Implementation TODO

### P0: Recall Fix (same as v4)

| # | Task |
|---|------|
| 1 | Calibrate L2/L3 bounds with safety margins |
| 2 | Validate C recall ≥ 99% before benchmarking |

### P1: Dataset + External Baseline

| # | Task | File |
|---|------|------|
| 3 | Generate mixed-K datasets (20/50/80% unimodal) | `generate_exp2_mixed.py` |
| 4 | Implement Approach B in Java | `Exp2ExternalBaseline.java` |
| 5 | — HTTP fetch from Fuseki | |
| 6 | — Parse SPARQL JSON response | |
| 7 | — Re-parse GMM literals using GMMDatatype | |
| 8 | — Apply F1, F2, F3 using same ProbFunctions | |
| 9 | — Collect timing breakdown | |
| 10 | Export query (no filters) | `queries/exp2_export.sparql` |

### P2: Benchmark + Analysis

| # | Task | File |
|---|------|------|
| 11 | Calibration (pair counts, JSD distribution, θ values) | `exp2_calibrate.py` |
| 12 | Run all 45 configs × 3 approaches | `exp2_run_v5.sh` |
| 13 | Validation (recall check) | `exp2_validate.py` |
| 14 | Analysis + charts | `analyze_exp2_v5.py` |

---

## 12. Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| Jena doesn't push F1 down before the join | Verify with EXPLAIN; if not, restructure query into sub-SELECT |
| B's HTTP overhead too large, makes comparison unfair | Report breakdown; HTTP is realistic cost of external approach |
| modeCount filter not selective enough | Adjust unimodal fraction in data generation; or use prob:std filter |
| A and B result counts differ significantly | MC variance — verify within ±2%; use same random seed if possible |
| C recall < 99% after bound fix | Keep widening margins; worst case remove L3, rely on L2+L5 only |
| Experiment too slow at S5 with GT_10K | Reduce to GT_5K consistently across all approaches |
