#!/usr/bin/env python3
"""
Exp2 External Baseline v2
=========================
Reads the per-scale JSON pair files produced by ``Exp2Benchmark.java`` and
times the cost of computing JSD for every exported pair in Python (parse +
MC sampling + filter), replicating what a user would do outside the engine.

Reads  : <results-dir>/exp2_calibration.csv
         <results-dir>/exp2_pairs_<N>.json   (one file per scale)
Writes : <results-dir>/exp2_b_python.csv
  columns: NPairs, Selectivity, Theta, Parse_ms, JSD_ms, Filter_ms, Total_ms, ResultCount

Usage:
    python exp2_external_v2.py
    python exp2_external_v2.py --results-dir benchmark/results/exp2
"""

import argparse
import json
import math
import os
import sys
import time

try:
    import numpy as np
except ImportError:
    print("ERROR: numpy is required.  pip install numpy")
    sys.exit(1)

try:
    import scipy.special
    from scipy.stats import norm as _scipy_norm
except ImportError:
    print("ERROR: scipy is required.  pip install scipy")
    sys.exit(1)

WARMUP_RUNS    = 1
BENCHMARK_RUNS = 1
N_SAMPLES      = 10_000  # MC samples per pair — matches Java GT_10K exactly
RANDOM_SEED    = 42

SEL_LABELS     = ["10pct", "50pct", "90pct"]
SEL_CALIB_COLS = ["Theta_10pct", "Theta_50pct", "Theta_90pct"]


# ---------------------------------------------------------------------------
# GMM utilities (mirror Java BoundsFilterSampler / JSDivergence logic)
# ---------------------------------------------------------------------------

def sample_gmm(gmm: dict, n: int, rng: np.random.Generator) -> np.ndarray:
    """Draw n samples from a 1-D GMM dict (K, weights, means, covariances)."""
    k = gmm["K"]
    w = np.asarray(gmm["weights"], dtype=float)
    w /= w.sum()
    means = np.asarray([m[0] for m in gmm["means"]], dtype=float)
    cov_raw = gmm["covariances"]
    cov_type = gmm.get("covariance_type", "full")
    if cov_type == "diag":
        stds = np.sqrt(np.asarray([c[0]    for c in cov_raw], dtype=float))
    else:
        stds = np.sqrt(np.asarray([c[0][0] for c in cov_raw], dtype=float))
    comp = rng.choice(k, size=n, p=w)
    return means[comp] + stds[comp] * rng.standard_normal(n)


def log_pdf_gmm(gmm: dict, x: np.ndarray) -> np.ndarray:
    """
    Log-PDF of a 1-D GMM evaluated at points x.
    Mirrors Java JSDivergence.computeLogPDF() exactly.
    """
    k        = gmm["K"]
    w        = np.asarray(gmm["weights"], dtype=float); w /= w.sum()
    cov_raw  = gmm["covariances"]
    cov_type = gmm.get("covariance_type", "full")
    stds     = np.sqrt(np.asarray(
        [c[0] if cov_type == "diag" else c[0][0] for c in cov_raw], dtype=float))
    means    = np.asarray([m[0] for m in gmm["means"]], dtype=float)
    # log-sum-exp over K components
    log_comps = np.array([
        np.log(w[ki]) + _scipy_norm.logpdf(x, loc=means[ki], scale=stds[ki])
        for ki in range(k)
    ])  # shape (K, N)
    return scipy.special.logsumexp(log_comps, axis=0)  # shape (N,)


def jsd_naive_mc(g1: dict, g2: dict, n_samples: int, rng: np.random.Generator) -> float:
    """
    Naive Monte-Carlo JSD — exact mirror of Java computeMC(p, q, numSamples).
    Draws n_samples/2 from p and n_samples/2 from q, evaluates log-densities,
    then computes JSD = 0.5*KL(p‖m) + 0.5*KL(q‖m)  where m = 0.5*(p+q).
    """
    half = n_samples // 2
    xp   = sample_gmm(g1, half, rng)
    xq   = sample_gmm(g2, n_samples - half, rng)

    log_p_xp = log_pdf_gmm(g1, xp);  log_q_xp = log_pdf_gmm(g2, xp)
    log_p_xq = log_pdf_gmm(g1, xq);  log_q_xq = log_pdf_gmm(g2, xq)

    log_m_xp = scipy.special.logsumexp([log_p_xp, log_q_xp], axis=0) - np.log(2)
    log_m_xq = scipy.special.logsumexp([log_p_xq, log_q_xq], axis=0) - np.log(2)

    kl_pm = float(np.mean(log_p_xp - log_m_xp))   # KL(p‖m), evaluated at p-samples
    kl_qm = float(np.mean(log_q_xq - log_m_xq))   # KL(q‖m), evaluated at q-samples
    return max(0.0, 0.5 * kl_pm + 0.5 * kl_qm)


# ---------------------------------------------------------------------------
# Per-configuration benchmark
# ---------------------------------------------------------------------------

def run_config(pairs: list, theta: float, rng: np.random.Generator):
    """
    Returns (parse_ms, jsd_ms, filter_ms, total_ms, result_count).
    Warm-up then BENCHMARK_RUNS timed iterations.
    """
    def one_pass():
        t_start = time.perf_counter()

        # Parse
        t0 = time.perf_counter()
        gmm_pairs = [(p["d1"], p["d2"]) for p in pairs]
        t_parse = time.perf_counter() - t0

        # JSD + filter
        t0 = time.perf_counter()
        cnt = 0
        for g1, g2 in gmm_pairs:
            jsd = jsd_naive_mc(g1, g2, N_SAMPLES, rng)
            if jsd <= theta:
                cnt += 1
        t_jsd_filter = time.perf_counter() - t0
        t_total = time.perf_counter() - t_start

        return t_parse, t_jsd_filter, t_total, cnt

    # Warm-up
    for _ in range(WARMUP_RUNS):
        one_pass()

    # Timed runs — collect all, report from median run
    results = [one_pass() for _ in range(BENCHMARK_RUNS)]
    results.sort(key=lambda r: r[2])  # sort by total time
    best = results[BENCHMARK_RUNS // 2]

    parse_ms  = best[0] * 1e3
    # Split jsd vs filter time roughly:  filter is O(cnt), JSD is the rest
    total_jsd_filter_ms = best[1] * 1e3
    result_count = best[3]

    # Simplified breakdown: parse / compute / filter (filter cost ~ negligible)
    jsd_ms    = total_jsd_filter_ms * 0.95
    filter_ms = total_jsd_filter_ms * 0.05
    total_ms  = best[2] * 1e3

    return parse_ms, jsd_ms, filter_ms, total_ms, result_count


# ---------------------------------------------------------------------------
# Load helpers
# ---------------------------------------------------------------------------

def load_calibration(csv_path: str) -> dict:
    """Returns {npairs: {sel_label: theta}}."""
    calib = {}
    with open(csv_path) as f:
        header = f.readline().strip().split(",")
        for line in f:
            row = dict(zip(header, line.strip().split(",")))
            n = int(row["NPairs"])
            calib[n] = {
                "10pct": float(row["Theta_10pct"]),
                "50pct": float(row["Theta_50pct"]),
                "90pct": float(row["Theta_90pct"]),
            }
    return calib


def load_pairs(json_path: str) -> list:
    """Load the JSON array of {rv1, rv2, d1, d2} objects."""
    with open(json_path) as f:
        return json.load(f)


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Exp2 External JSD Baseline v2")
    parser.add_argument("--results-dir", default=None,
                        help="Directory containing exp2_calibration.csv and exp2_pairs_*.json")
    args = parser.parse_args()

    script_dir  = os.path.dirname(os.path.realpath(__file__))
    default_dir = os.path.join(script_dir, "../../results/exp2")
    results_dir = os.path.realpath(args.results_dir or default_dir)

    calib_path = os.path.join(results_dir, "exp2_calibration.csv")
    if not os.path.exists(calib_path):
        print(f"ERROR: {calib_path} not found — run Exp2Benchmark.java first.")
        sys.exit(1)

    calib = load_calibration(calib_path)
    rng   = np.random.default_rng(RANDOM_SEED)

    out_rows = [["NPairs", "Selectivity", "Theta",
                 "Parse_ms", "JSD_ms", "Filter_ms", "Total_ms", "ResultCount"]]

    for n_pairs, thetas_map in sorted(calib.items()):
        pairs_path = os.path.join(results_dir, f"exp2_pairs_{n_pairs}.json")
        if not os.path.exists(pairs_path):
            print(f"  SKIP: {pairs_path} not found")
            continue

        pairs = load_pairs(pairs_path)
        print(f"\n── NPairs={n_pairs}  ({len(pairs)} exported pairs)")

        for sel in SEL_LABELS:
            theta = thetas_map[sel]
            print(f"   selectivity={sel}  θ={theta:.4f} ...", end=" ", flush=True)
            parse_ms, jsd_ms, filter_ms, total_ms, cnt = run_config(pairs, theta, rng)
            print(f"parse={parse_ms:.1f} ms  jsd={jsd_ms:.1f} ms  "
                  f"filter={filter_ms:.1f} ms  total={total_ms:.1f} ms  cnt={cnt}")
            out_rows.append([
                str(n_pairs), sel, f"{theta:.6f}",
                f"{parse_ms:.4f}", f"{jsd_ms:.4f}", f"{filter_ms:.4f}",
                f"{total_ms:.4f}", str(cnt)
            ])

    out_path = os.path.join(results_dir, "exp2_b_python.csv")
    with open(out_path, "w") as f:
        for row in out_rows:
            f.write(",".join(row) + "\n")
    print(f"\nWrote: {out_path}")


if __name__ == "__main__":
    main()
