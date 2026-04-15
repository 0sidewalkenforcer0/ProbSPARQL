#!/usr/bin/env python3
"""
generate_exp5.py — Generate persisted datasets for Exp5:
In-engine early filter vs post-processing late filter with OPTIONAL expansion.

Data model:
  ?gear :toothLength ?d .
  OPTIONAL {
    ?gear :ctMeasurement ?ctDist .
    ?gear :lightMeasurement ?lightDist .
  }

The left-side probabilistic filter is:
  FILTER(prob:cdf(?d, 9.8) >= 0.9)

This generator keeps the OPTIONAL side simple and stable:
  - each gear has at most one :ctMeasurement value
  - each gear has at most one :lightMeasurement value
  - a gear either has both measurements or has neither

Experimental difficulty is controlled by the left-side filter selectivity:
  a configurable fraction of gears is constructed to pass the CDF threshold,
  and the rest are constructed to fail it.
"""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path


NS = "http://example.org/exp5#"
NS_UQ = "http://example.org/ontology/uncertainty#"


def make_gmm_json(rng: random.Random, mean: float, var: float, k: int = 1) -> str:
    if k == 1:
        obj = {
            "n_components": 1,
            "dimensions": 1,
            "covariance_type": "diag",
            "weights": [1.0],
            "means": [[mean]],
            "covariances": [[var]],
        }
        return json.dumps(obj, separators=(",", ":"))

    weights = [rng.uniform(0.1, 1.0) for _ in range(k)]
    s = sum(weights)
    weights = [w / s for w in weights]
    means = [[mean + rng.uniform(-0.35, 0.35)] for _ in range(k)]
    covs = [[max(0.03, var * rng.uniform(0.7, 1.3))] for _ in range(k)]
    obj = {
        "n_components": k,
        "dimensions": 1,
        "covariance_type": "diag",
        "weights": weights,
        "means": means,
        "covariances": covs,
    }
    return json.dumps(obj, separators=(",", ":"))


def ttl_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def write_dataset(
    out_path: Path,
    n_gears: int,
    pass_frac: float,
    opt_present_frac: float,
    seed: int,
) -> None:
    rng = random.Random(seed)
    n_pass = int(round(n_gears * pass_frac))

    lines = [
        "@prefix : <http://example.org/exp5#> .",
        "@prefix uq: <http://example.org/ontology/uncertainty#> .",
        "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .",
        "",
    ]

    for i in range(1, n_gears + 1):
        gear = f":gear_{i:06d}"
        is_pass = i <= n_pass

        if is_pass:
            mean = rng.uniform(8.5, 9.0)
            var = rng.uniform(0.03, 0.08)
        else:
            mean = rng.uniform(10.2, 11.0)
            var = rng.uniform(0.05, 0.10)

        tooth = ttl_escape(make_gmm_json(rng, mean, var, k=1))
        lines.append(f"{gear} :toothLength \"{tooth}\"^^uq:gmmLiteral .")

        has_ct = False
        has_light = False
        if rng.random() < opt_present_frac:
            has_ct = True
            has_light = True

        if has_ct:
            ct_val = ttl_escape(
                make_gmm_json(rng, rng.uniform(8.0, 12.0), rng.uniform(0.04, 0.15), k=1)
            )
            lines.append(f"{gear} :ctMeasurement \"{ct_val}\"^^uq:gmmLiteral .")
        if has_light:
            light_val = ttl_escape(
                make_gmm_json(rng, rng.uniform(8.0, 12.0), rng.uniform(0.04, 0.15), k=1)
            )
            lines.append(f"{gear} :lightMeasurement \"{light_val}\"^^uq:gmmLiteral .")

        lines.append("")

    out_path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser(description="Generate Exp5 datasets")
    ap.add_argument("--output-dir", default="benchmark/data/exp5")
    ap.add_argument("--n-gears", type=int, default=5000)
    ap.add_argument("--pass-frac", type=float, default=0.3,
                    help="Fraction of gears intended to satisfy cdf(d, 9.8) >= 0.9")
    ap.add_argument("--opt-present-frac", type=float, default=0.7,
                    help="Fraction of gears with both OPTIONAL-side measurements")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--name", default="exp5_gears_5000")
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    ttl_path = out_dir / f"{args.name}.ttl"
    meta_path = out_dir / f"{args.name}_meta.json"

    if ttl_path.exists() and not args.force:
        print(f"SKIP  {ttl_path}")
        return

    write_dataset(
        ttl_path,
        n_gears=args.n_gears,
        pass_frac=args.pass_frac,
        opt_present_frac=args.opt_present_frac,
        seed=args.seed,
    )

    meta = {
        "dataset": args.name,
        "n_gears": args.n_gears,
        "pass_frac": args.pass_frac,
        "opt_present_frac": args.opt_present_frac,
        "neither_frac": max(0.0, 1.0 - args.opt_present_frac),
        "seed": args.seed,
        "ttl": str(ttl_path),
    }
    meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(f"WROTE {ttl_path}")
    print(f"WROTE {meta_path}")


if __name__ == "__main__":
    main()
