#!/usr/bin/env python3
"""
generate_exp2.py — Pre-generate persisted datasets for Exp2.

Creates one Turtle file per (NPairs, unimodalFrac) configuration:
  exp2_npairs_<N>_uf_<UF>.ttl

The data model matches Exp2Benchmark expectations:
  ?rv a uq:RandomVariable ;
      uq:hasDistribution "<gmm-json>"^^uq:gmmLiteral .
"""

from __future__ import annotations

import argparse
import json
import math
import random
from pathlib import Path


NS_EX = "http://example.org/data/"
NS_UQ = "http://example.org/ontology/uncertainty#"

DEFAULT_NPAIRS = [10000]
DEFAULT_UF = [0.2, 0.5, 0.8]
K_UNIMODAL = 1
K_MULTIMODAL = 3


def next_double(rng: random.Random, lo: float, hi: float) -> float:
    return lo + rng.random() * (hi - lo)


def make_gmm_json(k: int, rng: random.Random) -> str:
    weights = [next_double(rng, 0.1, 1.0) for _ in range(k)]
    total = sum(weights)
    weights = [w / total for w in weights]
    means = [[next_double(rng, 5.0, 15.0)] for _ in range(k)]
    covs = [[[next_double(rng, 0.1, 2.0)]] for _ in range(k)]
    obj = {
        "n_components": k,
        "dimensions": 1,
        "covariance_type": "full",
        "weights": weights,
        "means": means,
        "covariances": covs,
    }
    return json.dumps(obj, separators=(",", ":"))


def ttl_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def write_dataset(path: Path, n_pairs: int, unimodal_frac: float, seed: int) -> None:
    n = math.ceil((1.0 + math.sqrt(1.0 + 8.0 * n_pairs)) / 2.0)
    n_unimodal = int(n * unimodal_frac)
    rng = random.Random(seed)

    lines = [
        "@prefix ex: <http://example.org/data/> .",
        "@prefix uq: <http://example.org/ontology/uncertainty#> .",
        "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .",
        "",
    ]

    for i in range(1, n + 1):
        k = K_UNIMODAL if i <= n_unimodal else K_MULTIMODAL
        gmm = ttl_escape(make_gmm_json(k, rng))
        lines.append(
            f"ex:rv5_{i} rdf:type uq:RandomVariable ;\n"
            f'    uq:hasDistribution "{gmm}"^^uq:gmmLiteral .'
        )
        lines.append("")

    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate persisted Exp2 v5 datasets")
    parser.add_argument(
        "--output-dir",
        default="benchmark/data/exp2",
        help="Directory to write exp2_*.ttl files",
    )
    parser.add_argument(
        "--npairs",
        nargs="+",
        type=int,
        default=DEFAULT_NPAIRS,
        help="Target pair counts",
    )
    parser.add_argument(
        "--unimodal-fracs",
        nargs="+",
        type=float,
        default=DEFAULT_UF,
        help="Fractions of K=1 entities",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Base random seed",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite existing TTL files",
    )
    args = parser.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    for n_pairs in args.npairs:
        for uf in args.unimodal_fracs:
            uf_label = f"{uf:.1f}".replace(".", "p")
            path = out_dir / f"exp2_npairs_{n_pairs}_uf_{uf_label}.ttl"
            if path.exists() and not args.force:
                print(f"SKIP  {path}")
                continue
            derived_seed = args.seed + 31 * n_pairs + round(uf * 1000.0)
            write_dataset(path, n_pairs, uf, derived_seed)
            print(f"WROTE {path} (seed={derived_seed})")


if __name__ == "__main__":
    main()
