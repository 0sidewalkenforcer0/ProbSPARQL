#!/usr/bin/env python3
"""
Generate deterministic Exp1 datasets from the partitioned probabilistic source.

The new Exp1 probabilistic data already stores point estimates on measurements
via ``om:hasValue``. The deterministic counterpart therefore only needs to
remove the random-variable / distribution subgraph while leaving the rest of
the measurement structure intact.

Usage:
    python generate_exp1_component_deterministic.py
    python generate_exp1_component_deterministic.py --scales E1 E3
    python generate_exp1_component_deterministic.py --input-dir benchmark/data/exp1/component --output-dir benchmark/data/exp1/component
"""
import argparse
import json
import os
import sys

try:
    from rdflib import Graph, Namespace, URIRef
    from rdflib.namespace import RDF
except ImportError:
    print("ERROR: rdflib is required.  Install with: pip install rdflib")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Namespaces
# ---------------------------------------------------------------------------
CFM = Namespace("http://example.org/ontology/cfm#")
GMM_DATATYPE = URIRef("http://example.org/ontology/uncertainty#gmmLiteral")

# ---------------------------------------------------------------------------
# Scales to process by default
# ---------------------------------------------------------------------------
EXP1_SCALES = ["E1", "E3", "E5", "E7"]


def convert_graph(g: Graph) -> Graph:
    """Return a new graph with the RV/distribution subgraph removed."""
    out = Graph()
    for prefix, ns in g.namespaces():
        out.bind(prefix, ns)

    rv_nodes = set()
    for s, p, o in g.triples((None, CFM.representedBy, None)):
        rv_nodes.add(o)

    for s, p, o in g:
        if s in rv_nodes:
            continue
        if o in rv_nodes:
            continue
        out.add((s, p, o))

    return out


def process_scale(scale: str, input_dir: str, output_dir: str, k_source: int = 3) -> None:
    filename = f"exp1_{scale}_K{k_source}.ttl"
    out_name = f"exp1_{scale}_det.ttl"
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
    parser = argparse.ArgumentParser(description="Generate deterministic Exp1 datasets")
    parser.add_argument("--scales", nargs="+", default=EXP1_SCALES,
                        help="Exp1 scale labels to process, e.g. E1 E3 E5")
    parser.add_argument("--k-source", type=int, default=3, dest="k_source",
                        help="Which probabilistic K variant to use as source (default: 3)")
    parser.add_argument("--input-dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "exp1", "component"),
                        help="Directory containing source probabilistic TTL files")
    parser.add_argument("--output-dir", default=None,
                        help="Directory for output TTL files (defaults to input-dir)")
    args = parser.parse_args()

    input_dir  = os.path.realpath(args.input_dir)
    output_dir = os.path.realpath(args.output_dir) if args.output_dir else input_dir

    print("=== Deterministic Dataset Generator ===")
    print(f"  Source : {input_dir}")
    print(f"  Output : {output_dir}")
    print(f"  K src  : {args.k_source}")
    print()

    for scale in args.scales:
        process_scale(scale, input_dir, output_dir, k_source=args.k_source)

    print("\nDone.")


if __name__ == "__main__":
    main()
