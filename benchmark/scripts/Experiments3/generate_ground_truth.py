#!/usr/bin/env python3
"""
Ground Truth JSD Generator for Experiment 3.3
==============================================
Reads the four SimJoin TTL datasets (easy / medium / hard / mixed),
parses aligned GMM pairs (left_NNN ↔ right_NNN), and computes a
high-accuracy JSD estimate for each pair using GT_10K Monte Carlo.

Output: simjoin_ground_truth.csv
  columns: dataset, pair_idx, jsd

This CSV is the mandatory prerequisite for SelectivityBenchmark (Exp 3.3),
which needs it to compute per-θ classification-accuracy metrics.

Usage (from project root):
  python3 benchmark/scripts/Experiments3/generate_ground_truth.py \\
      [--data-dir  benchmark/data] \\
      [--output    benchmark/results/exp3_full/simjoin_ground_truth.csv] \\
      [--n-samples 10000] \\
      [--seed      42]
"""

import argparse
import csv
import json
import math
import os
import re
import sys
import time

import numpy as np

# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args():
    p = argparse.ArgumentParser(description="Generate ground-truth JSD CSV for Exp 3.3")
    p.add_argument("--data-dir",  default="benchmark/data",
                   help="Directory containing simjoin_*.ttl files")
    p.add_argument("--output",    default="benchmark/results/exp3_full/simjoin_ground_truth.csv",
                   help="Output CSV path")
    p.add_argument("--n-samples", type=int, default=10_000,
                   help="MC sample count per pair (default: 10000)")
    p.add_argument("--seed",      type=int, default=42)
    return p.parse_args()

# ---------------------------------------------------------------------------
# GMM math utilities (mirror Java JSDivergence logic)
# ---------------------------------------------------------------------------

def log_pdf_gmm(x: np.ndarray, weights, means, variances) -> np.ndarray:
    """Vectorised log p(x) for a 1-D GMM.
    x: (n,)  weights/means/variances: (K,)
    """
    K = len(weights)
    n = len(x)
    log_comps = np.empty((K, n))
    for k in range(K):
        diff = x - means[k]
        log_comps[k] = (
            math.log(weights[k])
            - 0.5 * math.log(2.0 * math.pi * variances[k])
            - 0.5 * diff ** 2 / variances[k]
        )
    max_log = np.max(log_comps, axis=0)
    return max_log + np.log(np.sum(np.exp(log_comps - max_log), axis=0))


def sample_gmm(weights, means, variances, n: int, rng) -> np.ndarray:
    """Draw n samples from a 1-D K-component GMM."""
    K = len(weights)
    comps = rng.choice(K, size=n, p=weights)
    out = np.empty(n)
    for k in range(K):
        mask = comps == k
        cnt = int(mask.sum())
        if cnt:
            out[mask] = rng.normal(means[k], math.sqrt(variances[k]), cnt)
    return out


def compute_jsd_mc(w1, m1, v1, w2, m2, v2, n_samples: int, rng) -> float:
    """Compute JSD(P||Q) by Monte Carlo with mixture M = 0.5 P + 0.5 Q."""
    K1, K2 = len(w1), len(w2)

    # Build mixture M
    wm = np.concatenate([0.5 * np.array(w1), 0.5 * np.array(w2)])
    mm = np.concatenate([m1, m2])
    vm = np.concatenate([v1, v2])

    half = n_samples // 2

    # KL(P || M)
    sp = sample_gmm(w1, m1, v1, half, rng)
    kl_pm = float(np.mean(log_pdf_gmm(sp, w1, m1, v1) - log_pdf_gmm(sp, wm, mm, vm)))

    # KL(Q || M)
    sq = sample_gmm(w2, m2, v2, half, rng)
    kl_qm = float(np.mean(log_pdf_gmm(sq, w2, m2, v2) - log_pdf_gmm(sq, wm, mm, vm)))

    return max(0.0, 0.5 * kl_pm + 0.5 * kl_qm)

# ---------------------------------------------------------------------------
# TTL parser (minimal — extracts only prob:hasGMM literals)
# ---------------------------------------------------------------------------

# Matches lines like:
#   ex:left_001 a prob:LeftEntity ; prob:hasGMM "..." .
# or subsequent continuation lines.
_TTL_GMM_RE = re.compile(
    r'(ex|<http[^>]+>)?:?(left|right)_(\d+)\s[^;]*;[^"]*"([^"]+)"\^\^',
    re.DOTALL,
)

def parse_ttl_gmms(ttl_path: str):
    """Return two dicts: left_idx → gmm_dict, right_idx → gmm_dict."""
    left_map  = {}
    right_map = {}

    with open(ttl_path, encoding="utf-8") as f:
        text = f.read()

    # Extract all subject + GMM literal blocks via regex on the full file text.
    # Pattern: URI containing "left_NNN" or "right_NNN" followed by gmmLiteral.
    block_re = re.compile(
        r'(ex:(?:left|right)_\d+)\s.*?prob:hasGMM\s+"((?:[^"\\]|\\.)*?)"\^\^',
        re.DOTALL,
    )
    for m in block_re.finditer(text):
        uri  = m.group(1)   # e.g. ex:left_001
        lex  = m.group(2)   # JSON string

        # unescape any backslash-escaped quotes in the literal
        lex = lex.replace('\\"', '"')

        idx_match = re.search(r'(left|right)_(\d+)', uri)
        if not idx_match:
            continue
        side = idx_match.group(1)
        idx  = int(idx_match.group(2))

        try:
            gmm = json.loads(lex)
        except json.JSONDecodeError as e:
            print(f"  WARNING: JSON parse failed for {uri}: {e}", file=sys.stderr)
            continue

        if side == "left":
            left_map[idx]  = gmm
        else:
            right_map[idx] = gmm

    return left_map, right_map


def gmm_arrays(gmm: dict):
    """Return (weights, means_1d, variances_1d) as numpy arrays."""
    weights   = np.array(gmm["weights"], dtype=float)
    # means is list of [value] — unwrap
    means     = np.array([m[0] for m in gmm["means"]], dtype=float)
    # covariances is list of [[value]] — unwrap
    variances = np.array([c[0][0] for c in gmm["covariances"]], dtype=float)
    return weights, means, variances

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

DATASETS = ["easy", "medium", "hard", "mixed"]


def main():
    args = parse_args()
    rng = np.random.default_rng(args.seed)

    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)

    total_pairs = 0
    rows = []

    for dataset in DATASETS:
        ttl_path = os.path.join(args.data_dir, f"simjoin_{dataset}.ttl")
        if not os.path.exists(ttl_path):
            print(f"SKIP (not found): {ttl_path}", file=sys.stderr)
            continue

        print(f"Processing dataset '{dataset}' ({ttl_path}) ...", flush=True)
        t0 = time.time()

        left_map, right_map = parse_ttl_gmms(ttl_path)
        common_indices = sorted(set(left_map) & set(right_map))
        n_pairs = len(common_indices)
        print(f"  Found {n_pairs} aligned pairs", flush=True)

        for i, idx in enumerate(common_indices):
            w1, m1, v1 = gmm_arrays(left_map[idx])
            w2, m2, v2 = gmm_arrays(right_map[idx])

            jsd = compute_jsd_mc(w1, m1, v1, w2, m2, v2, args.n_samples, rng)
            rows.append({"dataset": dataset, "pair_idx": idx, "jsd": jsd})

            if (i + 1) % 50 == 0:
                elapsed = time.time() - t0
                print(f"  [{i+1}/{n_pairs}] pair {idx:03d}  JSD={jsd:.6f}  "
                      f"({elapsed:.1f}s elapsed)", flush=True)

        total_pairs += n_pairs
        elapsed = time.time() - t0
        print(f"  Done: {n_pairs} pairs in {elapsed:.1f}s", flush=True)

    # Write CSV
    with open(args.output, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["dataset", "pair_idx", "jsd"])
        writer.writeheader()
        writer.writerows(rows)

    print(f"\nWrote {total_pairs} rows → {args.output}")


if __name__ == "__main__":
    main()
