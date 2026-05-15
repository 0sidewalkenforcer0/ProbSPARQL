#!/usr/bin/env python3
"""
generate_exp5.py — Generate persisted datasets for Exp5:
In-engine early filter vs post-processing late filter with OPTIONAL expansion.

Data model:
  ?gear a ag:CrownGear ;
        cfm:hasLengthCharacteristic ?char .
  ?char cfm:measuresCharacteristicBy ?ctMeasurement .
  ?ctMeasurement a ag:CTMeasurement ;
                 cfm:representedBy ?rv .
  ?rv cfm:hasDistribution ?d .

  OPTIONAL {
    ?char cfm:measuresCharacteristicBy ?slMeasurement, ?lightMeasurement .
    ...
  }

The left-side probabilistic filter is:
  FILTER(prob:cdf(?d, 9.8) >= 0.9)

This generator keeps the OPTIONAL side simple and stable:
  - each gear has exactly one CT distribution used by the left-side filter
  - each gear has at most one SL and one light/laser follow-up distribution
  - a gear either has both follow-up measurements or has neither

Experimental difficulty is controlled by the left-side filter selectivity:
  a configurable fraction of gears is constructed to pass the CDF threshold,
  and the rest are constructed to fail it.
"""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path


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
        "@prefix ex: <http://example.org/data/> .",
        "@prefix ag: <http://example.org/ontology/anglegrinder#> .",
        "@prefix cfm: <http://example.org/ontology/cfm#> .",
        "@prefix om: <http://example.org/ontology/om#> .",
        "@prefix uq: <http://example.org/ontology/uncertainty#> .",
        "@prefix bench: <http://example.org/benchmark/exp5#> .",
        "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .",
        "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
        "",
        f"ex:{out_path.stem} rdf:type bench:Exp5BenchmarkDataset ;",
        f'    rdfs:label "Exp5 early-filter dataset {out_path.stem}" ;',
        f"    bench:gearCount {n_gears} ;",
        f"    bench:passFraction {pass_frac} ;",
        f"    bench:optionalPresentFraction {opt_present_frac} .",
        "",
    ]

    for i in range(1, n_gears + 1):
        grinder = f"ex:exp5_grinder_{i:06d}"
        gear = f"ex:exp5_gear_{i:06d}"
        char = f"ex:exp5_gear_{i:06d}_toothchar_1"
        ct_measurement = f"ex:exp5_gear_{i:06d}_ct_measurement"
        ct_rv = f"ex:exp5_gear_{i:06d}_ct_rv"
        is_pass = i <= n_pass

        if is_pass:
            mean = rng.uniform(8.5, 9.0)
            var = rng.uniform(0.03, 0.08)
        else:
            mean = rng.uniform(10.2, 11.0)
            var = rng.uniform(0.05, 0.10)

        tooth = ttl_escape(make_gmm_json(rng, mean, var, k=1))
        lines.extend([
            f"{grinder} rdf:type ag:AngleGrinder ;",
            f"    ag:hasPart {gear} .",
            "",
            f"{gear} rdf:type ag:CrownGear ;",
            f"    cfm:hasLengthCharacteristic {char} .",
            "",
            f"{char} rdf:type cfm:MeasurableCharacteristics ;",
            f"    cfm:measuresCharacteristicBy {ct_measurement} .",
            "",
            f"{ct_measurement} rdf:type ag:CTMeasurement ;",
            f"    cfm:representedBy {ct_rv} ;",
            f"    om:hasValue {mean:.6f} .",
            f"{ct_rv} rdf:type cfm:RandomVariable ;",
            f'    cfm:hasDistribution "{tooth}"^^uq:gmmLiteral .',
        ])

        has_followup = False
        if rng.random() < opt_present_frac:
            has_followup = True

        if has_followup:
            sl_measurement = f"ex:exp5_gear_{i:06d}_sl_measurement"
            light_measurement = f"ex:exp5_gear_{i:06d}_light_measurement"
            sl_rv = f"ex:exp5_gear_{i:06d}_sl_rv"
            light_rv = f"ex:exp5_gear_{i:06d}_light_rv"
            sl_val = ttl_escape(
                make_gmm_json(rng, rng.uniform(8.0, 12.0), rng.uniform(0.04, 0.15), k=1)
            )
            light_val = ttl_escape(
                make_gmm_json(rng, rng.uniform(8.0, 12.0), rng.uniform(0.04, 0.15), k=1)
            )
            lines.extend([
                f"{char} cfm:measuresCharacteristicBy {sl_measurement}, {light_measurement} .",
                f"{sl_measurement} rdf:type ag:SLMeasurement ;",
                f"    cfm:representedBy {sl_rv} .",
                f"{sl_rv} rdf:type cfm:RandomVariable ;",
                f'    cfm:hasDistribution "{sl_val}"^^uq:gmmLiteral .',
                f"{light_measurement} rdf:type ag:LaserMeasurement ;",
                f"    cfm:representedBy {light_rv} .",
                f"{light_rv} rdf:type cfm:RandomVariable ;",
                f'    cfm:hasDistribution "{light_val}"^^uq:gmmLiteral .',
            ])

        lines.append("")

    out_path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser(description="Generate Exp5 datasets")
    ap.add_argument("--output-dir", default="benchmark/data/exp5")
    ap.add_argument("--n-gears", type=int, default=1_000_000)
    ap.add_argument("--pass-fracs", nargs="+", type=float, default=[0.01, 0.05, 0.1, 0.3],
                    help="Fractions of gears intended to satisfy cdf(d, 9.8) >= 0.9")
    ap.add_argument("--pass-frac", type=float, default=None,
                    help="Single pass fraction to use with --name")
    ap.add_argument("--opt-present-frac", type=float, default=0.7,
                    help="Fraction of gears with both OPTIONAL-side measurements")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--name", default=None,
                    help="Optional single dataset name. When omitted, writes one file per --pass-fracs value.")
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    if args.name:
        pass_frac = args.pass_frac if args.pass_frac is not None else args.pass_fracs[0]
        specs = [(args.name, pass_frac, args.seed)]
    else:
        specs = []
        for pass_frac in args.pass_fracs:
            pass_label = str(pass_frac).replace(".", "p")
            specs.append((f"exp5_gears_{args.n_gears}_pass_{pass_label}", pass_frac, args.seed + round(pass_frac * 10_000)))

    for name, pass_frac, seed in specs:
        ttl_path = out_dir / f"{name}.ttl"
        meta_path = out_dir / f"{name}_meta.json"

        if ttl_path.exists() and not args.force:
            print(f"SKIP  {ttl_path}")
            continue

        write_dataset(
            ttl_path,
            n_gears=args.n_gears,
            pass_frac=pass_frac,
            opt_present_frac=args.opt_present_frac,
            seed=seed,
        )

        meta = {
            "dataset": name,
            "n_gears": args.n_gears,
            "pass_frac": pass_frac,
            "opt_present_frac": args.opt_present_frac,
            "neither_frac": max(0.0, 1.0 - args.opt_present_frac),
            "seed": seed,
            "ttl": str(ttl_path),
        }
        meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")
        print(f"WROTE {ttl_path}")
        print(f"WROTE {meta_path}")


if __name__ == "__main__":
    main()
