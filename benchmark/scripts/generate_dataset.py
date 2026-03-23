#!/usr/bin/env python3
"""
ProbSPARQL Benchmark Dataset Generator
=======================================
Generates synthetic RDF datasets at multiple scale factors for
benchmarking the ProbSPARQL probabilistic SPARQL extension.

Namespace URIs match the existing ProbSPARQL codebase:
  ag:  <http://example.org/ontology/anglegrinder#>
  cfm: <http://example.org/ontology/cfm#>
  om:  <http://example.org/ontology/om#>
  uq:  <http://example.org/ontology/uncertainty#>
  ex:  <http://example.org/data/>

Usage:
  python generate_dataset.py                     # Generate all scales (S1-S4)
  python generate_dataset.py --scales S1 S2      # Generate specific scales
  python generate_dataset.py --seed 123          # Custom random seed
  python generate_dataset.py --output-dir ../data # Custom output directory
"""

import argparse
import json
import math
import os
import sys
import time
from collections import defaultdict
from typing import Optional

import numpy as np

try:
    from rdflib import Graph, Namespace, Literal, URIRef, BNode
    from rdflib.namespace import RDF, RDFS, XSD, OWL
except ImportError:
    print("ERROR: rdflib is required. Install with: pip install rdflib")
    sys.exit(1)


# =============================================================================
# Namespace Definitions (matching existing codebase)
# =============================================================================
AG = Namespace("http://example.org/ontology/anglegrinder#")
CFM = Namespace("http://example.org/ontology/cfm#")
OM = Namespace("http://example.org/ontology/om#")
UQ = Namespace("http://example.org/ontology/uncertainty#")
CORE = Namespace("http://example.org/ontology/core#")
EX = Namespace("http://example.org/data/")

# Custom datatype URI for GMM literals
GMM_DATATYPE = URIRef("http://example.org/ontology/uncertainty#gmmLiteral")

# =============================================================================
# Scale Factor Configuration
# =============================================================================
SCALE_CONFIGS = {
    # Standard scales
    "S0": {"num_gears": 1, "label": "~300 triples (minimal example)"},
    "S1": {"num_gears": 10, "label": "~10K triples"},
    "S2": {"num_gears": 100, "label": "~100K triples"},
    "S3": {"num_gears": 1000, "label": "~1M triples"},
    "S4": {"num_gears": 5000, "label": "~5M triples"},
    # Exp 1 overhead benchmark scales (uniform-K datasets)
    "E1": {"num_gears": 10,   "label": "Exp1 10 gears"},
    "E2": {"num_gears": 50,   "label": "Exp1 50 gears"},
    "E3": {"num_gears": 100,  "label": "Exp1 100 gears"},
    "E4": {"num_gears": 500,   "label": "Exp1 500 gears"},
    "E5": {"num_gears": 1000,  "label": "Exp1 1000 gears"},
    "E6": {"num_gears": 5000,  "label": "Exp1 5000 gears"},
    "E7": {"num_gears": 10000, "label": "Exp1 10000 gears"},
    # JSD experiment scales (controlled overlap for SPRT testing)
    "JSD_easy": {
        "num_gears": 100,
        "label": "JSD Easy (80% low overlap)",
        "overlap_mode": "easy",  # 80% low overlap (easy SPRT)
    },
    "JSD_hard": {
        "num_gears": 100,
        "label": "JSD Hard (80% high overlap)",
        "overlap_mode": "hard",  # 80% high overlap (hard SPRT)
    },
    "JSD_mixed": {
        "num_gears": 100,
        "label": "JSD Mixed (33% each)",
        "overlap_mode": "mixed",  # Equal mix of all three
    },
}

TEETH_PER_GEAR = 8
CT_MEASUREMENTS_PER_TOOTH = 1
SL_MEASUREMENTS_PER_TOOTH = 1
CALIPER_MEASUREMENTS_PER_SPINDLE = 3
LASER_MEASUREMENTS_PER_SPINDLE = 3


# =============================================================================
# GMM Generation Utilities
# =============================================================================
class GMMGenerator:
    """Generates Gaussian Mixture Model parameters with controlled properties."""

    def __init__(self, rng: np.random.Generator, forced_k: Optional[int] = None):
        self.rng = rng
        self.forced_k = forced_k  # When set, all _sample_K calls return this value

    def _normalize_weights(self, weights):
        """Normalize weights to sum to 1.0 exactly."""
        total = sum(weights)
        normalized = [w / total for w in weights]
        normalized[-1] = 1.0 - sum(normalized[:-1])
        return normalized

    def _sample_K(self, distribution: dict) -> int:
        """Sample number of components from a categorical distribution.
        If forced_k is set, always returns that value regardless of distribution.
        """
        if self.forced_k is not None:
            return self.forced_k
        ks = list(distribution.keys())
        probs = list(distribution.values())
        return int(self.rng.choice(ks, p=probs))

    def _format_gmm(self, K, means, covariances, weights, covariance_type="full"):
        """Format GMM parameters as a JSON string."""
        gmm = {
            "K": K,
            "d": 1,
            "covariance_type": covariance_type,
            "weights": [round(w, 6) for w in weights],
            "means": [[round(m, 6)] for m in means],
            "covariances": None,
        }
        if covariance_type == "full":
            gmm["covariances"] = [[[round(c, 8)]] for c in covariances]
        elif covariance_type == "diag":
            gmm["covariances"] = [[round(c, 8)] for c in covariances]
        else:
            raise ValueError(f"Unknown covariance_type: {covariance_type}")
        return json.dumps(gmm, separators=(",", ":"))

    def _weighted_mean(self, weights, means):
        """Compute weighted mean of a GMM."""
        return sum(w * m for w, m in zip(weights, means))

    def generate_tooth_ct(self, is_worn: bool):
        """Generate CT scan GMM for a tooth measurement.

        Returns: (gmm_json, point_estimate, is_worn, covariance_type)
        """
        cov_type = self.rng.choice(["full", "diag"])

        if is_worn:
            K = self._sample_K({1: 0.5, 2: 0.5})
            means = [float(self.rng.uniform(8.8, 9.75)) for _ in range(K)]
            variances = [float(self.rng.uniform(0.01, 0.05)) for _ in range(K)]
            raw_weights = self.rng.dirichlet(np.ones(K)).tolist() if K > 1 else [1.0]
        else:
            K = self._sample_K({1: 0.6, 2: 0.3, 3: 0.1})
            means = [float(self.rng.uniform(9.5, 10.5)) for _ in range(K)]
            variances = [float(self.rng.uniform(0.002, 0.015)) for _ in range(K)]
            raw_weights = self.rng.dirichlet(np.ones(K)).tolist() if K > 1 else [1.0]

        weights = self._normalize_weights(raw_weights)
        point_est = round(self._weighted_mean(weights, means), 6)
        gmm_json = self._format_gmm(K, means, variances, weights, cov_type)
        return gmm_json, point_est, cov_type

    def generate_tooth_sl_consistent(self, ct_gmm_json: str):
        """Generate SL measurement CONSISTENT with CT (low JS divergence)."""
        ct_params = json.loads(ct_gmm_json)
        K = ct_params["K"]
        cov_type = ct_params["covariance_type"]
        ct_means = [m[0] for m in ct_params["means"]]

        if cov_type == "full":
            ct_vars = [c[0][0] for c in ct_params["covariances"]]
        else:
            ct_vars = [c[0] for c in ct_params["covariances"]]

        means = [m + float(self.rng.normal(0, 0.05)) for m in ct_means]
        variances = [v * float(self.rng.uniform(0.9, 1.3)) for v in ct_vars]
        weights = ct_params["weights"]

        point_est = round(self._weighted_mean(weights, means), 6)
        gmm_json = self._format_gmm(K, means, variances, weights, cov_type)
        return gmm_json, point_est

    def generate_tooth_sl_inconsistent(self):
        """Generate SL measurement INCONSISTENT with CT (high JS divergence)."""
        cov_type = self.rng.choice(["full", "diag"])
        K = self._sample_K({1: 0.4, 2: 0.4, 3: 0.2})
        means = [float(self.rng.uniform(9.0, 10.8)) for _ in range(K)]
        variances = [float(self.rng.uniform(0.05, 0.2)) for _ in range(K)]
        raw_weights = self.rng.dirichlet(np.ones(K)).tolist() if K > 1 else [1.0]
        weights = self._normalize_weights(raw_weights)

        point_est = round(self._weighted_mean(weights, means), 6)
        gmm_json = self._format_gmm(K, means, variances, weights, cov_type)
        return gmm_json, point_est

    def generate_tooth_sl_controlled(self, ct_gmm_json: str, overlap_type: str):
        """Generate SL measurement with controlled overlap for JSD experiments.

        overlap_type:
        - "low": mean offset 1.0-3.0 (easy SPRT - quickly rejected)
        - "medium": mean offset 0.3-1.0 (moderate)
        - "high": mean offset 0.01-0.3 (hard SPRT - requires many samples)
        """
        ct_params = json.loads(ct_gmm_json)
        K = ct_params["K"]
        cov_type = ct_params["covariance_type"]
        ct_means = [m[0] for m in ct_params["means"]]

        if cov_type == "full":
            ct_vars = [c[0][0] for c in ct_params["covariances"]]
        else:
            ct_vars = [c[0] for c in ct_params["covariances"]]

        # Determine offset range based on overlap type
        if overlap_type == "low":
            offset_min, offset_max = 1.0, 3.0
            var_multiplier_min, var_multiplier_max = 1.5, 3.0
        elif overlap_type == "medium":
            offset_min, offset_max = 0.3, 1.0
            var_multiplier_min, var_multiplier_max = 1.2, 2.0
        else:  # high
            offset_min, offset_max = 0.01, 0.3
            var_multiplier_min, var_multiplier_max = 0.8, 1.5

        # Apply controlled offset
        offset = float(self.rng.uniform(offset_min, offset_max))
        direction = 1 if self.rng.random() > 0.5 else -1
        means = [
            m + direction * offset + float(self.rng.normal(0, 0.02)) for m in ct_means
        ]
        variances = [
            v * float(self.rng.uniform(var_multiplier_min, var_multiplier_max))
            for v in ct_vars
        ]
        weights = ct_params["weights"]

        point_est = round(self._weighted_mean(weights, means), 6)
        gmm_json = self._format_gmm(K, means, variances, weights, cov_type)
        return gmm_json, point_est

    def generate_speed(self):
        """Generate motor speed GMM (rad/s)."""
        cov_type = self.rng.choice(["full", "diag"])
        K = self._sample_K({1: 0.5, 2: 0.5})
        means = [float(self.rng.uniform(80, 120)) for _ in range(K)]
        variances = [float(self.rng.uniform(5, 30)) for _ in range(K)]
        raw_weights = self.rng.dirichlet(np.ones(K)).tolist() if K > 1 else [1.0]
        weights = self._normalize_weights(raw_weights)

        point_est = round(self._weighted_mean(weights, means), 6)
        gmm_json = self._format_gmm(K, means, variances, weights, cov_type)
        return gmm_json, point_est

    def generate_torque(self):
        """Generate motor torque GMM (Nm)."""
        cov_type = self.rng.choice(["full", "diag"])
        K = self._sample_K({1: 0.5, 2: 0.5})
        means = [float(self.rng.uniform(1.5, 3.5)) for _ in range(K)]
        variances = [float(self.rng.uniform(0.005, 0.05)) for _ in range(K)]
        raw_weights = self.rng.dirichlet(np.ones(K)).tolist() if K > 1 else [1.0]
        weights = self._normalize_weights(raw_weights)

        point_est = round(self._weighted_mean(weights, means), 6)
        gmm_json = self._format_gmm(K, means, variances, weights, cov_type)
        return gmm_json, point_est

    def generate_diameter(self):
        """Generate spindle diameter GMM (mm, nominal 15mm)."""
        cov_type = self.rng.choice(["full", "diag"])
        K = self._sample_K({1: 0.4, 2: 0.4, 3: 0.2})
        means = [float(self.rng.uniform(14.8, 15.2)) for _ in range(K)]
        variances = [float(self.rng.uniform(0.0001, 0.005)) for _ in range(K)]
        raw_weights = self.rng.dirichlet(np.ones(K)).tolist() if K > 1 else [1.0]
        weights = self._normalize_weights(raw_weights)

        point_est = round(self._weighted_mean(weights, means), 6)
        gmm_json = self._format_gmm(K, means, variances, weights, cov_type)
        return gmm_json, point_est

    def generate_diameter_consistent(self, base_gmm_json: str):
        """Generate diameter measurement consistent with base (for fusion)."""
        base = json.loads(base_gmm_json)
        K = base["K"]
        cov_type = base["covariance_type"]
        base_means = [m[0] for m in base["means"]]
        if cov_type == "full":
            base_vars = [c[0][0] for c in base["covariances"]]
        else:
            base_vars = [c[0] for c in base["covariances"]]

        means = [m + float(self.rng.normal(0, 0.02)) for m in base_means]
        variances = [v * float(self.rng.uniform(0.8, 1.5)) for v in base_vars]
        weights = base["weights"]

        point_est = round(self._weighted_mean(weights, means), 6)
        gmm_json = self._format_gmm(K, means, variances, weights, cov_type)
        return gmm_json, point_est


# =============================================================================
# Dataset Generator
# =============================================================================
class DatasetGenerator:
    """Generates RDF dataset for ProbSPARQL benchmarking."""

    def __init__(self, seed=42, forced_k: Optional[int] = None):
        self.seed = seed
        self.forced_k = forced_k
        self.rng = np.random.default_rng(seed)
        self.gmm_gen = GMMGenerator(self.rng, forced_k=forced_k)
        self.metadata = {}

    def _bind_namespaces(self, g: Graph):
        """Bind all prefixes to the graph."""
        g.bind("rdf", RDF)
        g.bind("rdfs", RDFS)
        g.bind("xsd", XSD)
        g.bind("owl", OWL)
        g.bind("ag", AG)
        g.bind("cfm", CFM)
        g.bind("om", OM)
        g.bind("uq", UQ)
        g.bind("core", CORE)
        g.bind("ex", EX)

    def _add_unit_instances(self, g: Graph):
        """Add unit instances (millimetre, rad/s, Nm, watt)."""
        g.add((OM.millimetre, RDF.type, OM.LengthUnit))
        g.add((OM.millimetre, RDFS.label, Literal("millimetre", lang="en")))

        g.add((OM.radianPerSecond, RDF.type, OM.AngularVelocityUnit))
        g.add((OM.radianPerSecond, RDFS.label, Literal("radian per second", lang="en")))

        g.add((OM.newtonMetre, RDF.type, OM.TorqueUnit))
        g.add((OM.newtonMetre, RDFS.label, Literal("newton metre", lang="en")))

        g.add((OM.watt, RDF.type, OM.PowerUnit))
        g.add((OM.watt, RDFS.label, Literal("watt", lang="en")))

    def generate(
        self, num_gears: int, scale_label: str, overlap_mode: Optional[str] = None
    ) -> tuple:
        """Generate the full RDF dataset.

        Returns: (Graph, metadata_dict)

        overlap_mode: For JSD experiments
        - "easy": 80% low overlap (quick SPRT rejection)
        - "hard": 80% high overlap (hard SPRT)
        - "mixed": 33% each type
        - None: standard random 20% inconsistent
        """
        g = Graph()
        self._bind_namespaces(g)
        self._add_unit_instances(g)

        worn_teeth_count = 0
        inconsistent_pairs_count = 0
        total_teeth = num_gears * TEETH_PER_GEAR

        worn_mask = self.rng.random(total_teeth) < 0.2

        # Determine overlap type for each tooth (JSD experiment)
        overlap_types = ["low", "medium", "high"]
        if overlap_mode == "easy":
            # 80% low overlap, 20% high overlap
            overlap_assignment = self.rng.choice(
                [
                    "low",
                    "low",
                    "low",
                    "low",
                    "low",
                    "low",
                    "low",
                    "low",
                    "high",
                    "high",
                ],
                size=total_teeth,
            )
        elif overlap_mode == "hard":
            # 80% high overlap, 20% low overlap
            overlap_assignment = self.rng.choice(
                [
                    "high",
                    "high",
                    "high",
                    "high",
                    "high",
                    "high",
                    "high",
                    "high",
                    "low",
                    "low",
                ],
                size=total_teeth,
            )
        elif overlap_mode == "mixed":
            # Equal mix
            overlap_assignment = self.rng.choice(
                ["low", "medium", "high"], size=total_teeth
            )
        else:
            # Standard mode: use inconsistent mask
            overlap_assignment = None
            inconsistent_mask = self.rng.random(total_teeth) < 0.2

        grinder_uri = EX["anglegrinder_bench"]
        g.add((grinder_uri, RDF.type, AG.AngleGrinder))
        g.add(
            (
                grinder_uri,
                RDFS.label,
                Literal(f"Benchmark Angle Grinder ({scale_label})", lang="en"),
            )
        )

        start_time = time.time()
        tooth_idx = 0

        for i in range(1, num_gears + 1):
            gear_uri = EX[f"gear_{i:06d}"]

            g.add((gear_uri, RDF.type, AG.CrownGear))
            g.add((gear_uri, RDFS.label, Literal(f"Crown Gear #{i:06d}", lang="en")))
            g.add((grinder_uri, AG.hasPart, gear_uri))

            for t in range(1, TEETH_PER_GEAR + 1):
                is_worn = bool(worn_mask[tooth_idx])

                # Determine if inconsistent and overlap type
                if overlap_mode is not None:
                    # JSD experiment mode
                    is_inconsistent = True  # All have some overlap
                    overlap_type = overlap_assignment[tooth_idx]
                else:
                    # Standard mode
                    is_inconsistent = bool(inconsistent_mask[tooth_idx])
                    overlap_type = None

                if is_worn:
                    worn_teeth_count += 1
                if is_inconsistent:
                    inconsistent_pairs_count += 1

                tooth_char_uri = EX[f"toothlen_{i:06d}_{t}"]
                g.add((tooth_char_uri, RDF.type, CFM.MeasurableCharacteristics))
                g.add(
                    (
                        tooth_char_uri,
                        RDFS.label,
                        Literal(f"Tooth Length (Gear {i:06d}, Tooth {t})", lang="en"),
                    )
                )
                g.add((gear_uri, CFM.hasCharacteristic, tooth_char_uri))

                # CT Measurement
                ct_gmm_json, ct_point_est, ct_cov_type = self.gmm_gen.generate_tooth_ct(
                    is_worn
                )

                meas_ct_uri = EX[f"meas_ct_{i:06d}_{t}"]
                rv_ct_uri = EX[f"rv_ct_{i:06d}_{t}"]
                voxel_uri = EX[f"voxel_{i:06d}_{t}"]

                g.add((voxel_uri, RDF.type, CFM.VoxelGrid))
                g.add(
                    (
                        voxel_uri,
                        RDFS.label,
                        Literal(f"CT Voxel Grid (Gear {i:06d}, Tooth {t})", lang="en"),
                    )
                )

                g.add((meas_ct_uri, RDF.type, OM.Measure))
                g.add(
                    (
                        meas_ct_uri,
                        RDFS.label,
                        Literal(f"CT Measurement (Gear {i:06d}, Tooth {t})", lang="en"),
                    )
                )
                g.add((meas_ct_uri, CFM.hasInputVoxelGrid, voxel_uri))
                g.add((meas_ct_uri, CFM.measuresCharacteristic, tooth_char_uri))
                g.add((meas_ct_uri, CFM.hasProbabilisticValue, rv_ct_uri))
                g.add(
                    (
                        meas_ct_uri,
                        OM.hasValue,
                        Literal(ct_point_est, datatype=XSD.double),
                    )
                )
                g.add((meas_ct_uri, OM.hasUnit, OM.millimetre))

                g.add((rv_ct_uri, RDF.type, UQ.RandomVariable))
                g.add(
                    (
                        rv_ct_uri,
                        RDFS.label,
                        Literal(
                            f"CT Distribution (Gear {i:06d}, Tooth {t})", lang="en"
                        ),
                    )
                )
                g.add(
                    (
                        rv_ct_uri,
                        UQ.hasDistribution,
                        Literal(ct_gmm_json, datatype=GMM_DATATYPE),
                    )
                )

                # SL Measurement
                if overlap_mode is not None:
                    # JSD experiment: use controlled overlap
                    sl_gmm_json, sl_point_est = (
                        self.gmm_gen.generate_tooth_sl_controlled(
                            ct_gmm_json, overlap_type
                        )
                    )
                elif is_inconsistent:
                    sl_gmm_json, sl_point_est = (
                        self.gmm_gen.generate_tooth_sl_inconsistent()
                    )
                else:
                    sl_gmm_json, sl_point_est = (
                        self.gmm_gen.generate_tooth_sl_consistent(ct_gmm_json)
                    )

                meas_sl_uri = EX[f"meas_sl_{i:06d}_{t}"]
                rv_sl_uri = EX[f"rv_sl_{i:06d}_{t}"]
                pc_uri = EX[f"pc_{i:06d}_{t}"]

                g.add((pc_uri, RDF.type, CFM.PointCloud))
                g.add(
                    (
                        pc_uri,
                        RDFS.label,
                        Literal(f"Point Cloud (Gear {i:06d}, Tooth {t})", lang="en"),
                    )
                )

                g.add((meas_sl_uri, RDF.type, OM.Measure))
                g.add(
                    (
                        meas_sl_uri,
                        RDFS.label,
                        Literal(f"SL Measurement (Gear {i:06d}, Tooth {t})", lang="en"),
                    )
                )
                g.add((meas_sl_uri, CFM.hasInputPointCloud, pc_uri))
                g.add((meas_sl_uri, CFM.measuresCharacteristic, tooth_char_uri))
                g.add((meas_sl_uri, CFM.hasProbabilisticValue, rv_sl_uri))
                g.add(
                    (
                        meas_sl_uri,
                        OM.hasValue,
                        Literal(sl_point_est, datatype=XSD.double),
                    )
                )
                g.add((meas_sl_uri, OM.hasUnit, OM.millimetre))

                g.add((rv_sl_uri, RDF.type, UQ.RandomVariable))
                g.add(
                    (
                        rv_sl_uri,
                        RDFS.label,
                        Literal(
                            f"SL Distribution (Gear {i:06d}, Tooth {t})", lang="en"
                        ),
                    )
                )
                g.add(
                    (
                        rv_sl_uri,
                        UQ.hasDistribution,
                        Literal(sl_gmm_json, datatype=GMM_DATATYPE),
                    )
                )

                tooth_idx += 1

            # Motor (one per gear, for U3)
            motor_uri = EX[f"motor_{i:06d}"]
            speed_char_uri = EX[f"speedchar_{i:06d}"]
            torque_char_uri = EX[f"torquechar_{i:06d}"]

            g.add((motor_uri, RDF.type, AG.Motor))
            g.add((motor_uri, RDFS.label, Literal(f"Motor #{i:06d}", lang="en")))
            g.add((motor_uri, AG.hasSpeedCharacteristic, speed_char_uri))
            g.add((motor_uri, AG.hasTorqueCharacteristic, torque_char_uri))
            g.add((grinder_uri, AG.hasMotor, motor_uri))
            g.add((motor_uri, AG.hasCrownGear, gear_uri))

            g.add((speed_char_uri, RDF.type, AG.RotationalSpeed))
            g.add(
                (
                    speed_char_uri,
                    RDFS.label,
                    Literal(f"Speed Characteristic (Motor {i:06d})", lang="en"),
                )
            )

            g.add((torque_char_uri, RDF.type, AG.Torque))
            g.add(
                (
                    torque_char_uri,
                    RDFS.label,
                    Literal(f"Torque Characteristic (Motor {i:06d})", lang="en"),
                )
            )

            # Speed measurement
            speed_gmm, speed_point = self.gmm_gen.generate_speed()
            meas_speed_uri = EX[f"meas_speed_{i:06d}"]
            rv_speed_uri = EX[f"rv_speed_{i:06d}"]

            g.add((meas_speed_uri, RDF.type, OM.Measure))
            g.add(
                (
                    meas_speed_uri,
                    RDFS.label,
                    Literal(f"Speed Measurement (Motor {i:06d})", lang="en"),
                )
            )
            g.add((meas_speed_uri, CFM.measuresCharacteristic, speed_char_uri))
            g.add((meas_speed_uri, CFM.hasProbabilisticValue, rv_speed_uri))
            g.add(
                (meas_speed_uri, OM.hasValue, Literal(speed_point, datatype=XSD.double))
            )
            g.add((meas_speed_uri, OM.hasUnit, OM.radianPerSecond))

            g.add((rv_speed_uri, RDF.type, UQ.RandomVariable))
            g.add(
                (
                    rv_speed_uri,
                    UQ.hasDistribution,
                    Literal(speed_gmm, datatype=GMM_DATATYPE),
                )
            )

            # Torque measurement
            torque_gmm, torque_point = self.gmm_gen.generate_torque()
            meas_torque_uri = EX[f"meas_torque_{i:06d}"]
            rv_torque_uri = EX[f"rv_torque_{i:06d}"]

            g.add((meas_torque_uri, RDF.type, OM.Measure))
            g.add(
                (
                    meas_torque_uri,
                    RDFS.label,
                    Literal(f"Torque Measurement (Motor {i:06d})", lang="en"),
                )
            )
            g.add((meas_torque_uri, CFM.measuresCharacteristic, torque_char_uri))
            g.add((meas_torque_uri, CFM.hasProbabilisticValue, rv_torque_uri))
            g.add(
                (
                    meas_torque_uri,
                    OM.hasValue,
                    Literal(torque_point, datatype=XSD.double),
                )
            )
            g.add((meas_torque_uri, OM.hasUnit, OM.newtonMetre))

            g.add((rv_torque_uri, RDF.type, UQ.RandomVariable))
            g.add(
                (
                    rv_torque_uri,
                    UQ.hasDistribution,
                    Literal(torque_gmm, datatype=GMM_DATATYPE),
                )
            )

            # Spindle (one per gear, for U4)
            spindle_uri = EX[f"spindle_{i:06d}"]
            diam_char_uri = EX[f"diamchar_{i:06d}"]

            g.add((spindle_uri, RDF.type, AG.Spindle))
            g.add((spindle_uri, RDFS.label, Literal(f"Spindle #{i:06d}", lang="en")))
            g.add((spindle_uri, AG.hasDiameterCharacteristic, diam_char_uri))
            g.add((grinder_uri, AG.hasSpindle, spindle_uri))

            g.add((diam_char_uri, RDF.type, AG.Diameter))
            g.add(
                (
                    diam_char_uri,
                    RDFS.label,
                    Literal(f"Diameter Characteristic (Spindle {i:06d})", lang="en"),
                )
            )

            base_diam_gmm, _ = self.gmm_gen.generate_diameter()

            for c_idx in range(1, CALIPER_MEASUREMENTS_PER_SPINDLE + 1):
                diam_gmm, diam_point = self.gmm_gen.generate_diameter_consistent(
                    base_diam_gmm
                )
                meas_uri = EX[f"meas_caliper_{i:06d}_{c_idx}"]
                rv_uri = EX[f"rv_caliper_{i:06d}_{c_idx}"]

                g.add((meas_uri, RDF.type, OM.Measure))
                g.add(
                    (
                        meas_uri,
                        RDFS.label,
                        Literal(
                            f"Caliper Measurement {c_idx} (Spindle {i:06d})", lang="en"
                        ),
                    )
                )
                g.add((meas_uri, CFM.measuresCharacteristic, diam_char_uri))
                g.add((meas_uri, CFM.hasProbabilisticValue, rv_uri))
                g.add((meas_uri, OM.hasValue, Literal(diam_point, datatype=XSD.double)))
                g.add((meas_uri, OM.hasUnit, OM.millimetre))

                g.add((rv_uri, RDF.type, UQ.RandomVariable))
                g.add(
                    (
                        rv_uri,
                        RDFS.label,
                        Literal(
                            f"Caliper Distribution {c_idx} (Spindle {i:06d})", lang="en"
                        ),
                    )
                )
                g.add(
                    (
                        rv_uri,
                        UQ.hasDistribution,
                        Literal(diam_gmm, datatype=GMM_DATATYPE),
                    )
                )

            for l_idx in range(1, LASER_MEASUREMENTS_PER_SPINDLE + 1):
                diam_gmm, diam_point = self.gmm_gen.generate_diameter_consistent(
                    base_diam_gmm
                )
                meas_uri = EX[f"meas_laser_{i:06d}_{l_idx}"]
                rv_uri = EX[f"rv_laser_{i:06d}_{l_idx}"]

                g.add((meas_uri, RDF.type, OM.Measure))
                g.add(
                    (
                        meas_uri,
                        RDFS.label,
                        Literal(
                            f"Laser Measurement {l_idx} (Spindle {i:06d})", lang="en"
                        ),
                    )
                )
                g.add((meas_uri, CFM.measuresCharacteristic, diam_char_uri))
                g.add((meas_uri, CFM.hasProbabilisticValue, rv_uri))
                g.add((meas_uri, OM.hasValue, Literal(diam_point, datatype=XSD.double)))
                g.add((meas_uri, OM.hasUnit, OM.millimetre))

                g.add((rv_uri, RDF.type, UQ.RandomVariable))
                g.add(
                    (
                        rv_uri,
                        RDFS.label,
                        Literal(
                            f"Laser Distribution {l_idx} (Spindle {i:06d})", lang="en"
                        ),
                    )
                )
                g.add(
                    (
                        rv_uri,
                        UQ.hasDistribution,
                        Literal(diam_gmm, datatype=GMM_DATATYPE),
                    )
                )

            if i % 100 == 0 or i == num_gears:
                elapsed = time.time() - start_time
                rate = i / elapsed if elapsed > 0 else 0
                print(
                    f"  [{scale_label}] {i}/{num_gears} gears "
                    f"({i * 100 / num_gears:.1f}%) - {rate:.1f} gears/s"
                )

        triple_count = len(g)
        elapsed_total = time.time() - start_time

        gear_inconsistent_count = 0
        for gi in range(num_gears):
            start = gi * TEETH_PER_GEAR
            end = start + TEETH_PER_GEAR
            if overlap_assignment is not None:
                # JSD mode: all teeth have some overlap
                gear_inconsistent_count += 1  # Every gear has inconsistent pairs
            elif any(inconsistent_mask[start:end]):
                gear_inconsistent_count += 1

        metadata = {
            "scale": scale_label,
            "num_gears": num_gears,
            "teeth_per_gear": TEETH_PER_GEAR,
            "total_teeth": total_teeth,
            "triple_count": triple_count,
            "worn_teeth_count": worn_teeth_count,
            "worn_teeth_pct": round(worn_teeth_count / total_teeth * 100, 2),
            "inconsistent_pairs_count": inconsistent_pairs_count,
            "inconsistent_pairs_pct": round(
                inconsistent_pairs_count / total_teeth * 100, 2
            ),
            "gears_with_inconsistent_pairs": gear_inconsistent_count,
            "gears_with_inconsistent_pct": round(
                gear_inconsistent_count / num_gears * 100, 2
            ),
            "num_motors": num_gears,
            "num_spindles": num_gears,
            "caliper_measurements_per_spindle": CALIPER_MEASUREMENTS_PER_SPINDLE,
            "laser_measurements_per_spindle": LASER_MEASUREMENTS_PER_SPINDLE,
            "random_seed": self.seed,
            "generation_time_seconds": round(elapsed_total, 2),
            "covariance_types": "mixed (full + diag)",
        }
        if self.forced_k is not None:
            metadata["forced_k"] = self.forced_k

        return g, metadata


# =============================================================================
# Validation
# =============================================================================
def validate_dataset(g: Graph, metadata: dict) -> list:
    """Run validation checks on the generated dataset."""
    results = []
    num_gears = metadata["num_gears"]
    total_teeth = metadata["total_teeth"]

    triple_count = metadata["triple_count"]
    expected_min = num_gears * 250
    expected_max = num_gears * 350
    tc_pass = expected_min <= triple_count <= expected_max
    results.append(
        (
            "triple_count_range",
            tc_pass,
            f"{triple_count} triples (expected {expected_min}-{expected_max})",
        )
    )

    worn_pct = metadata["worn_teeth_pct"]
    worn_pass = worn_pct >= 15.0
    results.append(
        ("worn_teeth_pct", worn_pass, f"{worn_pct:.1f}% worn teeth (need >= 15%)")
    )

    incon_pct = metadata["inconsistent_pairs_pct"]
    incon_pass = incon_pct >= 15.0
    results.append(
        (
            "inconsistent_pairs_pct",
            incon_pass,
            f"{incon_pct:.1f}% inconsistent pairs (need >= 15%)",
        )
    )

    gmm_valid = True
    gmm_msg = "All sampled GMM literals valid"
    sample_size = min(50, total_teeth)
    rv_query = (
        """
    PREFIX uq: <http://example.org/ontology/uncertainty#>
    SELECT ?dist WHERE { ?rv uq:hasDistribution ?dist } LIMIT %d
    """
        % sample_size
    )

    for row in g.query(rv_query):
        try:
            gmm = json.loads(str(row.dist))
            weight_sum = sum(gmm["weights"])
            if abs(weight_sum - 1.0) > 1e-4:
                gmm_valid = False
                gmm_msg = f"Weight sum = {weight_sum} (expected 1.0)"
                break
            if gmm["K"] != len(gmm["means"]):
                gmm_valid = False
                gmm_msg = f"K={gmm['K']} but {len(gmm['means'])} means"
                break
            if gmm["covariance_type"] not in ("full", "diag"):
                gmm_valid = False
                gmm_msg = f"Unknown covariance_type: {gmm['covariance_type']}"
                break
        except (json.JSONDecodeError, KeyError) as e:
            gmm_valid = False
            gmm_msg = f"Invalid GMM literal: {e}"
            break

    results.append(("gmm_literals_valid", gmm_valid, gmm_msg))

    return results


# =============================================================================
# Main Entry Point
# =============================================================================
def main():
    parser = argparse.ArgumentParser(
        description="Generate ProbSPARQL benchmark datasets"
    )
    parser.add_argument(
        "--scales",
        nargs="+",
        default=list(SCALE_CONFIGS.keys()),
        choices=list(SCALE_CONFIGS.keys()),
        help="Scale factors to generate (default: all)",
    )
    parser.add_argument(
        "--seed", type=int, default=42, help="Random seed (default: 42)"
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        default=None,
        help="Output directory (default: ../data relative to script)",
    )
    parser.add_argument(
        "--k",
        type=int,
        default=None,
        metavar="K",
        help="Force all GMM distributions to use exactly K components. "
             "When set, output files are named exp1_{scale}_K{k}.ttl instead of benchmark_{scale}.ttl.",
    )
    parser.add_argument(
        "--skip-validation", action="store_true", help="Skip post-generation validation"
    )
    args = parser.parse_args()

    if args.output_dir:
        output_dir = args.output_dir
    else:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        output_dir = os.path.join(script_dir, "..", "data")
    os.makedirs(output_dir, exist_ok=True)

    all_metadata = {}
    overall_start = time.time()

    for scale in args.scales:
        config = SCALE_CONFIGS[scale]
        num_gears = config["num_gears"]
        label = config["label"]
        overlap_mode = config.get("overlap_mode", None)

        print(f"\n{'=' * 60}")
        print(f"Generating {scale}: {num_gears} gears ({label})")
        if overlap_mode:
            print(f"Overlap mode: {overlap_mode}")
        print(f"{'=' * 60}")

        generator = DatasetGenerator(seed=args.seed, forced_k=args.k)
        g, metadata = generator.generate(num_gears, scale, overlap_mode)

        # Add overlap info to metadata
        if overlap_mode:
            metadata["overlap_mode"] = overlap_mode

        if not args.skip_validation:
            print(f"\n  Validating {scale}...")
            checks = validate_dataset(g, metadata)
            all_passed = True
            for name, passed, msg in checks:
                status = "PASS" if passed else "FAIL"
                print(f"    [{status}] {name}: {msg}")
                if not passed:
                    all_passed = False
            metadata["validation_passed"] = all_passed
        else:
            metadata["validation_passed"] = "skipped"

        if args.k is not None:
            ttl_path = os.path.join(output_dir, f"exp1_{scale}_K{args.k}.ttl")
        else:
            ttl_path = os.path.join(output_dir, f"benchmark_{scale}.ttl")
        print(f"\n  Writing {ttl_path}...")
        g.serialize(destination=ttl_path, format="turtle")
        file_size_mb = os.path.getsize(ttl_path) / (1024 * 1024)
        metadata["file_size_mb"] = round(file_size_mb, 2)
        print(f"  Done: {metadata['triple_count']} triples, {file_size_mb:.1f} MB")

        all_metadata[scale] = metadata

    meta_path = os.path.join(output_dir, "benchmark_metadata.json")
    with open(meta_path, "w") as f:
        json.dump(all_metadata, f, indent=2)
    print(f"\nMetadata saved to {meta_path}")

    overall_elapsed = time.time() - overall_start
    print(f"\nTotal generation time: {overall_elapsed:.1f}s")


if __name__ == "__main__":
    main()
