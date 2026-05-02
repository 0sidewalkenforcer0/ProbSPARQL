#!/usr/bin/env python3
"""
Generate multi-dimensional GMM benchmark datasets.

For each (d, K) configuration, generate a fixed number of uq:RandomVariable nodes
encoded as uq:gmmLiteral with:
- dimensions d in {1, 2, 4, 8}
- mixture components K in {1, 3, 5, 10}
- covariance_type = "diag"
- fixed seed for reproducibility

Output files are written under benchmark/multiDdata/ as:
  multid_d{d}_k{K}.ttl

A machine-readable manifest is also written to:
  benchmark/multiDdata/manifest.json
"""

import json
import math
import os
import random
from pathlib import Path

from rdflib import Graph, Literal, Namespace, URIRef
from rdflib.namespace import RDF, RDFS, XSD


CFM = Namespace("http://example.org/ontology/cfm#")
UQ = Namespace("http://example.org/ontology/uncertainty#")
EX = Namespace("http://example.org/data/multid/")
BENCH = Namespace("http://example.org/benchmark/multid#")
GMM_DATATYPE = URIRef("http://example.org/ontology/uncertainty#gmmLiteral")

DIMS = [1, 2, 4, 8]
COMPONENT_COUNTS = [1, 3, 5, 10]
RV_COUNT = 100
MASTER_SEED = 20260501
MEAN_LOW = -10.0
MEAN_HIGH = 10.0
VAR_LOW = 0.05
VAR_HIGH = 2.00


def sample_normalized_positive_weights(rng: random.Random, k: int):
    raw = [rng.uniform(0.1, 1.0) for _ in range(k)]
    total = sum(raw)
    weights = [value / total for value in raw]

    rounded = [round(value, 6) for value in weights[:-1]]
    last = round(1.0 - sum(rounded), 6)
    rounded.append(last)
    return rounded



def sample_mean_vector(rng: random.Random, d: int):
    return [round(rng.uniform(MEAN_LOW, MEAN_HIGH), 6) for _ in range(d)]



def sample_diag_variances(rng: random.Random, d: int):
    return [round(rng.uniform(VAR_LOW, VAR_HIGH), 6) for _ in range(d)]



def make_gmm_literal(rng: random.Random, d: int, k: int):
    weights = sample_normalized_positive_weights(rng, k)
    means = [sample_mean_vector(rng, d) for _ in range(k)]
    covariances = [sample_diag_variances(rng, d) for _ in range(k)]
    obj = {
        "n_components": k,
        "dimensions": d,
        "covariance_type": "diag",
        "weights": weights,
        "means": means,
        "covariances": covariances,
    }
    return Literal(json.dumps(obj, separators=(",", ":")), datatype=GMM_DATATYPE)



def dataset_seed(d: int, k: int):
    return MASTER_SEED + d * 100 + k



def build_graph(d: int, k: int, rv_count: int):
    rng = random.Random(dataset_seed(d, k))

    graph = Graph()
    graph.bind("cfm", CFM)
    graph.bind("uq", UQ)
    graph.bind("ex", EX)
    graph.bind("bench", BENCH)
    graph.bind("rdfs", RDFS)
    graph.bind("xsd", XSD)

    dataset_uri = EX[f"dataset_d{d}_k{k}"]
    graph.add((dataset_uri, RDF.type, BENCH.MultiDimBenchmarkDataset))
    graph.add((dataset_uri, RDFS.label, Literal(f"Multi-dimensional GMM benchmark d={d}, K={k}", lang="en")))
    graph.add((dataset_uri, BENCH.dimensions, Literal(d, datatype=XSD.integer)))
    graph.add((dataset_uri, BENCH.nComponents, Literal(k, datatype=XSD.integer)))
    graph.add((dataset_uri, BENCH.rvCount, Literal(rv_count, datatype=XSD.integer)))
    graph.add((dataset_uri, BENCH.covarianceType, Literal("diag")))
    graph.add((dataset_uri, BENCH.seed, Literal(dataset_seed(d, k), datatype=XSD.integer)))

    for index in range(1, rv_count + 1):
        entity_uri = EX[f"entity_d{d}_k{k}_{index:04d}"]
        rv_uri = EX[f"rv_d{d}_k{k}_{index:04d}"]

        graph.add((entity_uri, RDF.type, CFM.Component))
        graph.add((entity_uri, CFM.hasProbabilisticValue, rv_uri))
        graph.add((rv_uri, RDF.type, UQ.RandomVariable))
        graph.add((rv_uri, UQ.hasDistribution, make_gmm_literal(rng, d, k)))

    return graph



def main():
    output_dir = Path(__file__).resolve().parent
    manifest = {
        "rv_count_per_config": RV_COUNT,
        "dimensions": DIMS,
        "component_counts": COMPONENT_COUNTS,
        "covariance_type": "diag",
        "master_seed": MASTER_SEED,
        "files": [],
    }

    for d in DIMS:
        for k in COMPONENT_COUNTS:
            graph = build_graph(d, k, RV_COUNT)
            filename = f"multid_d{d}_k{k}.ttl"
            output_path = output_dir / filename
            graph.serialize(destination=str(output_path), format="turtle")
            manifest["files"].append(
                {
                    "file": filename,
                    "dimensions": d,
                    "n_components": k,
                    "rv_count": RV_COUNT,
                    "seed": dataset_seed(d, k),
                    "triples": len(graph),
                }
            )
            print(f"Wrote {filename} ({len(graph)} triples)")

    with open(output_dir / "manifest.json", "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)
        handle.write("\n")

    print("Done.")


if __name__ == "__main__":
    main()
