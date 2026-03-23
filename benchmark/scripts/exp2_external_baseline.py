#!/usr/bin/env python3
"""
Exp 2: External JSD Baseline (Python / NumPy)
=============================================
Reads the ``exp2_pairs.json`` file exported by ``InEngineVsExternalBenchmark``
and times the cost of computing JSD for every pair using Monte-Carlo sampling
(5 000 samples per pair, matching the V1_MC sample budget).

Output CSV: benchmark/results/exp2_external.csv
  columns: NPairs, Theta, Approach, Median_ms, ResultCount

Combined with ``exp2_inengine.csv`` produced by the Java benchmark, the two
files together provide the full Exp 2 comparison.

Usage:
    python exp2_external_baseline.py
    python exp2_external_baseline.py --pairs exp2_pairs.json \\
                                     --output benchmark/results
"""
import argparse
import json
import os
import sys
import time
from typing import List, Tuple

try:
    import numpy as np
except ImportError:
    print("ERROR: numpy is required.  Install with: pip install numpy")
    sys.exit(1)

SAMPLES        = 5_000
WARMUP_RUNS    = 3
BENCHMARK_RUNS = 10
THRESHOLDS     = [0.1, 0.3, 0.5]
RANDOM_SEED    = 42


# ---------------------------------------------------------------------------
# GMM sampling
# ---------------------------------------------------------------------------

def sample_gmm(gmm: dict, n: int, rng: np.random.Generator) -> np.ndarray:
    """Draw n i.i.d. samples from a 1-D GMM dict."""
    k = gmm["K"]
    w = np.asarray(gmm["weights"], dtype=float)
    w /= w.sum()
    means = np.asarray([m[0] for m in gmm["means"]], dtype=float)
    cov_raw = gmm["covariances"]
    cov_type = gmm.get("covariance_type", "full")
    if cov_type == "diag":
        stds = np.sqrt(np.asarray([c[0] for c in cov_raw], dtype=float))
    else:
        stds = np.sqrt(np.asarray([c[0][0] for c in cov_raw], dtype=float))
    comp = rng.choice(k, size=n, p=w)
    return means[comp] + stds[comp] * rng.standard_normal(n)


def jsd_mc(s1: np.ndarray, s2: np.ndarray, bins: int = 100) -> float:
    """Estimate JSD(P‖Q) from two sample arrays using histogram density."""
    lo = min(s1.min(), s2.min())
    hi = max(s1.max(), s2.max())
    if hi <= lo:
        return 0.0
    p, _ = np.histogram(s1, bins=bins, range=(lo, hi), density=True)
    q, _ = np.histogram(s2, bins=bins, range=(lo, hi), density=True)
    eps = 1e-10
    p = p + eps;  q = q + eps
    m = 0.5 * (p + q)
    return 0.5 * np.sum(p * np.log(p / m)) + 0.5 * np.sum(q * np.log(q / m))


# ---------------------------------------------------------------------------
# Timing helper
# ---------------------------------------------------------------------------

def time_jsd_batch(pairs: List[Tuple[dict, dict]], theta: float,
                   rng: np.random.Generator) -> Tuple[float, int]:
    """Time computing JSD for all pairs and filtering by theta. Returns (median_ms, count)."""
    # Warm up
    for g1, g2 in pairs[:min(WARMUP_RUNS, len(pairs))]:
        s1 = sample_gmm(g1, SAMPLES, rng)
        s2 = sample_gmm(g2, SAMPLES, rng)
        jsd_mc(s1, s2)

    run_times = []
    for _ in range(BENCHMARK_RUNS):
        t0 = time.perf_counter()
        cnt = 0
        for g1, g2 in pairs:
            s1 = sample_gmm(g1, SAMPLES, rng)
            s2 = sample_gmm(g2, SAMPLES, rng)
            jsd = jsd_mc(s1, s2)
            if jsd <= theta:
                cnt += 1
        run_times.append((time.perf_counter() - t0) * 1000)

    run_times.sort()
    median_ms = run_times[BENCHMARK_RUNS // 2]
    return median_ms, cnt  # cnt is from last run


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="External JSD baseline for Exp 2")
    parser.add_argument("--pairs",  default=None,
                        help="Path to exp2_pairs.json (auto-detected if omitted)")
    parser.add_argument("--output", default=None,
                        help="Output directory (defaults to directory containing pairs file)")
    args = parser.parse_args()

    script_dir = os.path.dirname(os.path.realpath(__file__))

    # Locate pairs file
    if args.pairs:
        pairs_path = os.path.realpath(args.pairs)
    else:
        pairs_path = os.path.join(script_dir, "../results/exp2_pairs.json")
        if not os.path.exists(pairs_path):
            pairs_path = os.path.join(script_dir, "../../benchmark/results/exp2_pairs.json")

    if not os.path.exists(pairs_path):
        print(f"ERROR: exp2_pairs.json not found at {pairs_path}")
        print("Run InEngineVsExternalBenchmark first to generate it.")
        sys.exit(1)

    output_dir = args.output if args.output else os.path.dirname(pairs_path)
    os.makedirs(output_dir, exist_ok=True)

    print("=== Exp 2: External JSD Baseline ===")
    print(f"  Pairs file : {pairs_path}")
    print(f"  Samples    : {SAMPLES}")
    print(f"  Runs       : {BENCHMARK_RUNS}")
    print()

    with open(pairs_path) as f:
        raw_data = json.load(f)

    rng = np.random.default_rng(RANDOM_SEED)

    rows = [["NPairs", "Theta", "Approach", "Median_ms", "ResultCount"]]

    for n_pairs_str, pair_list in sorted(raw_data.items(), key=lambda x: int(x[0])):
        n_pairs = int(n_pairs_str)
        # Parse GMM dicts
        pairs: List[Tuple[dict, dict]] = [
            (p["d1"], p["d2"]) for p in pair_list
        ]
        print(f"  nPairs={n_pairs}  ({len(pairs)} actual pairs)")
        for theta in THRESHOLDS:
            median_ms, cnt = time_jsd_batch(pairs, theta, rng)
            print(f"    θ={theta}  EXTERNAL  {median_ms:.2f} ms  ({cnt} results)")
            rows.append([str(n_pairs), f"{theta:.2f}", "EXTERNAL",
                         f"{median_ms:.4f}", str(cnt)])

    out_path = os.path.join(output_dir, "exp2_external.csv")
    with open(out_path, "w") as f:
        for row in rows:
            f.write(",".join(row) + "\n")
    print(f"\nWritten: {out_path}")


if __name__ == "__main__":
    main()
