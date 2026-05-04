#!/usr/bin/env python3
"""
Generate semantically equivalent Exp1 datasets by permuting GMM component order.

This script reads existing Exp1 probabilistic TTL files and rewrites every
`uq:gmmLiteral` so that mixture components are reordered while preserving the
same mathematical distribution. RDF structure is kept unchanged.

Default use is the representation-invariance experiment:
  - scale: E5
  - K:     3, 5, 10

Outputs:
  exp1_{scale}_K{k}_permuted.ttl
"""

import argparse
import json
import os
from typing import List

from rdflib import Graph, Literal, URIRef


GMM_DATATYPE = URIRef("http://example.org/ontology/uncertainty#gmmLiteral")


def non_identity_permutation(k: int, rng) -> List[int]:
    perm = list(range(k))
    if k <= 1:
        return perm
    rng.shuffle(perm)
    if perm == list(range(k)):
        perm = perm[1:] + perm[:1]
    return perm


def permute_gmm_json(gmm_json: str, rng) -> str:
    obj = json.loads(gmm_json)
    k = int(obj["n_components"])
    if k <= 1:
        return json.dumps(obj, separators=(",", ":"))

    perm = non_identity_permutation(k, rng)
    obj["weights"] = [obj["weights"][i] for i in perm]
    obj["means"] = [obj["means"][i] for i in perm]
    obj["covariances"] = [obj["covariances"][i] for i in perm]
    return json.dumps(obj, separators=(",", ":"))


def permute_graph(g: Graph, seed: int) -> Graph:
    import random

    rng = random.Random(seed)
    out = Graph()
    for prefix, ns in g.namespaces():
        out.bind(prefix, ns)

    for s, p, o in g:
        if isinstance(o, Literal) and o.datatype == GMM_DATATYPE:
            out.add((s, p, Literal(permute_gmm_json(str(o), rng), datatype=GMM_DATATYPE)))
        else:
            out.add((s, p, o))
    return out


def process_file(src: str, dst: str, seed: int) -> None:
    if not os.path.exists(src):
        print(f"  SKIP {src} — file not found")
        return
    g = Graph()
    g.parse(src, format="turtle")
    out = permute_graph(g, seed)
    os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
    out.serialize(destination=dst, format="turtle")
    print(f"  {os.path.basename(src)} -> {os.path.basename(dst)}  ({len(out)} triples)")


def main():
    parser = argparse.ArgumentParser(
        description="Generate Exp1 probabilistic datasets with permuted-but-equivalent GMM literals"
    )
    parser.add_argument("--scales", nargs="+", default=["E5"])
    parser.add_argument("--ks", nargs="+", type=int, default=[3, 5, 10])
    parser.add_argument("--input-dir", default="benchmark/data/exp1/component")
    parser.add_argument("--output-dir", default="benchmark/data/exp1/permutation")
    parser.add_argument("--suffix", default="_permuted")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    output_dir = args.output_dir or args.input_dir

    print("=== Exp1: Permuted GMM Dataset Generator ===")
    print(f"  Input  : {args.input_dir}")
    print(f"  Output : {output_dir}")
    print(f"  Scales : {args.scales}")
    print(f"  Ks     : {args.ks}")

    for scale in args.scales:
        for k in args.ks:
            src = os.path.join(args.input_dir, f"exp1_{scale}_K{k}.ttl")
            dst = os.path.join(output_dir, f"exp1_{scale}_K{k}{args.suffix}.ttl")
            process_file(src, dst, args.seed + hash((scale, k)) % 10_000)

    print("Done.")


if __name__ == "__main__":
    main()
