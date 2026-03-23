#!/usr/bin/env python3
"""
Generate Deterministic Counterpart Datasets (Exp 1 baseline)
=============================================================
Reads benchmark TTL files that contain ``uq:gmmLiteral`` distributions and
writes new TTL files in which every ``uq:hasDistribution`` triple is replaced
by a ``uq:hasPointValue`` triple carrying the GMM's weighted-mean as an
``xsd:double``.  The resulting datasets are structurally identical in all
other triples, making them suitable DET baselines for overhead comparison.

A ``uq:DeterministicVar`` class assertion is also added to each random-variable
node that originally appeared as ``uq:RandomVariable``.

Usage:
    python generate_dataset_deterministic.py
    python generate_dataset_deterministic.py --scales S1 S2
    python generate_dataset_deterministic.py --input-dir ../data --output-dir ../data
"""
import argparse
import json
import os
import re
import sys

try:
    from rdflib import Graph, Namespace, Literal, URIRef
    from rdflib.namespace import RDF, RDFS, XSD, OWL
except ImportError:
    print("ERROR: rdflib is required.  Install with: pip install rdflib")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Namespaces
# ---------------------------------------------------------------------------
UQ = Namespace("http://example.org/ontology/uncertainty#")
GMM_DATATYPE = URIRef("http://example.org/ontology/uncertainty#gmmLiteral")

# ---------------------------------------------------------------------------
# Scales to process by default
# ---------------------------------------------------------------------------
DEFAULT_SCALES = ["S0", "S1", "S2", "S3", "S4"]
EXP1_SCALES   = ["E1", "E2", "E3", "E4", "E5", "E6", "E7"]


def gmm_mean(json_str: str) -> float:
    """Return the scalar weighted mean E[X] of a 1-D GMM JSON literal."""
    obj = json.loads(json_str)
    weights = obj["weights"]
    means = obj["means"]  # list of [value] lists
    return sum(w * mu[0] for w, mu in zip(weights, means))


def convert_graph(g: Graph) -> Graph:
    """Return a new graph where GMM distributions are replaced by point values."""
    out = Graph()
    # Copy all prefixes
    for prefix, ns in g.namespaces():
        out.bind(prefix, ns)
    out.bind("uq", UQ)

    has_dist = UQ.hasDistribution
    has_val  = UQ.hasPointValue
    rv_type  = UQ.RandomVariable
    det_type = UQ.DeterministicVar

    for s, p, o in g:
        if p == has_dist and hasattr(o, 'datatype') and o.datatype == GMM_DATATYPE:
            # Replace distribution literal with point-value double
            mean_val = gmm_mean(str(o))
            out.add((s, has_val, Literal(round(mean_val, 6), datatype=XSD.double)))
        elif p == RDF.type and o == rv_type:
            # Replace RandomVariable type with DeterministicVar
            out.add((s, RDF.type, det_type))
        else:
            out.add((s, p, o))

    return out


def process_scale(scale: str, input_dir: str, output_dir: str,
                  prefix: str = "benchmark", k_source: int = 3) -> None:
    if prefix == "exp1":
        filename = f"exp1_{scale}_K{k_source}.ttl"
        out_name = f"exp1_{scale}_det.ttl"
    else:
        filename = f"benchmark_{scale}.ttl"
        out_name = f"benchmark_{scale}_det.ttl"
    src = os.path.join(input_dir, filename)
    dst = os.path.join(output_dir, out_name)

    if not os.path.exists(src):
        print(f"  SKIP {filename} — not found")
        return

    print(f"  {filename} → {out_name}", end="", flush=True)
    g = Graph()
    g.parse(src, format="turtle")
    g_out = convert_graph(g)
    os.makedirs(output_dir, exist_ok=True)
    g_out.serialize(destination=dst, format="turtle")
    print(f"  ({len(g_out)} triples)")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate deterministic benchmark datasets")
    parser.add_argument("--scales", nargs="+", default=DEFAULT_SCALES,
                        help="Scale labels to process, e.g. S1 S2 S3 or E1 E2 E3 E4 E5 E6 E7")
    parser.add_argument("--prefix", default="benchmark", choices=["benchmark", "exp1"],
                        help="Filename prefix: 'benchmark' (default) uses benchmark_{scale}.ttl; "
                             "'exp1' uses exp1_{scale}_K{k_source}.ttl → exp1_{scale}_det.ttl")
    parser.add_argument("--k-source", type=int, default=3, dest="k_source",
                        help="Which K variant to use as source for exp1 prefix (default: 3)")
    parser.add_argument("--input-dir", default=os.path.join(os.path.dirname(__file__), "../data"),
                        help="Directory containing source TTL files")
    parser.add_argument("--output-dir", default=None,
                        help="Directory for output TTL files (defaults to input-dir)")
    args = parser.parse_args()

    input_dir  = os.path.realpath(args.input_dir)
    output_dir = os.path.realpath(args.output_dir) if args.output_dir else input_dir

    print("=== Deterministic Dataset Generator ===")
    print(f"  Source : {input_dir}")
    print(f"  Output : {output_dir}")
    if args.prefix == "exp1":
        print(f"  Prefix : exp1  (K source = {args.k_source})")
    print()

    for scale in args.scales:
        process_scale(scale, input_dir, output_dir,
                      prefix=args.prefix, k_source=args.k_source)

    print("\nDone.")


if __name__ == "__main__":
    main()
