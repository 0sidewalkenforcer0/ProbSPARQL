#!/usr/bin/env python3
"""
Generate Cross-Type Distribution Pairs for Experiment 4 (Sub-experiment 4.3)
==============================================================================
Creates two TTL files used to evaluate the sample-based cross-type JSD fallback:

  1. exp4_crosstype_gmm_hist.ttl  — 100 entity pairs, each pair has:
       :e_NNN cfm:hasDistA ?gmm        (uq:gmmLiteral,   K=3)
       :e_NNN cfm:hasDistB ?hist       (uq:histLiteral, bins+weights)
       The histogram is derived from the same underlying GMM (same source dist),
       so the true GMM↔Hist JSD should be close to zero.

  2. exp4_crosstype_dir_hist.ttl  — 100 entity pairs, each pair has:
       :e_NNN cfm:hasDistA ?dir        (uq:dirichletLiteral, alphas length = 4)
       :e_NNN cfm:hasDistB ?hist_1d    (uq:histLiteral, bins+weights)
       The histogram is built from 1-D marginal samples of the Dirichlet.

The ground-truth JSD for each pair is recorded in a companion CSV:
  benchmark/data/exp4/exp4_crosstype_gt.csv
  Columns: entity, pair_type, pair_idx, gmm_jsd, hist_jsd, gt_gmm_hist_jsd

Usage:
    python generate_crosstype_pairs.py
    python generate_crosstype_pairs.py --n 200 --bins 100 --seed 42
"""
import argparse
import json
import math
import os
import sys
import csv

try:
    import numpy as np
except ImportError:
    print("ERROR: numpy required.  pip install numpy")
    sys.exit(1)

try:
    from rdflib import Graph, Namespace, Literal, URIRef
    from rdflib.namespace import RDF, RDFS
except ImportError:
    print("ERROR: rdflib required.  pip install rdflib")
    sys.exit(1)

# ---------------------------------------------------------------------------
CFM      = Namespace("http://example.org/ontology/cfm#")
UQ       = Namespace("http://example.org/ontology/uncertainty#")
EX       = Namespace("http://example.org/data/")
GMM_DT   = URIRef("http://example.org/ontology/uncertainty#gmmLiteral")
HIST_DT  = URIRef("http://example.org/ontology/uncertainty#histLiteral")
DIR_DT   = URIRef("http://example.org/ontology/uncertainty#dirichletLiteral")

N_PAIRS  = 100
BINS     = 100
K_GMM    = 3
K_DIR    = 4
MC_SAMPLES = 10_000
SEED     = 42


# ---------------------------------------------------------------------------
# Helpers: sampling and histogram construction
# ---------------------------------------------------------------------------

def sample_gmm(weights, means, stds, n, rng):
    weights = np.asarray(weights) / np.array(weights).sum()
    comps   = rng.choice(len(weights), size=n, p=weights)
    return np.asarray(means)[comps] + np.asarray(stds)[comps] * rng.standard_normal(n)


def samples_to_hist_json(samples, B):
    g_mean = float(np.mean(samples))
    g_std  = max(float(np.std(samples)), 1e-6)
    lo = g_mean - 4.0 * g_std
    hi = g_mean + 4.0 * g_std
    counts, edges = np.histogram(samples, bins=B, range=(lo, hi))
    total = counts.sum()
    if total == 0:
        weights_out = np.full(B, 1.0 / B)
    else:
        weights_out = counts / total
    rounded_weights = rounded_probabilities(weights_out)
    return json.dumps({"bins": [round(float(x), 6) for x in edges.tolist()],
                       "weights": rounded_weights})


def rounded_probabilities(weights, decimals: int = 6):
    arr = np.asarray(weights, dtype=float)
    if arr.size == 1:
        return [1.0]
    rounded = np.round(arr, decimals)
    diff = round(1.0 - float(rounded.sum()), decimals)
    rounded[-1] = round(float(rounded[-1] + diff), decimals)
    return [float(x) for x in rounded.tolist()]


def sample_dirichlet(alpha, n, rng):
    """Draw n samples from Dir(alpha), return n x k array."""
    alpha = np.asarray(alpha)
    gammas = rng.gamma(shape=alpha, scale=1.0, size=(n, len(alpha)))
    sums   = gammas.sum(axis=1, keepdims=True)
    return gammas / sums


def hist_jsd_exact(h1_json, h2_json):
    """Exact discrete JSD between two histograms over the same bin grid."""
    h1 = json.loads(h1_json)
    h2 = json.loads(h2_json)
    p = np.asarray(h1["weights"], dtype=float)
    q = np.asarray(h2["weights"], dtype=float)
    bins1 = np.asarray(h1["bins"], dtype=float)
    bins2 = np.asarray(h2["bins"], dtype=float)
    if len(p) != len(q) or len(bins1) != len(bins2) or not np.allclose(bins1, bins2):
        return float("nan")
    m = 0.5 * (p + q)
    def kl(a, b):
        mask = (a > 0) & (b > 0)
        return float(np.sum(a[mask] * np.log(a[mask] / b[mask])))
    return max(0.0, 0.5 * kl(p, m) + 0.5 * kl(q, m))


# ---------------------------------------------------------------------------
# Generate GMM ↔ Hist pairs
# ---------------------------------------------------------------------------

def generate_gmm_hist_pairs(n, B, rng, output_path, gt_rows):
    g = Graph()
    g.bind("cfm", CFM); g.bind("uq", UQ); g.bind("ex", EX)

    for i in range(1, n + 1):
        uri = EX[f"crossGH_{i:04d}"]

        # Random K-component GMM (1-D, diag)
        raw_w  = rng.uniform(0.5, 2.0, size=K_GMM)
        weights = (raw_w / raw_w.sum()).tolist()
        means   = rng.uniform(10.0, 20.0, size=K_GMM).tolist()
        vars_   = rng.uniform(0.01, 0.5,  size=K_GMM).tolist()
        stds    = [math.sqrt(v) for v in vars_]

        gmm_obj = {"n_components": K_GMM, "dimensions": 1, "covariance_type": "diag",
                   "weights": [round(w, 6) for w in weights],
                   "means":   [[round(m, 6)] for m in means],
                   "covariances": [[round(v, 6)] for v in vars_]}
        gmm_json = json.dumps(gmm_obj)

        # Histogram derived from same GMM
        samples  = sample_gmm(weights, means, stds, MC_SAMPLES, rng)
        hist_json = samples_to_hist_json(samples, B)

        # Also build a "reference" histogram from a second independent draw
        # so we can record a same-GMM baseline JSD ≈ 0
        samples2  = sample_gmm(weights, means, stds, MC_SAMPLES, rng)
        hist2_json = samples_to_hist_json(samples2, B)
        ref_jsd    = hist_jsd_exact(hist_json, hist2_json)

        g.add((uri, RDF.type,         CFM.CrossTypePair))
        g.add((uri, CFM.hasDistA,     Literal(gmm_json,  datatype=GMM_DT)))
        g.add((uri, CFM.hasDistB,     Literal(hist_json, datatype=HIST_DT)))
        g.add((uri, CFM.pairType,     Literal("gmm_hist")))
        g.add((uri, CFM.pairIndex,    Literal(i)))

        gt_rows.append({"entity": f"crossGH_{i:04d}", "pair_type": "gmm_hist",
                        "pair_idx": i, "ref_same_type_jsd": round(ref_jsd, 6)})

    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    g.serialize(destination=output_path, format="turtle")
    print(f"  gmm↔hist: {len(g)} triples → {output_path}")


# ---------------------------------------------------------------------------
# Generate Dir ↔ Hist pairs
# ---------------------------------------------------------------------------

def generate_dir_hist_pairs(n, B, rng, output_path, gt_rows):
    g = Graph()
    g.bind("cfm", CFM); g.bind("uq", UQ); g.bind("ex", EX)

    for i in range(1, n + 1):
        uri = EX[f"crossDH_{i:04d}"]

        # Random Dirichlet
        alpha = rng.uniform(0.5, 5.0, size=K_DIR).tolist()
        dir_obj  = {"alphas": [round(a, 6) for a in alpha]}
        dir_json = json.dumps(dir_obj)

        # 1-D marginal histogram of dimension 0
        dir_samples = sample_dirichlet(alpha, MC_SAMPLES, rng)
        hist_json   = samples_to_hist_json(dir_samples[:, 0], B)

        # Reference: second draw → baseline JSD for marginal dim-0
        dir_samples2 = sample_dirichlet(alpha, MC_SAMPLES, rng)
        hist2_json   = samples_to_hist_json(dir_samples2[:, 0], B)
        ref_jsd      = hist_jsd_exact(hist_json, hist2_json)

        g.add((uri, RDF.type,         CFM.CrossTypePair))
        g.add((uri, CFM.hasDistA,     Literal(dir_json,  datatype=DIR_DT)))
        g.add((uri, CFM.hasDistB,     Literal(hist_json, datatype=HIST_DT)))
        g.add((uri, CFM.pairType,     Literal("dir_hist")))
        g.add((uri, CFM.pairIndex,    Literal(i)))

        gt_rows.append({"entity": f"crossDH_{i:04d}", "pair_type": "dir_hist",
                        "pair_idx": i, "ref_same_type_jsd": round(ref_jsd, 6)})

    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    g.serialize(destination=output_path, format="turtle")
    print(f"  dir↔hist: {len(g)} triples → {output_path}")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Generate Exp4 cross-type JSD pairs")
    parser.add_argument("--n",    type=int, default=N_PAIRS)
    parser.add_argument("--bins", type=int, default=BINS)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--output-dir", default=None)
    args = parser.parse_args()

    script_dir   = os.path.dirname(os.path.realpath(__file__))
    project_root = os.path.realpath(os.path.join(script_dir, "../../.."))
    output_dir   = args.output_dir or os.path.join(project_root, "benchmark/data/exp4")

    rng     = np.random.default_rng(args.seed)
    gt_rows = []

    print("=== Exp4: Cross-Type Pair Generator ===")
    print(f"  N pairs: {args.n}  Bins: {args.bins}  Output: {output_dir}")

    generate_gmm_hist_pairs(args.n, args.bins, rng,
                            os.path.join(output_dir, "exp4_crosstype_gmm_hist.ttl"),
                            gt_rows)
    generate_dir_hist_pairs(args.n, args.bins, rng,
                            os.path.join(output_dir, "exp4_crosstype_dir_hist.ttl"),
                            gt_rows)

    # Write ground-truth CSV
    gt_path = os.path.join(output_dir, "exp4_crosstype_gt.csv")
    with open(gt_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["entity", "pair_type", "pair_idx",
                                               "ref_same_type_jsd"])
        writer.writeheader()
        writer.writerows(gt_rows)
    print(f"  Ground-truth CSV → {gt_path}")
    print("Done.")


if __name__ == "__main__":
    main()
