#!/usr/bin/env python3
"""
Generate Exp1 probabilistic datasets with one grinder archetype per file.

Design goals:
1. Use predicate names aligned with the architecture diagram.
2. Keep grinder identity aligned with K: grinder1→K=1, grinder2→K=3,
   grinder3→K=5, grinder4→K=10.
3. Preserve the original scale-based benchmark structure and numeric generation
   style as closely as practical.

Output files keep the existing Exp1 naming convention for compatibility:
  exp1_{scale}_K1.ttl
  exp1_{scale}_K3.ttl
  exp1_{scale}_K5.ttl
  exp1_{scale}_K10.ttl

Semantics of the per-file K:
  Each file contains exactly one grinder instance. Its grinder id is determined
  by the target K:
    K=1  -> anglegrinder_1
    K=3  -> anglegrinder_2
    K=5  -> anglegrinder_3
    K=10 -> anglegrinder_4
"""

import argparse
import json
import os
import time
from dataclasses import dataclass
from typing import Dict, List, Tuple

import numpy as np
from rdflib import Graph, Literal, Namespace, URIRef
from rdflib.namespace import RDF, RDFS, XSD


AG = Namespace("http://example.org/ontology/anglegrinder#")
CFM = Namespace("http://example.org/ontology/cfm#")
OM = Namespace("http://example.org/ontology/om#")
UQ = Namespace("http://example.org/ontology/uncertainty#")
EX = Namespace("http://example.org/data/")

GMM_DATATYPE = URIRef("http://example.org/ontology/uncertainty#gmmLiteral")

SCALE_CONFIGS = {
    "E1": {"num_gears": 10, "label": "Exp1 10 gears"},
    "E2": {"num_gears": 50, "label": "Exp1 50 gears"},
    "E3": {"num_gears": 100, "label": "Exp1 100 gears"},
    "E4": {"num_gears": 500, "label": "Exp1 500 gears"},
    "E5": {"num_gears": 1000, "label": "Exp1 1000 gears"},
    "E6": {"num_gears": 5000, "label": "Exp1 5000 gears"},
    "E7": {"num_gears": 10000, "label": "Exp1 10000 gears"},
}

DEFAULT_MAIN_SCALES = ["E1", "E3", "E5", "E7"]

FILE_K_VALUES = [1, 3, 5, 10]
TEETH_PER_GEAR = 8
CALIPER_MEASUREMENTS_PER_SPINDLE = 3
LASER_MEASUREMENTS_PER_SPINDLE = 3


class GMMGenerator:
    def __init__(self, rng: np.random.Generator, forced_k: int):
        self.rng = rng
        self.forced_k = forced_k

    def _normalize_weights(self, weights: List[float]) -> List[float]:
        total = sum(weights)
        normalized = [w / total for w in weights]
        normalized[-1] = 1.0 - sum(normalized[:-1])
        return normalized

    def _format_gmm(
        self, K: int, means: List[float], covariances: List[float], weights: List[float]
    ) -> str:
        gmm = {
            "n_components": K,
            "dimensions": 1,
            "covariance_type": "diag",
            "weights": [round(w, 6) for w in weights],
            "means": [[round(m, 6)] for m in means],
            "covariances": [[round(c, 8)] for c in covariances],
        }
        return json.dumps(gmm, separators=(",", ":"))

    def _weighted_mean(self, weights: List[float], means: List[float]) -> float:
        return sum(w * m for w, m in zip(weights, means))

    def _random_weights(self, K: int) -> List[float]:
        raw = self.rng.dirichlet(np.ones(K)).tolist() if K > 1 else [1.0]
        return self._normalize_weights(raw)

    def _make_gmm(
        self, mean_lo: float, mean_hi: float, var_lo: float, var_hi: float
    ) -> Tuple[str, float]:
        K = self.forced_k
        means = [float(self.rng.uniform(mean_lo, mean_hi)) for _ in range(K)]
        variances = [float(self.rng.uniform(var_lo, var_hi)) for _ in range(K)]
        weights = self._random_weights(K)
        point_est = round(self._weighted_mean(weights, means), 6)
        return self._format_gmm(K, means, variances, weights), point_est

    def generate_tooth_scan(self, is_worn: bool) -> Tuple[str, float]:
        if is_worn:
            return self._make_gmm(8.8, 9.75, 0.01, 0.05)
        return self._make_gmm(9.5, 10.5, 0.002, 0.015)

    def generate_tooth_followup(self, base_json: str, inconsistent: bool) -> Tuple[str, float]:
        if inconsistent:
            return self._make_gmm(9.0, 10.8, 0.05, 0.2)

        base = json.loads(base_json)
        means = [m[0] + float(self.rng.normal(0, 0.05)) for m in base["means"]]
        variances = [c[0] * float(self.rng.uniform(0.9, 1.3)) for c in base["covariances"]]
        weights = base["weights"]
        point_est = round(self._weighted_mean(weights, means), 6)
        return self._format_gmm(self.forced_k, means, variances, weights), point_est

    def generate_speed(self) -> Tuple[str, float]:
        return self._make_gmm(80, 120, 5, 30)

    def generate_torque(self) -> Tuple[str, float]:
        return self._make_gmm(8, 18, 0.5, 3.0)

    def generate_diameter(self) -> Tuple[str, float]:
        return self._make_gmm(14.8, 15.2, 0.001, 0.01)

    def generate_diameter_consistent(self, base_json: str) -> Tuple[str, float]:
        base = json.loads(base_json)
        means = [m[0] + float(self.rng.normal(0, 0.01)) for m in base["means"]]
        variances = [c[0] * float(self.rng.uniform(0.9, 1.2)) for c in base["covariances"]]
        weights = base["weights"]
        point_est = round(self._weighted_mean(weights, means), 6)
        return self._format_gmm(self.forced_k, means, variances, weights), point_est


@dataclass
class GrinderConfig:
    grinder_idx: int
    k_value: int
    gear_count: int


class DatasetBuilder:
    def __init__(self, seed: int):
        self.seed = seed

    def _bind(self, g: Graph) -> None:
        g.bind("rdf", RDF)
        g.bind("rdfs", RDFS)
        g.bind("ag", AG)
        g.bind("cfm", CFM)
        g.bind("om", OM)
        g.bind("uq", UQ)
        g.bind("ex", EX)

    def _single_grinder_config(self, num_gears: int, target_k: int) -> GrinderConfig:
        grinder_idx = FILE_K_VALUES.index(target_k) + 1
        return GrinderConfig(grinder_idx=grinder_idx, k_value=target_k, gear_count=num_gears)

    def _add_measurement(
        self,
        g: Graph,
        component_prefix: str,
        measure_id: str,
        characteristic_uri,
        distribution_json: str,
        point_value: float,
        measurement_type,
        measurement_label: str,
        pointcloud_label: str,
    ) -> None:
        meas_uri = EX[f"{component_prefix}_measurement_{measure_id}"]
        rv_uri = EX[f"{component_prefix}_rv_{measure_id}"]
        pc_uri = EX[f"{component_prefix}_pc_{measure_id}"]

        g.add((meas_uri, RDF.type, OM.Measure))
        g.add((meas_uri, RDF.type, measurement_type))
        g.add((pc_uri, RDF.type, CFM.PointCloud))
        g.add((meas_uri, RDFS.label, Literal(measurement_label, lang="en")))
        g.add((rv_uri, RDFS.label, Literal(f"Random Variable {measure_id}", lang="en")))
        g.add((pc_uri, RDFS.label, Literal(pointcloud_label, lang="en")))
        g.add((characteristic_uri, CFM.measuresCharacteristicBy, meas_uri))
        g.add((meas_uri, CFM.hasInputPointCloud, pc_uri))
        g.add((meas_uri, CFM.representedBy, rv_uri))
        g.add((meas_uri, OM.hasValue, Literal(point_value, datatype=XSD.double)))
        g.add((meas_uri, OM.hasUnit, OM.millimetre))
        g.add((rv_uri, RDF.type, CFM.RandomVariable))
        g.add((rv_uri, CFM.hasDomain, CFM.NonNegativeDouble))
        g.add((rv_uri, CFM.hasDistribution, Literal(distribution_json, datatype=GMM_DATATYPE)))

    def generate_scale(self, scale: str, num_gears: int, target_k: int) -> Tuple[Graph, Dict]:
        g = Graph()
        self._bind(g)
        cfg = self._single_grinder_config(num_gears, target_k)

        total_gears = 0
        start = time.time()

        rng = np.random.default_rng(self.seed + target_k * 1000 + cfg.grinder_idx * 100)
        gmm_gen = GMMGenerator(rng, forced_k=cfg.k_value)

        grinder_uri = EX[f"anglegrinder_{cfg.grinder_idx}"]
        g.add((grinder_uri, RDF.type, AG.AngleGrinder))
        g.add((grinder_uri, RDFS.label, Literal(f"Angle Grinder {cfg.grinder_idx}", lang="en")))

        for gear_local in range(1, cfg.gear_count + 1):
            total_gears += 1
            gear_uri = EX[f"ag{cfg.grinder_idx}_gear_{gear_local:06d}"]
            motor_uri = EX[f"ag{cfg.grinder_idx}_motor_{gear_local:06d}"]
            spindle_uri = EX[f"ag{cfg.grinder_idx}_spindle_{gear_local:06d}"]

            g.add((gear_uri, RDF.type, AG.CrownGear))
            g.add((motor_uri, RDF.type, AG.Motor))
            g.add((spindle_uri, RDF.type, AG.Spindle))
            g.add((gear_uri, RDFS.label, Literal(f"Crown Gear {cfg.grinder_idx}-{gear_local:06d}", lang="en")))
            g.add((motor_uri, RDFS.label, Literal(f"Motor {cfg.grinder_idx}-{gear_local:06d}", lang="en")))
            g.add((spindle_uri, RDFS.label, Literal(f"Spindle {cfg.grinder_idx}-{gear_local:06d}", lang="en")))
            g.add((grinder_uri, AG.hasPart, gear_uri))
            g.add((grinder_uri, AG.hasPart, motor_uri))
            g.add((grinder_uri, AG.hasPart, spindle_uri))

            # Gear tooth measurements: two sensing modalities per tooth (CT + SL),
            # which better matches the experiment story for retrieval/filtering
            # on one modality and distribution comparison across both modalities.
            worn_mask = rng.random(TEETH_PER_GEAR) < 0.2
            inconsistent_mask = rng.random(TEETH_PER_GEAR) < 0.2

            for tooth_idx in range(1, TEETH_PER_GEAR + 1):
                char_uri = EX[f"ag{cfg.grinder_idx}_gear_{gear_local:06d}_toothchar_{tooth_idx}"]
                g.add((char_uri, RDF.type, CFM.MeasurableCharacteristics))
                g.add((char_uri, RDFS.label, Literal(
                    f"Tooth Length Characteristic {cfg.grinder_idx}-{gear_local:06d}-{tooth_idx}",
                    lang="en",
                )))
                g.add((gear_uri, CFM.hasLengthCharacteristic, char_uri))

                base_json, base_value = gmm_gen.generate_tooth_scan(bool(worn_mask[tooth_idx - 1]))
                follow_json, follow_value = gmm_gen.generate_tooth_followup(
                    base_json, bool(inconsistent_mask[tooth_idx - 1])
                )

                self._add_measurement(
                    g, f"ag{cfg.grinder_idx}_gear_{gear_local:06d}_tooth_{tooth_idx}",
                    "ct", char_uri, base_json, base_value,
                    AG.CTMeasurement,
                    f"CT Measurement {cfg.grinder_idx}-{gear_local:06d}-{tooth_idx}",
                    f"CT Point Cloud {cfg.grinder_idx}-{gear_local:06d}-{tooth_idx}",
                )
                self._add_measurement(
                    g, f"ag{cfg.grinder_idx}_gear_{gear_local:06d}_tooth_{tooth_idx}",
                    "sl", char_uri, follow_json, follow_value,
                    AG.SLMeasurement,
                    f"SL Measurement {cfg.grinder_idx}-{gear_local:06d}-{tooth_idx}",
                    f"SL Point Cloud {cfg.grinder_idx}-{gear_local:06d}-{tooth_idx}",
                )

            # Motor: speed + torque modeled as two measurable characteristics.
            for suffix, generator in (
                ("speed", gmm_gen.generate_speed),
                ("torque", gmm_gen.generate_torque),
            ):
                char_uri = EX[f"ag{cfg.grinder_idx}_motor_{gear_local:06d}_{suffix}char"]
                g.add((char_uri, RDF.type, CFM.MeasurableCharacteristics))
                g.add((char_uri, RDFS.label, Literal(
                    f"{suffix.capitalize()} Characteristic {cfg.grinder_idx}-{gear_local:06d}",
                    lang="en",
                )))
                if suffix == "speed":
                    g.add((motor_uri, AG.hasSpeedCharacteristic, char_uri))
                else:
                    g.add((motor_uri, AG.hasTorqueCharacteristic, char_uri))
                dist_json, point = generator()
                self._add_measurement(
                    g, f"ag{cfg.grinder_idx}_motor_{gear_local:06d}_{suffix}",
                    "primary", char_uri, dist_json, point,
                    OM.Measure,
                    f"{suffix.capitalize()} Measurement {cfg.grinder_idx}-{gear_local:06d}",
                    f"{suffix.capitalize()} Point Cloud {cfg.grinder_idx}-{gear_local:06d}",
                )

            # Spindle: one characteristic, measured by caliper and laser.
            spindle_char = EX[f"ag{cfg.grinder_idx}_spindle_{gear_local:06d}_diamchar"]
            g.add((spindle_char, RDF.type, CFM.MeasurableCharacteristics))
            g.add((spindle_char, RDFS.label, Literal(
                f"Diameter Characteristic {cfg.grinder_idx}-{gear_local:06d}",
                lang="en",
            )))
            g.add((spindle_uri, AG.hasDiameterCharacteristic, spindle_char))
            base_diam_json, _ = gmm_gen.generate_diameter()
            for rep in range(1, CALIPER_MEASUREMENTS_PER_SPINDLE + 1):
                dist_json, point = gmm_gen.generate_diameter_consistent(base_diam_json)
                self._add_measurement(
                    g, f"ag{cfg.grinder_idx}_spindle_{gear_local:06d}_diam",
                    f"caliper_{rep}", spindle_char, dist_json, point,
                    AG.CaliperMeasurement,
                    f"Caliper Measurement {cfg.grinder_idx}-{gear_local:06d}-{rep}",
                    f"Caliper Point Cloud {cfg.grinder_idx}-{gear_local:06d}-{rep}",
                )
            for rep in range(1, LASER_MEASUREMENTS_PER_SPINDLE + 1):
                dist_json, point = gmm_gen.generate_diameter_consistent(base_diam_json)
                self._add_measurement(
                    g, f"ag{cfg.grinder_idx}_spindle_{gear_local:06d}_diam",
                    f"laser_{rep}", spindle_char, dist_json, point,
                    AG.LaserMeasurement,
                    f"Laser Measurement {cfg.grinder_idx}-{gear_local:06d}-{rep}",
                    f"Laser Point Cloud {cfg.grinder_idx}-{gear_local:06d}-{rep}",
                )

        metadata = {
            "scale": scale,
            "target_k": target_k,
            "grinder_id": cfg.grinder_idx,
            "grinder_k_values": [target_k],
            "dominant_target_k": target_k,
            "dominant_gear_count": num_gears,
            "support_gear_count_each": 0,
            "total_grinders": 1,
            "total_gears": total_gears,
            "triple_count": len(g),
            "generation_time_seconds": round(time.time() - start, 2),
            "predicate_profile": [
                "rdf:type",
                "ag:hasPart",
                "cfm:hasLengthCharacteristic",
                "cfm:measuresCharacteristicBy",
                "cfm:hasInputPointCloud",
                "cfm:representedBy",
                "cfm:hasDomain",
                "cfm:hasDistribution",
                "om:hasValue",
                "om:hasUnit",
            ],
        }
        return g, metadata


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate Exp1 datasets with four grinder archetypes and architecture-aligned predicates."
    )
    parser.add_argument(
        "--scales",
        nargs="+",
        default=DEFAULT_MAIN_SCALES,
        choices=list(SCALE_CONFIGS.keys()),
        help="Exp1 scales to generate (default: E1 E3 E5 E7)",
    )
    parser.add_argument(
        "--seed", type=int, default=42, help="Base random seed"
    )
    parser.add_argument(
        "--output-dir",
        default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "data", "exp1", "main"),
        help="Output directory for exp1_{scale}_K{k}.ttl files",
    )
    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)
    builder = DatasetBuilder(seed=args.seed)
    all_metadata: Dict[str, Dict] = {}

    for scale in args.scales:
        num_gears = SCALE_CONFIGS[scale]["num_gears"]
        for target_k in FILE_K_VALUES:
            print(f"\n=== Generating {scale} target K={target_k} ({num_gears} dominant gears) ===")
            graph, metadata = builder.generate_scale(scale, num_gears, target_k)
            out_path = os.path.join(args.output_dir, f"exp1_{scale}_K{target_k}.ttl")
            graph.serialize(destination=out_path, format="turtle")
            print(f"  Wrote {out_path}  ({metadata['triple_count']} triples)")
            all_metadata[f"{scale}_K{target_k}"] = metadata

    meta_path = os.path.join(args.output_dir, "exp1_partitioned_metadata.json")
    with open(meta_path, "w") as f:
        json.dump(all_metadata, f, indent=2)
    print(f"\nMetadata saved to {meta_path}")


if __name__ == "__main__":
    main()
