#!/usr/bin/env python3
"""
Generate Histogram Variant Datasets (Exp 4 baseline)
=====================================================
Reads benchmark TTL files containing ``uq:gmmLiteral`` distributions and
writes new TTL files in which each GMM is replaced by a 1-D histogram
approximation encoded as ``uq:histLiteral``.

Preferred JSON schema expected by HistogramDatatype.java:
    {"dimensions":1, "edges":[[<double>, ...]], "weights":[<double>, ...]}

For each GMM:
  1. Draw ``SAMPLES`` Monte-Carlo samples from the 1-D mixture.
  2. Clip to [mean - 4σ, mean + 4σ] to avoid runaway bins.
  3. Build a uniform-width histogram with ``B`` bins.

Usage:
    python benchmark/scripts/Experiments4/generate_histogram_variants.py
    python benchmark/scripts/Experiments4/generate_histogram_variants.py --scales S1 S2 --bins 50
    python benchmark/scripts/Experiments4/generate_histogram_variants.py --bins 20 50 100  # multiple B values
"""
import argparse
import json
import os
import sys
from typing import List

try:
    import numpy as np
except ImportError:
    print("ERROR: numpy is required.  Install with: pip install numpy")
    sys.exit(1)

try:
    from rdflib import Graph, Namespace, Literal, URIRef
    from rdflib.namespace import RDF, XSD
except ImportError:
    print("ERROR: rdflib is required.  Install with: pip install rdflib")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Namespaces & datatype URIs
# ---------------------------------------------------------------------------
UQ = Namespace("http://example.org/ontology/uncertainty#")
GMM_DATATYPE  = URIRef("http://example.org/ontology/uncertainty#gmmLiteral")
HIST_DATATYPE = URIRef("http://example.org/ontology/uncertainty#histLiteral")

DEFAULT_SCALES  = ["S0", "S1", "S2", "S3", "S4"]
DEFAULT_BINS    = [50]
SAMPLES         = 10_000
RANDOM_SEED     = 42


# ---------------------------------------------------------------------------
# GMM → histogram
# ---------------------------------------------------------------------------

def gmm_to_histogram(json_str: str, B: int, rng: np.random.Generator) -> str:
    """Sample from a 1-D GMM and return a ``uq:histLiteral`` JSON string."""
    obj = json.loads(json_str)
    k = obj["n_components"]
    weights = np.asarray(obj["weights"], dtype=float)
    means   = np.asarray([m[0] for m in obj["means"]], dtype=float)

    # support both "diag" and "full" covariance storage
    cov_raw = obj["covariances"]
    if obj.get("covariance_type", "full") == "diag":
        stds = np.sqrt(np.asarray([c[0] for c in cov_raw], dtype=float))
    else:
        # full: each entry is a [[value]] matrix for d=1
        stds = np.sqrt(np.asarray([c[0][0] for c in cov_raw], dtype=float))

    # sample component assignments, then draw from each Gaussian
    components = rng.choice(k, size=SAMPLES, p=weights / weights.sum())
    samples = means[components] + stds[components] * rng.standard_normal(SAMPLES)

    # determine bin range from global mean ± 4σ (robust to outliers)
    global_mean = float(weights.dot(means) / weights.sum())
    global_var  = float((weights * (stds ** 2 + means ** 2)).sum() / weights.sum()
                        - global_mean ** 2)
    global_std  = max(float(np.sqrt(max(global_var, 1e-12))), 1e-6)
    lo = global_mean - 4.0 * global_std
    hi = global_mean + 4.0 * global_std

    counts, edges = np.histogram(samples, bins=B, range=(lo, hi))
    total = counts.sum()
    if total == 0:
        weights_out = np.full(B, 1.0 / B)
    else:
        weights_out = counts / total
    return json.dumps({"dimensions": 1,
                       "edges": [[round(float(x), 6) for x in edges.tolist()]],
                       "weights": [round(float(w), 6) for w in weights_out.tolist()]})


# ---------------------------------------------------------------------------
# Graph conversion
# ---------------------------------------------------------------------------

def convert_graph(g: Graph, B: int, rng: np.random.Generator) -> Graph:
    out = Graph()
    for prefix, ns in g.namespaces():
        out.bind(prefix, ns)
    out.bind("uq", UQ)

    has_dist = UQ.hasDistribution
    rv_type  = UQ.RandomVariable
    hist_type = UQ.HistogramVariable

    for s, p, o in g:
        if p == has_dist and hasattr(o, 'datatype') and o.datatype == GMM_DATATYPE:
            hist_json = gmm_to_histogram(str(o), B, rng)
            out.add((s, has_dist, Literal(hist_json, datatype=HIST_DATATYPE)))
        elif p == RDF.type and o == rv_type:
            out.add((s, RDF.type, hist_type))
        else:
            out.add((s, p, o))
    return out


# ---------------------------------------------------------------------------
# Per-scale processing
# ---------------------------------------------------------------------------

def process_scale(scale: str, B: int, rng: np.random.Generator,
                  input_dir: str, output_dir: str) -> None:
    filename = f"benchmark_{scale}.ttl"
    src = os.path.join(input_dir, filename)
    dst = os.path.join(output_dir, f"benchmark_{scale}_hist{B}.ttl")

    if not os.path.exists(src):
        print(f"  SKIP {filename} — not found")
        return

    print(f"  {filename} (B={B}) → benchmark_{scale}_hist{B}.ttl", end="", flush=True)
    g = Graph()
    g.parse(src, format="turtle")
    g_out = convert_graph(g, B, rng)
    os.makedirs(output_dir, exist_ok=True)
    g_out.serialize(destination=dst, format="turtle")
    print(f"  ({len(g_out)} triples)")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="Generate histogram dataset variants")
    parser.add_argument("--scales", nargs="+", default=DEFAULT_SCALES)
    parser.add_argument("--bins", nargs="+", type=int, default=DEFAULT_BINS,
                        help="Number of histogram bins (can specify multiple, e.g. 20 50 100)")
    parser.add_argument("--samples", type=int, default=SAMPLES,
                        help="MC samples drawn per GMM (default: 10000)")
    parser.add_argument("--seed", type=int, default=RANDOM_SEED)
    parser.add_argument("--input-dir",  default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "data"))
    parser.add_argument("--output-dir", default=None)
    args = parser.parse_args()

    input_dir  = os.path.realpath(args.input_dir)
    output_dir = os.path.realpath(args.output_dir) if args.output_dir else input_dir

    print("=== Histogram Dataset Generator ===")
    print(f"  Source  : {input_dir}")
    print(f"  Output  : {output_dir}")
    print(f"  B values: {args.bins}")
    print(f"  Samples : {args.samples}")
    print()

    rng = np.random.default_rng(args.seed)

    for B in args.bins:
        print(f"── B={B} ──────────────────────────────")
        for scale in args.scales:
            process_scale(scale, B, rng, input_dir, output_dir)

    print("\nDone.")


if __name__ == "__main__":
    main()
