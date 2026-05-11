#!/usr/bin/env python3
"""
Generate Exp1 dimension-scaling datasets with the full Exp1 graph structure.

Design:
- fixed scale: E5 by default
- fixed K: 3 by default
- varying dimensions: 1, 2, 4, 8 by default
- preserves the entity/measurement layout needed by Q1-Q4
- om:hasValue remains a scalar and is defined as the weighted mean of dimension 0
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

DEFAULT_DIMENSIONS = [1, 2, 4, 8]
TEETH_PER_GEAR = 8
CALIPER_MEASUREMENTS_PER_SPINDLE = 3
LASER_MEASUREMENTS_PER_SPINDLE = 3


class MultiDimGMMGenerator:
    def __init__(self, rng: np.random.Generator, forced_k: int, dimensions: int):
        self.rng = rng
        self.forced_k = forced_k
        self.dimensions = dimensions

    def _normalize_weights(self, weights: List[float]) -> List[float]:
        total = sum(weights)
        normalized = [w / total for w in weights]
        normalized[-1] = 1.0 - sum(normalized[:-1])
        return normalized

    def _random_weights(self) -> List[float]:
        raw = self.rng.dirichlet(np.ones(self.forced_k)).tolist() if self.forced_k > 1 else [1.0]
        return self._normalize_weights(raw)

    def _weighted_mean_dim0(self, weights: List[float], means: np.ndarray) -> float:
        return float(np.dot(np.array(weights), means[:, 0]))

    def _format_gmm(self, means: np.ndarray, variances: np.ndarray, weights: List[float]) -> str:
        gmm = {
            "n_components": self.forced_k,
            "dimensions": self.dimensions,
            "covariance_type": "diag",
            "weights": [round(float(w), 6) for w in weights],
            "means": [[round(float(v), 6) for v in row] for row in means],
            "covariances": [[round(float(v), 8) for v in row] for row in variances],
        }
        return json.dumps(gmm, separators=(",", ":"))

    def _base_center(self, primary_lo: float, primary_hi: float, aux_base: float, aux_jitter: float) -> np.ndarray:
        center = np.zeros(self.dimensions)
        center[0] = self.rng.uniform(primary_lo, primary_hi)
        for j in range(1, self.dimensions):
            center[j] = self.rng.uniform(aux_base * j - aux_jitter, aux_base * j + aux_jitter)
        return center

    def _make_gmm_from_center(
        self,
        center: np.ndarray,
        dim0_noise: float,
        aux_noise: float,
        dim0_var_range: Tuple[float, float],
        aux_var_range: Tuple[float, float],
    ) -> Tuple[str, float]:
        means = np.stack(
            [
                center + self.rng.normal(
                    0.0,
                    [dim0_noise] + [aux_noise] * (self.dimensions - 1),
                    size=self.dimensions,
                )
                for _ in range(self.forced_k)
            ],
            axis=0,
        )
        variances = np.zeros((self.forced_k, self.dimensions))
        variances[:, 0] = self.rng.uniform(*dim0_var_range, size=self.forced_k)
        if self.dimensions > 1:
            variances[:, 1:] = self.rng.uniform(*aux_var_range, size=(self.forced_k, self.dimensions - 1))
        weights = self._random_weights()
        point_est = round(self._weighted_mean_dim0(weights, means), 6)
        return self._format_gmm(means, variances, weights), point_est

    def generate_tooth_scan(self, is_worn: bool) -> Tuple[str, float]:
        center = self._base_center(
            8.8 if is_worn else 9.5,
            9.75 if is_worn else 10.5,
            aux_base=0.25,
            aux_jitter=0.12,
        )
        if is_worn and self.dimensions > 1:
            center[1:] += 0.15
        return self._make_gmm_from_center(center, 0.08, 0.05, (0.01, 0.05), (0.005, 0.03))

    def generate_tooth_followup(self, base_json: str, inconsistent: bool) -> Tuple[str, float]:
        if inconsistent:
            center = self._base_center(9.0, 10.8, aux_base=0.30, aux_jitter=0.30)
            return self._make_gmm_from_center(center, 0.15, 0.10, (0.03, 0.12), (0.02, 0.08))

        base = json.loads(base_json)
        means = np.array(base["means"], dtype=float)
        variances = np.array(base["covariances"], dtype=float)
        weights = list(base["weights"])
        means = means + self.rng.normal(
            0.0,
            [0.03] + [0.02] * (self.dimensions - 1),
            size=means.shape,
        )
        scales = np.ones_like(variances)
        scales[:, 0] = self.rng.uniform(0.9, 1.2, size=self.forced_k)
        if self.dimensions > 1:
            scales[:, 1:] = self.rng.uniform(0.9, 1.15, size=(self.forced_k, self.dimensions - 1))
        variances = variances * scales
        point_est = round(self._weighted_mean_dim0(weights, means), 6)
        return self._format_gmm(means, variances, weights), point_est

    def generate_speed(self) -> Tuple[str, float]:
        center = self._base_center(80, 120, aux_base=5.0, aux_jitter=1.5)
        return self._make_gmm_from_center(center, 4.0, 1.0, (5.0, 30.0), (0.5, 3.0))

    def generate_torque(self) -> Tuple[str, float]:
        center = self._base_center(8, 18, aux_base=1.2, aux_jitter=0.4)
        return self._make_gmm_from_center(center, 0.8, 0.25, (0.5, 3.0), (0.05, 0.4))

    def generate_diameter(self) -> Tuple[str, float]:
        center = self._base_center(14.8, 15.2, aux_base=0.4, aux_jitter=0.05)
        return self._make_gmm_from_center(center, 0.04, 0.03, (0.001, 0.01), (0.001, 0.01))

    def generate_diameter_consistent(self, base_json: str) -> Tuple[str, float]:
        base = json.loads(base_json)
        means = np.array(base["means"], dtype=float)
        variances = np.array(base["covariances"], dtype=float)
        weights = list(base["weights"])
        means = means + self.rng.normal(
            0.0,
            [0.01] + [0.005] * (self.dimensions - 1),
            size=means.shape,
        )
        variances = variances * self.rng.uniform(0.9, 1.2, size=variances.shape)
        point_est = round(self._weighted_mean_dim0(weights, means), 6)
        return self._format_gmm(means, variances, weights), point_est


@dataclass
class DimensionConfig:
    scale: str
    k_value: int
    dimensions: int
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

    def generate_dataset(self, scale: str, dimensions: int, target_k: int) -> Tuple[Graph, Dict]:
        num_gears = SCALE_CONFIGS[scale]["num_gears"]
        cfg = DimensionConfig(scale=scale, k_value=target_k, dimensions=dimensions, gear_count=num_gears)
        rng = np.random.default_rng(self.seed + target_k * 1000 + dimensions * 100)
        gmm_gen = MultiDimGMMGenerator(rng, forced_k=target_k, dimensions=dimensions)

        g = Graph()
        self._bind(g)
        start = time.time()

        grinder_uri = EX["anglegrinder_2"]
        g.add((grinder_uri, RDF.type, AG.AngleGrinder))
        g.add((grinder_uri, RDFS.label, Literal("Angle Grinder 2", lang="en")))

        for gear_local in range(1, cfg.gear_count + 1):
            gear_uri = EX[f"ag2_gear_{gear_local:06d}"]
            motor_uri = EX[f"ag2_motor_{gear_local:06d}"]
            spindle_uri = EX[f"ag2_spindle_{gear_local:06d}"]

            g.add((gear_uri, RDF.type, AG.CrownGear))
            g.add((motor_uri, RDF.type, AG.Motor))
            g.add((spindle_uri, RDF.type, AG.Spindle))
            g.add((gear_uri, RDFS.label, Literal(f"Crown Gear 2-{gear_local:06d}", lang="en")))
            g.add((motor_uri, RDFS.label, Literal(f"Motor 2-{gear_local:06d}", lang="en")))
            g.add((spindle_uri, RDFS.label, Literal(f"Spindle 2-{gear_local:06d}", lang="en")))
            g.add((grinder_uri, AG.hasPart, gear_uri))
            g.add((grinder_uri, AG.hasPart, motor_uri))
            g.add((grinder_uri, AG.hasPart, spindle_uri))

            worn_mask = rng.random(TEETH_PER_GEAR) < 0.2
            inconsistent_mask = rng.random(TEETH_PER_GEAR) < 0.2

            for tooth_idx in range(1, TEETH_PER_GEAR + 1):
                char_uri = EX[f"ag2_gear_{gear_local:06d}_toothchar_{tooth_idx}"]
                g.add((char_uri, RDF.type, CFM.MeasurableCharacteristics))
                g.add((char_uri, RDFS.label, Literal(
                    f"Tooth Length Characteristic 2-{gear_local:06d}-{tooth_idx}",
                    lang="en",
                )))
                g.add((gear_uri, CFM.hasLengthCharacteristic, char_uri))

                base_json, base_value = gmm_gen.generate_tooth_scan(bool(worn_mask[tooth_idx - 1]))
                follow_json, follow_value = gmm_gen.generate_tooth_followup(
                    base_json, bool(inconsistent_mask[tooth_idx - 1])
                )

                self._add_measurement(
                    g, f"ag2_gear_{gear_local:06d}_tooth_{tooth_idx}",
                    "ct", char_uri, base_json, base_value,
                    AG.CTMeasurement,
                    f"CT Measurement 2-{gear_local:06d}-{tooth_idx}",
                    f"CT Point Cloud 2-{gear_local:06d}-{tooth_idx}",
                )
                self._add_measurement(
                    g, f"ag2_gear_{gear_local:06d}_tooth_{tooth_idx}",
                    "sl", char_uri, follow_json, follow_value,
                    AG.SLMeasurement,
                    f"SL Measurement 2-{gear_local:06d}-{tooth_idx}",
                    f"SL Point Cloud 2-{gear_local:06d}-{tooth_idx}",
                )

            for suffix, generator in (
                ("speed", gmm_gen.generate_speed),
                ("torque", gmm_gen.generate_torque),
            ):
                char_uri = EX[f"ag2_motor_{gear_local:06d}_{suffix}char"]
                g.add((char_uri, RDF.type, CFM.MeasurableCharacteristics))
                g.add((char_uri, RDFS.label, Literal(
                    f"{suffix.capitalize()} Characteristic 2-{gear_local:06d}",
                    lang="en",
                )))
                if suffix == "speed":
                    g.add((motor_uri, AG.hasSpeedCharacteristic, char_uri))
                else:
                    g.add((motor_uri, AG.hasTorqueCharacteristic, char_uri))
                dist_json, point = generator()
                self._add_measurement(
                    g, f"ag2_motor_{gear_local:06d}_{suffix}",
                    "primary", char_uri, dist_json, point,
                    OM.Measure,
                    f"{suffix.capitalize()} Measurement 2-{gear_local:06d}",
                    f"{suffix.capitalize()} Point Cloud 2-{gear_local:06d}",
                )

            spindle_char = EX[f"ag2_spindle_{gear_local:06d}_diamchar"]
            g.add((spindle_char, RDF.type, CFM.MeasurableCharacteristics))
            g.add((spindle_char, RDFS.label, Literal(
                f"Diameter Characteristic 2-{gear_local:06d}",
                lang="en",
            )))
            g.add((spindle_uri, AG.hasDiameterCharacteristic, spindle_char))
            base_diam_json, _ = gmm_gen.generate_diameter()
            for rep in range(1, CALIPER_MEASUREMENTS_PER_SPINDLE + 1):
                dist_json, point = gmm_gen.generate_diameter_consistent(base_diam_json)
                self._add_measurement(
                    g, f"ag2_spindle_{gear_local:06d}_diam",
                    f"caliper_{rep}", spindle_char, dist_json, point,
                    AG.CaliperMeasurement,
                    f"Caliper Measurement 2-{gear_local:06d}-{rep}",
                    f"Caliper Point Cloud 2-{gear_local:06d}-{rep}",
                )
            for rep in range(1, LASER_MEASUREMENTS_PER_SPINDLE + 1):
                dist_json, point = gmm_gen.generate_diameter_consistent(base_diam_json)
                self._add_measurement(
                    g, f"ag2_spindle_{gear_local:06d}_diam",
                    f"laser_{rep}", spindle_char, dist_json, point,
                    AG.LaserMeasurement,
                    f"Laser Measurement 2-{gear_local:06d}-{rep}",
                    f"Laser Point Cloud 2-{gear_local:06d}-{rep}",
                )

        metadata = {
            "scale": scale,
            "k": target_k,
            "dimensions": dimensions,
            "total_grinders": 1,
            "total_gears": num_gears,
            "teeth_per_gear": TEETH_PER_GEAR,
            "triple_count": len(g),
            "generation_time_seconds": round(time.time() - start, 2),
        }
        return g, metadata


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate Exp1 dimension datasets with the full Exp1 graph structure."
    )
    parser.add_argument("--scale", default="E5", choices=list(SCALE_CONFIGS.keys()))
    parser.add_argument("--k", type=int, default=3)
    parser.add_argument("--dimensions", nargs="+", type=int, default=DEFAULT_DIMENSIONS)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--output-dir",
        default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "exp1", "dimension"),
    )
    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)
    builder = DatasetBuilder(seed=args.seed)
    all_metadata: Dict[str, Dict] = {}

    for d in args.dimensions:
        print(f"\n=== Generating {args.scale} K={args.k} D={d} ===")
        graph, metadata = builder.generate_dataset(args.scale, d, args.k)
        out_path = os.path.join(args.output_dir, f"exp1_{args.scale}_K{args.k}_D{d}.ttl")
        graph.serialize(destination=out_path, format="turtle")
        print(f"  Wrote {out_path} ({metadata['triple_count']} triples)")
        all_metadata[f"{args.scale}_K{args.k}_D{d}"] = metadata

    meta_path = os.path.join(args.output_dir, "exp1_dimension_metadata.json")
    with open(meta_path, "w") as f:
        json.dump(all_metadata, f, indent=2)
    print(f"\nMetadata saved to {meta_path}")


if __name__ == "__main__":
    main()
