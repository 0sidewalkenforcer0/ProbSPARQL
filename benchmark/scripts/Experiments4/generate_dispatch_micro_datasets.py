#!/usr/bin/env python3
"""
Generate Exp4 dispatch/micro TTL datasets for remote Fuseki loading.

These files are not consumed by run_exp4.sh directly. They are preparation
artifacts for the Fujitsu Fuseki server, whose services should be named after
the generated file stem, for example:

  exp4_dispatch_gmm_K3
  exp4_micro_hist_B50

Output directory defaults to benchmark/data/exp4.
"""
import argparse
import json
import os
import random

from rdflib import Graph, Literal, Namespace, URIRef
from rdflib.namespace import RDF, RDFS


CFM = Namespace("http://example.org/ontology/cfm#")
UQ = Namespace("http://example.org/ontology/uncertainty#")
EX = Namespace("http://example.org/data/")
BENCH = Namespace("http://example.org/benchmark/exp4#")

GMM_DT = URIRef("http://example.org/ontology/uncertainty#gmmLiteral")
HIST_DT = URIRef("http://example.org/ontology/uncertainty#histLiteral")
DIR_DT = URIRef("http://example.org/ontology/uncertainty#dirichletLiteral")


def rounded_probs(values):
    total = sum(values)
    probs = [round(v / total, 6) for v in values]
    if len(probs) > 1:
        probs[-1] = round(probs[-1] + (1.0 - sum(probs)), 6)
    return probs


def make_gmm(k, rng):
    weights = rounded_probs([rng.uniform(0.1, 1.0) for _ in range(k)])
    means = [[round(rng.uniform(13.0, 17.0), 4)] for _ in range(k)]
    covs = [[round(rng.uniform(0.001, 0.1), 6)] for _ in range(k)]
    return json.dumps({
        "n_components": k,
        "dimensions": 1,
        "covariance_type": "diag",
        "weights": weights,
        "means": means,
        "covariances": covs,
    })


def make_hist(b, rng):
    lo, hi = 5.0, 25.0
    width = (hi - lo) / b
    edges = [round(lo + i * width, 6) for i in range(b + 1)]
    weights = rounded_probs([rng.randint(1, 50) for _ in range(b)])
    return json.dumps({
        "dimensions": 1,
        "edges": [edges],
        "weights": weights,
    })


def make_dir(k, rng):
    return json.dumps({
        "alphas": [round(rng.uniform(0.5, 5.0), 6) for _ in range(k)]
    })


def graph():
    g = Graph()
    g.bind("cfm", CFM)
    g.bind("uq", UQ)
    g.bind("ex", EX)
    g.bind("bench", BENCH)
    g.bind("rdfs", RDFS)
    return g


def write_graph(g, output_dir, name):
    os.makedirs(output_dir, exist_ok=True)
    path = os.path.join(output_dir, f"{name}.ttl")
    g.serialize(destination=path, format="turtle")
    print(f"  {name}.ttl ({len(g)} triples)")


def add_has_distribution_dataset(output_dir, name, n, datatype, literal_fn, rng):
    g = graph()
    dataset_uri = EX[name]
    g.add((dataset_uri, RDF.type, BENCH.Exp4OperationDataset))
    g.add((dataset_uri, RDFS.label, Literal(f"Exp4 operation dataset {name}")))
    g.add((dataset_uri, BENCH.entityCount, Literal(n)))
    for i in range(1, n + 1):
        e = EX[f"{name}_component_{i:06d}"]
        rv = EX[f"{name}_rv_{i:06d}"]
        g.add((dataset_uri, BENCH.hasEntity, e))
        g.add((e, RDF.type, CFM.Component))
        g.add((e, CFM.hasProbabilisticValue, rv))
        g.add((rv, RDF.type, CFM.RandomVariable))
        g.add((rv, CFM.hasDistribution, Literal(literal_fn(), datatype=datatype)))
    write_graph(g, output_dir, name)


def add_dir_dispatch_dataset(output_dir, name, n, k, rng):
    g = graph()
    dataset_uri = EX[name]
    g.add((dataset_uri, RDF.type, BENCH.Exp4OperationDataset))
    g.add((dataset_uri, RDFS.label, Literal(f"Exp4 Dirichlet dispatch dataset {name}")))
    g.add((dataset_uri, BENCH.entityCount, Literal(n)))
    for i in range(1, n + 1):
        e = EX[f"{name}_component_{i:06d}"]
        rv = EX[f"{name}_rv_{i:06d}"]
        measured = make_dir(k, rng)
        expected = make_dir(k, rng)
        g.add((dataset_uri, BENCH.hasEntity, e))
        g.add((e, RDF.type, CFM.Component))
        g.add((e, CFM.hasProbabilisticValue, rv))
        g.add((rv, RDF.type, CFM.RandomVariable))
        g.add((rv, CFM.hasDistribution, Literal(measured, datatype=DIR_DT)))
        g.add((e, CFM.hasMeasuredComposition, Literal(measured, datatype=DIR_DT)))
        g.add((e, CFM.hasExpectedComposition, Literal(expected, datatype=DIR_DT)))
    write_graph(g, output_dir, name)


def main():
    parser = argparse.ArgumentParser(description="Generate Exp4 dispatch/micro remote datasets")
    parser.add_argument("--output-dir", default=None)
    parser.add_argument("--dispatch-n", type=int, default=10)
    parser.add_argument("--micro-n", type=int, default=1000)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    script_dir = os.path.dirname(os.path.realpath(__file__))
    project_root = os.path.realpath(os.path.join(script_dir, "../../.."))
    output_dir = args.output_dir or os.path.join(project_root, "benchmark/data/exp4")
    rng = random.Random(args.seed)

    print("=== Exp4 dispatch/micro dataset generator ===")
    print(f"Output: {output_dir}")

    add_has_distribution_dataset(
        output_dir, "exp4_dispatch_gmm_K3", args.dispatch_n, GMM_DT, lambda: make_gmm(3, rng), rng)
    add_has_distribution_dataset(
        output_dir, "exp4_dispatch_hist_B50", args.dispatch_n, HIST_DT, lambda: make_hist(50, rng), rng)
    add_dir_dispatch_dataset(output_dir, "exp4_dispatch_dir_k4", args.dispatch_n, 4, rng)

    add_has_distribution_dataset(
        output_dir, "exp4_micro_gmm_K3", args.micro_n, GMM_DT, lambda: make_gmm(3, rng), rng)
    for b in (20, 50, 100):
        add_has_distribution_dataset(
            output_dir, f"exp4_micro_hist_B{b}", args.micro_n, HIST_DT, lambda b=b: make_hist(b, rng), rng)
    for k in (4, 10, 20):
        add_has_distribution_dataset(
            output_dir, f"exp4_micro_dir_k{k}", args.micro_n, DIR_DT, lambda k=k: make_dir(k, rng), rng)

    print("Done.")


if __name__ == "__main__":
    main()
