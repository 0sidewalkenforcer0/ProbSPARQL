# Experiment 3 — Overnight Completion Plan

**Goal:** Fix remaining bugs, validate, and launch full Exp 3 run before sleep.

---

## Step 1: Fix V5_ADAPTIVE Threshold Bug (15 min)

### Problem

`ClassificationAccuracyBenchmark.java` and `MultiMethodConvergenceBenchmark.java` construct `AdaptiveSampler` with hardcoded threshold `0.1` instead of using the actual calibrated θ (which is `0.3` for Exp 3.1).

```java
// BROKEN — hardcoded 0.1
new AdaptiveSampler(0.1)

// CORRECT — use actual theta from calibration
new AdaptiveSampler(theta)  // theta = 0.3 for Exp 3.1
```

### Files to Fix

| File | What to Change |
|------|---------------|
| `ClassificationAccuracyBenchmark.java` | Find `new AdaptiveSampler(0.1)` → replace `0.1` with the `theta` variable |
| `MultiMethodConvergenceBenchmark.java` | Same fix |

### How to Find the Lines

```bash
grep -n "AdaptiveSampler" src/main/java/org/apache/jena/probsparql/ClassificationAccuracyBenchmark.java
grep -n "AdaptiveSampler" src/main/java/org/apache/jena/probsparql/MultiMethodConvergenceBenchmark.java
```

### Fix Pattern

```java
// In ClassificationAccuracyBenchmark.java:
// Find the line where theta/THETA is defined (likely near top or in the loop)
// Then find: new AdaptiveSampler(0.1)
// Replace with: new AdaptiveSampler(theta)

// In MultiMethodConvergenceBenchmark.java:
// Same pattern — the convergence benchmark uses a fixed hard pair
// whose ground truth JSD ≈ 0.3, so theta should be 0.3
// Find: new AdaptiveSampler(0.1)
// Replace with: new AdaptiveSampler(THETA)  // where THETA = 0.3
```

---

## Step 2: Rebuild (2 min)

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
PATH="$(/usr/libexec/java_home -v 21)/bin:$PATH" \
mvn -q package -DskipTests && echo "BUILD OK"
```

If BUILD OK → proceed. If not → fix compilation errors.

---

## Step 3: Quick Validation Smoke Test (5 min)

Run the smoke test to verify both V3 and V5 are now working:

```bash
SKIP_BUILD=1 bash benchmark/scripts/Experiments3/run_exp3_smoke.sh 2>&1 | tee exp3_smoke_final.log
```

### Acceptance Criteria (check in the output)

| Method | Exp 3.1 Easy F1 | Exp 3.1 Hard F1 | Exp 3.2 Converges? |
|--------|----------------|----------------|-------------------|
| V1_MC | ≥ 0.95 | ≥ 0.80 | Yes (error decreases with N) |
| V2_STRAT | ≥ 0.95 | ≥ 0.80 | Yes |
| V3_SPRT | ≥ 0.85 | ≥ 0.70 | Yes |
| V5_ADAPTIVE | ≥ 0.90 | ≥ 0.75 | Yes (NOT flat at 0.151) |

**Critical check for V5:** In Exp 3.2 convergence output, V5_ADAPTIVE's estimated JSD must NOT be 0.151 at all N values. It should show values near the ground truth (≈0.3) and improve with N.

If all checks pass → proceed to full run.
If V5 still shows 0.151 → there's another place where 0.1 is hardcoded. Search:
```bash
grep -rn "0\.1" src/main/java/org/apache/jena/probsparql/AdaptiveSampler.java
grep -rn "0\.1" src/main/java/org/apache/jena/probsparql/PrunedSimJoinEvaluator.java
```

---

## Step 4: Launch Full Experiment 3 Run (overnight)

### 4.1 Create Full Run Script

If `run_exp3_full.sh` doesn't exist yet, create it by copying `run_exp3_smoke.sh` and changing parameters:

```bash
cp benchmark/scripts/Experiments3/run_exp3_smoke.sh \
   benchmark/scripts/Experiments3/run_exp3_full.sh
```

Then edit `run_exp3_full.sh` to change:

| Parameter | Smoke Value | Full Value |
|-----------|------------|------------|
| Exp 3.1 REPEAT | 2 | **10** |
| Exp 3.1 pairs per difficulty | (whatever smoke uses) | **200** |
| Exp 3.2 REPETITIONS | 2 | **50** |
| Exp 3.2 N values | 100,1000 | **100,500,1000,5000,10000,50000,100000** |
| Exp 3.3 ITERATIONS | 2 | **10** |
| Exp 3.3 --limit-graphs | 10 | **50** |
| WARMUP (all) | 1 | **3** |

### 4.2 Launch with nohup

```bash
nohup bash benchmark/scripts/Experiments3/run_exp3_full.sh \
  > benchmark/results/exp3_full/exp3_full_run.log 2>&1 &

echo $! > exp3_full.pid
echo "Exp 3 full run launched. PID: $(cat exp3_full.pid)"
```

### 4.3 Estimated Runtime

| Sub-Exp | Estimate |
|---------|---------|
| Exp 3.1 (4 methods × 4 difficulties × 10 reps × 200 pairs) | ~30 min |
| Exp 3.2 (4 methods × 7 N values × 50 reps × 1 pair) | ~25 min |
| Exp 3.3 (4 methods × 6 θ × 4 datasets × 10 reps × 1225 pairs) | ~2.5 hours |
| **Total** | **~3.5 hours** |

Should complete well before morning.

### 4.4 Monitor (optional, before sleep)

```bash
# Check if still running
ps aux | grep -E "ClassificationAccuracy|Convergence|Selectivity" | grep -v grep

# Check progress
tail -f benchmark/results/exp3_full/exp3_full_run.log
```

---

## Step 5: Morning Check (next day)

### 5.1 Verify Completion

```bash
# Check if process finished
cat exp3_full.pid | xargs ps -p

# Check last lines of log
tail -20 benchmark/results/exp3_full/exp3_full_run.log

# Check output files exist
ls -la benchmark/results/exp3_full/*.csv
ls -la benchmark/results/exp3_full/*.png
```

### 5.2 Quick Sanity Checks

```bash
# Exp 3.1: V5_ADAPTIVE accuracy on Hard should be > 75%
grep "V5_ADAPTIVE.*hard" benchmark/results/exp3_full/exp3_accuracy.csv

# Exp 3.2: V5_ADAPTIVE should NOT show flat 0.151
grep "V5_ADAPTIVE" benchmark/results/exp3_full/exp3_convergence.csv | head

# Exp 3.3: All methods should show result counts that vary with θ
grep "V5_ADAPTIVE" benchmark/results/exp3_full/exp3_selectivity.csv | head
```

### 5.3 If Something Failed

Check the log for the failure point:
```bash
grep -i "error\|exception\|fail\|crash" benchmark/results/exp3_full/exp3_full_run.log
```

Common issues:
- OOM → add `-Xmx4g` to Java commands in the script
- Timeout on Exp 3.3 → reduce `--limit-graphs` from 50 to 30
- V5 still broken → check if the fix was actually compiled into the jar

---

## Summary: Tonight's Sequence

```
1. Fix AdaptiveSampler(0.1) → AdaptiveSampler(theta) in 2 files     [15 min]
2. mvn package                                                        [2 min]
3. Run smoke test, verify V5 is fixed                                 [5 min]
4. If smoke passes → launch full run with nohup                       [1 min]
5. Go to sleep                                                        [8 hours]
6. Morning: check results                                             [10 min]
```

Total active time: ~25 minutes.
