#!/usr/bin/env python3
"""
Generate Dirichlet Dataset for Experiment 4
=============================================
Creates a synthetic RDF/Turtle dataset with 100 components, each having:
  - cfm:hasMeasuredComposition  Dir(α_measured)
  - cfm:hasExpectedComposition  Dir(α_expected)

α vectors sampled uniformly from [0.5, 5.0] for k=4 dimensions.
The expected composition for each component uses a smoothed version
of the measured α (closer to uniform), enabling realistic JSD pairs.

Output: benchmark/data/exp4/exp4_dirichlet.ttl

JSON format: {"type":"dirichlet","k":4,"alpha":[2.5,1.0,3.0,0.5]}
Datatype URI: http://example.org/ontology/uncertainty#dirichletLiteral

Usage:
    python generate_dirichlet_dataset.py
    python generate_dirichlet_dataset.py --n 200 --k 6 --seed 123
"""
import argparse
import json
import os
import sys

try:
    import numpy as np
except ImportError:
    print("ERROR: numpy required.  pip install numpy")
    sys.exit(1)

try:
    from rdflib import Graph, Namespace, Literal, URIRef
    from rdflib.namespace import RDF, RDFS, XSD
except ImportError:
    print("ERROR: rdflib required.  pip install rdflib")
    sys.exit(1)

# ---------------------------------------------------------------------------
CFM  = Namespace("http://example.org/ontology/cfm#")
UQ   = Namespace("http://example.org/ontology/uncertainty#")
EX   = Namespace("http://example.org/data/")
DIR_DTYPE = URIRef("http://example.org/ontology/uncertainty#dirichletLiteral")

N_COMPONENTS = 100
K_DIM        = 4
SEED         = 42
ALPHA_LO     = 0.5
ALPHA_HI     = 5.0
# Expected α is a convex combination of measured α and uniform(1,1,...):
# α_expected = 0.6 * α_measured + 0.4 * (α₀/k) * ones
# where α₀ = sum(α_measured), scaled to keep similar concentration.
SMOOTH_FACTOR = 0.4


def make_dirichlet_literal(alpha: np.ndarray, k: int) -> Literal:
    obj = {"type": "dirichlet", "k": k, "alpha": [round(float(a), 6) for a in alpha]}
    return Literal(json.dumps(obj), datatype=DIR_DTYPE)


def generate(n: int, k: int, seed: int, output_path: str) -> None:
    rng = np.random.default_rng(seed)

    g = Graph()
    g.bind("cfm",  CFM)
    g.bind("uq",   UQ)
    g.bind("ex",   EX)
    g.bind("rdfs", RDFS)
    g.bind("xsd",  XSD)

    component_type      = CFM.Component
    has_measured        = CFM.hasMeasuredComposition
    has_expected        = CFM.hasExpectedComposition

    for i in range(1, n + 1):
        uri = EX[f"component_{i:06d}"]

        # Measured composition: α ∈ [0.5, 5.0]^k, random
        alpha_m = rng.uniform(ALPHA_LO, ALPHA_HI, size=k)

        # Expected composition: smoothed toward uniform
        alpha0    = alpha_m.sum()
        alpha_uni = np.full(k, alpha0 / k)
        alpha_e   = (1.0 - SMOOTH_FACTOR) * alpha_m + SMOOTH_FACTOR * alpha_uni

        g.add((uri, RDF.type,     component_type))
        g.add((uri, has_measured, make_dirichlet_literal(alpha_m, k)))
        g.add((uri, has_expected, make_dirichlet_literal(alpha_e, k)))

    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    g.serialize(destination=output_path, format="turtle")
    print(f"  Written {len(g)} triples → {output_path}")


def main():
    parser = argparse.ArgumentParser(description="Generate Exp4 Dirichlet dataset")
    parser.add_argument("--n",    type=int, default=N_COMPONENTS, help="Number of components")
    parser.add_argument("--k",    type=int, default=K_DIM,        help="Dirichlet dimension")
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--output-dir", default=None)
    args = parser.parse_args()

    script_dir   = os.path.dirname(os.path.realpath(__file__))
    project_root = os.path.realpath(os.path.join(script_dir, "../../.."))
    output_dir   = args.output_dir or os.path.join(project_root, "benchmark/data/exp4")
    output_path  = os.path.join(output_dir, "exp4_dirichlet.ttl")

    print("=== Exp4: Dirichlet Dataset Generator ===")
    print(f"  N components : {args.n}")
    print(f"  k dimensions : {args.k}")
    print(f"  Output       : {output_path}")
    generate(args.n, args.k, args.seed, output_path)
    print("Done.")


if __name__ == "__main__":
    main()
