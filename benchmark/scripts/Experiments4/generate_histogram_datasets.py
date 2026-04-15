#!/usr/bin/env python3
"""
Generate Histogram Datasets for Experiment 4 (from exp1 K=3 GMM files)
=======================================================================
Reads exp1 E3/E5/E7 K3 GMM TTL files and converts each GMM literal into
a histogram literal with B=50 and B=100 bins.

Conversion:
  1. Parse the gmm literal JSON (K=3, d=1, diag covariance)
  2. Draw SAMPLES=10 000 Monte-Carlo samples from the 1-D mixture
  3. Clip to [mean − 4σ, mean + 4σ]
  4. Build B bins → JSON: {"bins":[...],"weights":[...]}
  5. Replace the GMM lexical form with a histogram lexical form on the same
     cfm:hasDistribution edge, preserving the surrounding Exp1 RDF structure
     (measurement -> RV -> distribution).

Output: benchmark/data/exp4/exp4_E{3,5,7}_hist_B{50,100}.ttl

Usage:
    python generate_histogram_datasets.py
    python generate_histogram_datasets.py --scales E3 E5 --bins 50
    python generate_histogram_datasets.py --input-dir benchmark/data/exp1/main
"""
import argparse
import json
import os
import sys

try:
    import numpy as np
except ImportError:
    print("ERROR: numpy is required.  pip install numpy")
    sys.exit(1)

try:
    from rdflib import Graph, Namespace, Literal, URIRef
    from rdflib.namespace import RDF
except ImportError:
    print("ERROR: rdflib is required.  pip install rdflib")
    sys.exit(1)

# ---------------------------------------------------------------------------
CFM          = Namespace("http://example.org/ontology/cfm#")
GMM_DTYPE    = URIRef("http://example.org/ontology/uncertainty#gmmLiteral")
HIST_DTYPE   = URIRef("http://example.org/ontology/uncertainty#histLiteral")

DEFAULT_SCALES = ["E3", "E5", "E7"]
DEFAULT_BINS   = [50, 100]
SAMPLES        = 10_000
SEED           = 42


# ---------------------------------------------------------------------------
def gmm_range(json_str: str) -> tuple:
    """Return the theoretical [lo, hi] for a 1-D GMM: min(mean_k - 4σ_k) … max(mean_k + 4σ_k)."""
    obj = json.loads(json_str)
    means    = np.asarray([m[0] for m in obj["means"]], dtype=float)
    cov_raw  = obj["covariances"]
    cov_type = obj.get("covariance_type", "full")
    if cov_type == "diag":
        stds = np.sqrt(np.asarray([c[0] for c in cov_raw], dtype=float))
    else:
        stds = np.sqrt(np.asarray([c[0][0] for c in cov_raw], dtype=float))
    return float(np.min(means - 4.0 * stds)), float(np.max(means + 4.0 * stds))


def gmm_json_to_histogram(json_str: str, B: int, rng: np.random.Generator,
                           lo: float, hi: float) -> str:
    """Convert a 1-D K-component GMM JSON string to a histogram JSON string."""
    obj = json.loads(json_str)
    k        = obj["n_components"]
    weights  = np.asarray(obj["weights"], dtype=float)
    weights /= weights.sum()
    means    = np.asarray([m[0] for m in obj["means"]], dtype=float)

    cov_raw    = obj["covariances"]
    cov_type   = obj.get("covariance_type", "full")
    if cov_type == "diag":
        stds = np.sqrt(np.asarray([c[0] for c in cov_raw], dtype=float))
    else:
        stds = np.sqrt(np.asarray([c[0][0] for c in cov_raw], dtype=float))

    comps   = rng.choice(k, size=SAMPLES, p=weights)
    samples = means[comps] + stds[comps] * rng.standard_normal(SAMPLES)

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


def convert_graph(g: Graph, B: int, rng: np.random.Generator) -> Graph:
    out = Graph()
    for prefix, ns in g.namespaces():
        out.bind(prefix, ns)
    out.bind("cfm", CFM)

    has_dist = CFM.hasDistribution

    # ── Pass 1: compute global bin range across all GMM literals ─────────────
    global_lo, global_hi = float("inf"), float("-inf")
    gmm_count = 0
    for s, p, o in g:
        if p == has_dist and hasattr(o, "datatype") and o.datatype == GMM_DTYPE:
            lo, hi = gmm_range(str(o))
            global_lo = min(global_lo, lo)
            global_hi = max(global_hi, hi)
            gmm_count += 1
    if gmm_count == 0:
        global_lo, global_hi = 0.0, 1.0  # fallback (should not happen)

    # ── Pass 2: build histograms with shared [global_lo, global_hi] ──────────
    for s, p, o in g:
        if p == has_dist and hasattr(o, "datatype") and o.datatype == GMM_DTYPE:
            hist_json = gmm_json_to_histogram(str(o), B, rng, global_lo, global_hi)
            out.add((s, has_dist, Literal(hist_json, datatype=HIST_DTYPE)))
        else:
            out.add((s, p, o))
    return out


def process(scale: str, B: int, rng: np.random.Generator,
            input_dir: str, output_dir: str) -> None:
    src = os.path.join(input_dir, f"exp1_{scale}_K3.ttl")
    dst = os.path.join(output_dir, f"exp4_{scale}_hist_B{B}.ttl")

    if not os.path.exists(src):
        print(f"  SKIP {src} — file not found")
        return

    print(f"  {os.path.basename(src)} (B={B}) → {os.path.basename(dst)} ...", end="", flush=True)
    g = Graph()
    g.parse(src, format="turtle")
    g_out = convert_graph(g, B, rng)
    os.makedirs(output_dir, exist_ok=True)
    g_out.serialize(destination=dst, format="turtle")
    print(f"  ({len(g_out)} triples)")


def main():
    global SAMPLES
    parser = argparse.ArgumentParser(description="Generate Exp4 histogram datasets from GMM exp1 files")
    parser.add_argument("--scales", nargs="+", default=DEFAULT_SCALES)
    parser.add_argument("--bins",   nargs="+", type=int, default=DEFAULT_BINS)
    parser.add_argument("--samples", type=int, default=SAMPLES)
    parser.add_argument("--seed",    type=int, default=SEED)
    parser.add_argument("--input-dir",  default=None)
    parser.add_argument("--output-dir", default=None)
    args = parser.parse_args()

    script_dir  = os.path.dirname(os.path.realpath(__file__))
    project_root = os.path.realpath(os.path.join(script_dir, "../../.."))

    input_dir  = args.input_dir  or os.path.join(project_root, "benchmark/data/exp1/main")
    output_dir = args.output_dir or os.path.join(project_root, "benchmark/data/exp4")

    SAMPLES = args.samples
    rng = np.random.default_rng(args.seed)

    print("=== Exp4: Histogram Dataset Generator ===")
    print(f"  Input  : {input_dir}")
    print(f"  Output : {output_dir}")
    print(f"  Scales : {args.scales}")
    print(f"  Bins   : {args.bins}")
    print(f"  Samples: {SAMPLES}")

    for scale in args.scales:
        for B in args.bins:
            process(scale, B, rng, input_dir, output_dir)

    print("Done.")


if __name__ == "__main__":
    main()
