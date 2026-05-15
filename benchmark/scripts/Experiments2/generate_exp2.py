#!/usr/bin/env python3
"""
generate_exp2.py — Pre-generate persisted datasets for Exp2.

Creates one Turtle file per (NPairs, unimodalFrac) configuration:
  exp2_npairs_<N>_uf_<UF>.ttl

Exp2 uses an Exp1-style measurement graph while keeping the original control
variables. Each synthetic benchmark sample is an independent angle grinder unit
with one crown gear and one tooth-length characteristic measured by CT and SL.
The DIVJOIN workload compares the CT distribution set against the SL
distribution set, so the target pair count is controlled as a bipartite
cross-product.
"""

from __future__ import annotations

import argparse
import json
import math
import random
from pathlib import Path


NS_EX = "http://example.org/data/"
NS_AG = "http://example.org/ontology/anglegrinder#"
NS_CFM = "http://example.org/ontology/cfm#"
NS_OM = "http://example.org/ontology/om#"
NS_UQ = "http://example.org/ontology/uncertainty#"
NS_BENCH = "http://example.org/benchmark/exp2#"

DEFAULT_NPAIRS = [5000]
DEFAULT_UF = [0.2, 0.5, 0.8]
K_UNIMODAL = 1
K_MULTIMODAL = 3


def next_double(rng: random.Random, lo: float, hi: float) -> float:
    return lo + rng.random() * (hi - lo)


def make_gmm_json(k: int, rng: random.Random, center: float) -> str:
    weights = [next_double(rng, 0.1, 1.0) for _ in range(k)]
    total = sum(weights)
    weights = [w / total for w in weights]
    means = [[next_double(rng, center - 1.2, center + 1.2)] for _ in range(k)]
    covs = [[[next_double(rng, 0.02, 0.5)]] for _ in range(k)]
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
    n = math.ceil(math.sqrt(n_pairs))
    n_unimodal = int(n * unimodal_frac)
    rng = random.Random(seed)
    dataset = path.stem

    lines = [
        "@prefix ex: <http://example.org/data/> .",
        "@prefix ag: <http://example.org/ontology/anglegrinder#> .",
        "@prefix cfm: <http://example.org/ontology/cfm#> .",
        "@prefix om: <http://example.org/ontology/om#> .",
        "@prefix uq: <http://example.org/ontology/uncertainty#> .",
        "@prefix bench: <http://example.org/benchmark/exp2#> .",
        "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .",
        "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
        "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .",
        "",
        f"ex:{dataset} rdf:type bench:Exp2BenchmarkDataset ;",
        f'    rdfs:label "Exp2 CT-vs-SL DIVJOIN dataset {dataset}" ;',
        f"    bench:targetPairs {n_pairs} ;",
        f"    bench:ctCount {n} ;",
        f"    bench:slCount {n} ;",
        f"    bench:actualPairs {n * n} ;",
        f"    bench:unimodalFraction {unimodal_frac:.1f} .",
        "",
    ]

    for i in range(1, n + 1):
        k = K_UNIMODAL if i <= n_unimodal else K_MULTIMODAL
        grinder = f"ex:exp2_grinder_{i:06d}"
        gear = f"ex:exp2_gear_{i:06d}"
        char = f"ex:exp2_gear_{i:06d}_toothchar_1"
        ct_measurement = f"ex:exp2_gear_{i:06d}_ct_measurement"
        sl_measurement = f"ex:exp2_gear_{i:06d}_sl_measurement"
        ct_rv = f"ex:exp2_gear_{i:06d}_ct_rv"
        sl_rv = f"ex:exp2_gear_{i:06d}_sl_rv"

        base_center = next_double(rng, 8.8, 10.6)
        ct_gmm = ttl_escape(make_gmm_json(k, rng, base_center))
        sl_gmm = ttl_escape(make_gmm_json(k, rng, base_center + next_double(rng, -0.7, 0.7)))

        lines.extend([
            f"{grinder} rdf:type ag:AngleGrinder ;",
            f"    ag:hasPart {gear} .",
            "",
            f"{gear} rdf:type ag:CrownGear ;",
            f"    cfm:hasLengthCharacteristic {char} .",
            "",
            f"{char} rdf:type cfm:MeasurableCharacteristics ;",
            f"    cfm:measuresCharacteristicBy {ct_measurement}, {sl_measurement} .",
            "",
            f"{ct_measurement} rdf:type ag:CTMeasurement ;",
            f"    cfm:representedBy {ct_rv} ;",
            f"    om:hasValue {base_center:.6f} .",
            f"{ct_rv} rdf:type cfm:RandomVariable ;",
            f'    cfm:hasDistribution "{ct_gmm}"^^uq:gmmLiteral .',
            "",
            f"{sl_measurement} rdf:type ag:SLMeasurement ;",
            f"    cfm:representedBy {sl_rv} ;",
            f"    om:hasValue {base_center:.6f} .",
            f"{sl_rv} rdf:type cfm:RandomVariable ;",
            f'    cfm:hasDistribution "{sl_gmm}"^^uq:gmmLiteral .',
        ])
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
