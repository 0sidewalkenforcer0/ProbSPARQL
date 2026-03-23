#!/usr/bin/env python3
"""
SimilarityJoin Benchmark Data Generator
========================================
Generates 4 TTL datasets (easy/medium/hard/mixed) with controlled JSD values
for benchmarking SIMILARITYJOIN with threshold θ=0.3.

Each dataset contains N LeftEntity + N RightEntity pairs.
JSD is controlled via rejection sampling with high-precision MC (50k samples).

JSD ranges (θ=0.3):
  Easy:   |JSD - 0.3| > 0.2   → JSD ∈ [0, 0.1) ∪ (0.5, log2]
  Medium: 0.05 < |JSD-0.3| ≤ 0.2  → JSD ∈ [0.1, 0.25) ∪ (0.35, 0.5]
  Hard:   |JSD - 0.3| ≤ 0.05  → JSD ∈ [0.25, 0.35]
  Mixed:  1/3 easy + 1/3 medium + 1/3 hard

Usage:
  pip install numpy
  python generate_sim_join_data.py [--n 100] [--output-dir ../data] [--seed 42]
"""

import argparse
import csv
import json
import math
import os
import sys
import time

import numpy as np


# ===========================================================================
# GMM Math Utilities (mirror Java JSDivergence logic)
# ===========================================================================


def sample_from_gmm(weights, means, covariances, n_samples, rng):
    """Sample from a 1D GMM. Returns array of shape (n_samples,)."""
    K = len(weights)
    components = rng.choice(K, size=n_samples, p=weights)
    samples = np.empty(n_samples)
    for i in range(n_samples):
        k = components[i]
        std = math.sqrt(covariances[k])
        samples[i] = rng.normal(means[k], std)
    return samples


def log_pdf_gmm(x, weights, means, covariances):
    """Compute log p(x) for a 1D GMM. x is array of shape (n,)."""
    K = len(weights)
    n = len(x)
    log_components = np.empty((K, n))
    for k in range(K):
        var = covariances[k]
        diff = x - means[k]
        log_components[k] = (
            math.log(weights[k])
            - 0.5 * math.log(2 * math.pi * var)
            - 0.5 * diff**2 / var
        )
    # log-sum-exp over components
    max_log = np.max(log_components, axis=0)
    return max_log + np.log(np.sum(np.exp(log_components - max_log), axis=0))


def compute_jsd_mc(w1, m1, c1, w2, m2, c2, n_samples, rng):
    """Compute JSD(P||Q) via Monte Carlo with mixture M = 0.5P + 0.5Q.
    All GMMs are 1D, K components each.
    w: weights, m: means (flat), c: variances (flat).
    """
    K1, K2 = len(w1), len(w2)
    # Build mixture M
    w_m = np.concatenate([0.5 * np.array(w1), 0.5 * np.array(w2)])
    m_m = np.concatenate([m1, m2])
    c_m = np.concatenate([c1, c2])

    half = n_samples // 2

    # KL(P || M)
    samples_p = sample_from_gmm(w1, m1, c1, half, rng)
    log_p = log_pdf_gmm(samples_p, w1, m1, c1)
    log_m_p = log_pdf_gmm(samples_p, w_m, m_m, c_m)
    kl_pm = np.mean(log_p - log_m_p)

    # KL(Q || M)
    samples_q = sample_from_gmm(w2, m2, c2, half, rng)
    log_q = log_pdf_gmm(samples_q, w2, m2, c2)
    log_m_q = log_pdf_gmm(samples_q, w_m, m_m, c_m)
    kl_qm = np.mean(log_q - log_m_q)

    jsd = 0.5 * kl_pm + 0.5 * kl_qm
    return max(0.0, jsd)  # Clamp numerical noise


def random_gmm_k2(rng, base_mean=10.0, mean_spread=2.0, var_range=(0.01, 0.2)):
    """Generate a random 1D GMM with K=2."""
    w1 = rng.uniform(0.2, 0.8)
    weights = np.array([w1, 1.0 - w1])
    means = np.array(
        [
            base_mean + rng.uniform(-mean_spread, mean_spread),
            base_mean + rng.uniform(-mean_spread, mean_spread),
        ]
    )
    covariances = np.array(
        [
            rng.uniform(*var_range),
            rng.uniform(*var_range),
        ]
    )
    return weights, means, covariances


def perturb_gmm(weights, means, covariances, offset_range, var_scale_range, rng):
    """Create a perturbed copy of a GMM."""
    K = len(weights)
    new_means = np.copy(means)
    new_covs = np.copy(covariances)
    for k in range(K):
        offset = rng.uniform(*offset_range) * rng.choice([-1, 1])
        new_means[k] += offset
        scale = rng.uniform(*var_scale_range)
        new_covs[k] *= scale
        new_covs[k] = max(new_covs[k], 1e-6)  # Floor
    return weights.copy(), new_means, new_covs


# ===========================================================================
# JSD Range Definitions (θ = 0.3)
# ===========================================================================

JSD_RANGES = {
    "easy_low": (0.0, 0.1),  # Almost identical → JSD ≈ 0
    "easy_high": (0.5, 0.693),  # Very different → JSD near log(2)
    "medium_low": (0.1, 0.25),  # Moderate, below threshold
    "medium_high": (0.35, 0.5),  # Moderate, above threshold
    "hard": (0.25, 0.35),  # Near threshold 0.3
}

# Perturbation parameters tuned to hit each JSD range
PERTURB_PARAMS = {
    "easy_low": {"offset_range": (0.0, 0.05), "var_scale_range": (0.95, 1.05)},
    "easy_high": {"offset_range": (3.0, 6.0), "var_scale_range": (1.5, 4.0)},
    "medium_low": {"offset_range": (0.3, 1.0), "var_scale_range": (0.8, 1.5)},
    "medium_high": {"offset_range": (1.5, 3.0), "var_scale_range": (1.2, 2.5)},
    "hard": {"offset_range": (0.8, 1.8), "var_scale_range": (0.9, 1.8)},
}


def generate_pair_for_range(jsd_low, jsd_high, perturb_key, rng, max_attempts=5000):
    """Generate a GMM pair whose true JSD falls in [jsd_low, jsd_high]."""
    params = PERTURB_PARAMS[perturb_key]
    verify_rng = np.random.default_rng(12345)  # Deterministic verification

    for _ in range(max_attempts):
        w1, m1, c1 = random_gmm_k2(rng)
        w2, m2, c2 = perturb_gmm(w1, m1, c1, **params, rng=rng)
        jsd = compute_jsd_mc(w1, m1, c1, w2, m2, c2, 50000, verify_rng)
        if jsd_low <= jsd <= jsd_high:
            return w1, m1, c1, w2, m2, c2, jsd

    raise RuntimeError(
        f"Failed to generate pair for JSD [{jsd_low}, {jsd_high}] "
        f"with perturb_key={perturb_key} after {max_attempts} attempts"
    )


def generate_easy_pair(rng):
    """Generate an easy pair: 50% chance low JSD, 50% chance high JSD."""
    if rng.random() < 0.5:
        return generate_pair_for_range(*JSD_RANGES["easy_low"], "easy_low", rng)
    else:
        return generate_pair_for_range(*JSD_RANGES["easy_high"], "easy_high", rng)


def generate_medium_pair(rng):
    """Generate a medium pair: 50% low-medium, 50% high-medium."""
    if rng.random() < 0.5:
        return generate_pair_for_range(*JSD_RANGES["medium_low"], "medium_low", rng)
    else:
        return generate_pair_for_range(*JSD_RANGES["medium_high"], "medium_high", rng)


def generate_hard_pair(rng):
    """Generate a hard pair: JSD ≈ 0.3."""
    return generate_pair_for_range(*JSD_RANGES["hard"], "hard", rng)


# ===========================================================================
# GMM → JSON (matching Java GMMValue format)
# ===========================================================================


def gmm_to_json(weights, means, covariances):
    """Convert 1D K=2 GMM to JSON string matching uq:gmmLiteral format.

    Java expects:
      {"K":2,"d":1,"covariance_type":"full",
       "weights":[w1,w2],"means":[[m1],[m2]],
       "covariances":[[[c1]],[[c2]]]}
    """
    K = len(weights)
    obj = {
        "K": K,
        "d": 1,
        "covariance_type": "full",
        "weights": [round(float(w), 6) for w in weights],
        "means": [[round(float(m), 6)] for m in means],
        "covariances": [[[round(float(c), 8)]] for c in covariances],
    }
    return json.dumps(obj, separators=(",", ":"))


# ===========================================================================
# TTL Writer
# ===========================================================================

TTL_HEADER = """\
@prefix ex:   <http://example.org/data/> .
@prefix prob: <http://example.org/prob#> .
@prefix uq:   <http://example.org/ontology/uncertainty#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

"""


def write_ttl(pairs, output_path, dataset_label):
    """Write GMM pairs to a TTL file.

    pairs: list of (w1, m1, c1, w2, m2, c2, jsd) tuples
    """
    with open(output_path, "w") as f:
        f.write(TTL_HEADER)
        f.write(f"# Dataset: {dataset_label}\n")
        f.write(f"# Pairs: {len(pairs)}\n")
        f.write(f"# Generated: {time.strftime('%Y-%m-%d %H:%M:%S')}\n\n")

        for i, (w1, m1, c1, w2, m2, c2, jsd) in enumerate(pairs):
            idx = f"{i + 1:03d}"
            left_json = gmm_to_json(w1, m1, c1)
            right_json = gmm_to_json(w2, m2, c2)

            # Escape quotes for TTL string literal
            left_escaped = left_json.replace("\\", "\\\\").replace('"', '\\"')
            right_escaped = right_json.replace("\\", "\\\\").replace('"', '\\"')

            f.write(f"ex:left_{idx} a prob:LeftEntity ;\n")
            f.write(f'    rdfs:label "Left Entity {idx}" ;\n')
            f.write(f'    prob:hasGMM "{left_escaped}"^^uq:gmmLiteral .\n\n')

            f.write(f"ex:right_{idx} a prob:RightEntity ;\n")
            f.write(f'    rdfs:label "Right Entity {idx}" ;\n')
            f.write(f'    prob:hasGMM "{right_escaped}"^^uq:gmmLiteral .\n\n')

    print(f"  Wrote {output_path} ({len(pairs)} pairs)")


# ===========================================================================
# Main
# ===========================================================================


def main():
    parser = argparse.ArgumentParser(
        description="Generate SimilarityJoin benchmark data"
    )
    parser.add_argument(
        "--n", type=int, default=100, help="Number of pairs per dataset"
    )
    parser.add_argument("--output-dir", default=None, help="Output directory")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    args = parser.parse_args()

    if args.output_dir is None:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        args.output_dir = os.path.join(script_dir, "..", "data")

    os.makedirs(args.output_dir, exist_ok=True)
    os.makedirs(os.path.join(args.output_dir, "..", "results"), exist_ok=True)

    N = args.n
    rng = np.random.default_rng(args.seed)

    print(f"Generating SimilarityJoin benchmark data (N={N}, seed={args.seed})")
    print(f"Output directory: {args.output_dir}")
    print()

    # Ground truth CSV
    gt_path = os.path.join(args.output_dir, "..", "results", "simjoin_ground_truth.csv")
    gt_rows = []

    datasets = {}

    # --- Easy dataset ---
    print("[1/4] Generating Easy dataset...")
    t0 = time.time()
    easy_pairs = []
    for i in range(N):
        pair = generate_easy_pair(rng)
        easy_pairs.append(pair)
        gt_rows.append(("easy", i + 1, pair[6]))
        if (i + 1) % 20 == 0:
            print(f"  {i + 1}/{N} pairs generated")
    datasets["easy"] = easy_pairs
    print(f"  Done in {time.time() - t0:.1f}s")

    # --- Medium dataset ---
    print("[2/4] Generating Medium dataset...")
    t0 = time.time()
    medium_pairs = []
    for i in range(N):
        pair = generate_medium_pair(rng)
        medium_pairs.append(pair)
        gt_rows.append(("medium", i + 1, pair[6]))
        if (i + 1) % 20 == 0:
            print(f"  {i + 1}/{N} pairs generated")
    datasets["medium"] = medium_pairs
    print(f"  Done in {time.time() - t0:.1f}s")

    # --- Hard dataset ---
    print("[3/4] Generating Hard dataset...")
    t0 = time.time()
    hard_pairs = []
    for i in range(N):
        pair = generate_hard_pair(rng)
        hard_pairs.append(pair)
        gt_rows.append(("hard", i + 1, pair[6]))
        if (i + 1) % 20 == 0:
            print(f"  {i + 1}/{N} pairs generated")
    datasets["hard"] = hard_pairs
    print(f"  Done in {time.time() - t0:.1f}s")

    # --- Mixed dataset (1/3 each) ---
    print("[4/4] Generating Mixed dataset...")
    t0 = time.time()
    mixed_pairs = []
    n_each = N // 3
    n_remainder = N - 3 * n_each
    indices = list(range(N))
    rng.shuffle(indices)

    generators = [generate_easy_pair, generate_medium_pair, generate_hard_pair]
    gen_labels = ["easy", "medium", "hard"]
    counts = [n_each, n_each, n_each + n_remainder]

    pair_idx = 0
    for gen_func, label, count in zip(generators, gen_labels, counts):
        for _ in range(count):
            pair = gen_func(rng)
            mixed_pairs.append(pair)
            gt_rows.append(("mixed", pair_idx + 1, pair[6]))
            pair_idx += 1

    # Shuffle mixed pairs
    perm = rng.permutation(len(mixed_pairs))
    mixed_pairs = [mixed_pairs[i] for i in perm]
    datasets["mixed"] = mixed_pairs
    print(f"  Done in {time.time() - t0:.1f}s")

    # --- Write TTL files ---
    print("\nWriting TTL files...")
    for name, pairs in datasets.items():
        path = os.path.join(args.output_dir, f"simjoin_{name}.ttl")
        write_ttl(pairs, path, f"SimilarityJoin {name.capitalize()} (N={N})")

    # --- Write ground truth CSV ---
    with open(gt_path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["Dataset", "PairIndex", "TrueJSD"])
        for row in gt_rows:
            writer.writerow([row[0], row[1], f"{row[2]:.6f}"])
    print(f"  Wrote {gt_path}")

    # --- Summary statistics ---
    print("\n=== Summary ===")
    for name, pairs in datasets.items():
        jsds = [p[6] for p in pairs]
        below = sum(1 for j in jsds if j <= 0.3)
        above = sum(1 for j in jsds if j > 0.3)
        print(
            f"  {name:8s}: N={len(pairs)}, "
            f"JSD range=[{min(jsds):.4f}, {max(jsds):.4f}], "
            f"mean={np.mean(jsds):.4f}, "
            f"below θ={below}, above θ={above}"
        )


if __name__ == "__main__":
    main()
