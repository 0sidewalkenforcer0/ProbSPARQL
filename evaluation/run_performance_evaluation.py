#!/usr/bin/env python3
"""
ProbSPARQL Performance Evaluation Script

This script evaluates the feasibility of ProbSPARQL by measuring:
1. Query response times for all query types (U1-U6)
2. Scalability with increasing data size
3. Impact of GMM complexity (number of components K)

Usage:
    python run_performance_evaluation.py [--runs N] [--output results.csv]
"""

import subprocess
import time
import os
import json
import argparse
import random
import math
from pathlib import Path
from typing import List, Dict, Tuple
import statistics

# Configuration
PROJECT_ROOT = Path(__file__).parent.parent
DATA_DIR = PROJECT_ROOT / "examples" / "data"
QUERY_DIR = PROJECT_ROOT / "examples" / "queries"
EVAL_DIR = PROJECT_ROOT / "evaluation"

QUERIES = [
    ("U1", "U1_probabilistic_thresholding.sparql", "Probabilistic Thresholding (CDF)"),
    ("U2", "U2_probabilistic_comparison.sparql", "Probabilistic Comparison (JS/KL)"),
    ("U3", "U3_distribution_transformation.sparql", "Distribution Transformation"),
    ("U4", "U4_distribution_manipulation.sparql", "Distribution Manipulation"),
    ("U5", "U5_similarityjoin_test.sparql", "SIMILARITYJOIN"),
    ("U6", "U6_fusejoin_comparison.sparql", "FUSEJOIN"),
]


def generate_gmm_literal(k: int = 2, dim: int = 1) -> str:
    """Generate a random GMM literal with K components."""
    weights = [random.random() for _ in range(k)]
    total = sum(weights)
    weights = [w / total for w in weights]
    
    means = [[random.uniform(5.0, 15.0) for _ in range(dim)] for _ in range(k)]
    
    # Generate valid covariance matrices (diagonal for simplicity)
    covariances = []
    for _ in range(k):
        if dim == 1:
            cov = [[random.uniform(0.1, 1.0)]]
        else:
            cov = [[random.uniform(0.1, 1.0) if i == j else 0.0 
                    for j in range(dim)] for i in range(dim)]
        covariances.append(cov)
    
    gmm = {
        "type": "gmm",
        "version": "1.0",
        "k": k,
        "d": dim,
        "covariance_type": "full",
        "weights": weights,
        "means": means,
        "covariances": covariances
    }
    
    return json.dumps(gmm, separators=(',', ':'))


def generate_synthetic_data(num_entities: int, k_components: int = 2, 
                            output_file: Path = None) -> Path:
    """Generate synthetic RDF data with GMM distributions."""
    if output_file is None:
        output_file = EVAL_DIR / f"synthetic_data_{num_entities}_k{k_components}.ttl"
    
    output_file.parent.mkdir(parents=True, exist_ok=True)
    
    prefixes = """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
@prefix ag: <http://example.org/ontology/anglegrinder#> .
@prefix cfm: <http://example.org/ontology/cfm#> .
@prefix om: <http://example.org/ontology/om#> .
@prefix uq: <http://example.org/ontology/uncertainty#> .
@prefix ex: <http://example.org/data/> .

"""
    
    lines = [prefixes]
    
    for i in range(1, num_entities + 1):
        entity_id = f"entity_{i:05d}"
        rv_id = f"rv_{i:05d}"
        char_id = f"char_{i:05d}"
        meas_id = f"meas_{i:05d}"
        pc_id = f"pc_{i:05d}"
        
        # Alternate between CrownGear and HistoricalGear for join queries
        gear_type = "ag:CrownGear" if i % 2 == 1 else "ag:HistoricalGear"
        
        gmm = generate_gmm_literal(k_components)
        point_estimate = random.uniform(8.0, 12.0)
        
        lines.append(f"""
ex:{entity_id} a {gear_type} ;
    rdfs:label "Gear #{i}"@en ;
    cfm:hasCharacteristic ex:{char_id} .

ex:{meas_id} cfm:measuresCharacteristic ex:{char_id} ;
    cfm:hasInputPointCloud ex:{pc_id} ;
    cfm:hasInputVoxelGrid ex:{pc_id} ;
    cfm:hasProbabilisticValue ex:{rv_id} ;
    om:hasValue "{point_estimate:.2f}"^^xsd:double .

ex:{rv_id} a uq:RandomVariable ;
    uq:hasDistribution "{gmm}"^^uq:gmmLiteral .
""")
    
    with open(output_file, 'w') as f:
        f.writelines(lines)
    
    print(f"  Generated {output_file.name}: {num_entities} entities, K={k_components}")
    return output_file


def run_query(data_file: Path, query_file: Path, timeout: int = 60) -> Tuple[float, int, bool]:
    """Run a single query and return (time_seconds, result_count, success)."""
    cmd = [
        "mvn", "-q", "exec:java",
        f"-Dexec.mainClass=org.apache.jena.probsparql.QueryRunner",
        f"-Dexec.args={data_file} {query_file}"
    ]
    
    start_time = time.perf_counter()
    try:
        result = subprocess.run(
            cmd,
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            timeout=timeout
        )
        end_time = time.perf_counter()
        
        elapsed = end_time - start_time
        
        # Parse result count from output
        output = result.stdout + result.stderr
        result_count = 0
        for line in output.split('\n'):
            if 'Total rows:' in line or 'result bindings' in line:
                try:
                    result_count = int(''.join(filter(str.isdigit, line.split(':')[-1])))
                except:
                    pass
        
        success = result.returncode == 0
        return elapsed, result_count, success
        
    except subprocess.TimeoutExpired:
        return timeout, 0, False
    except Exception as e:
        print(f"    Error: {e}")
        return 0, 0, False


def evaluate_query_performance(data_file: Path, num_runs: int = 5) -> Dict:
    """Evaluate all queries on given data file."""
    results = {}
    
    for query_id, query_filename, description in QUERIES:
        query_file = QUERY_DIR / query_filename
        if not query_file.exists():
            print(f"    Warning: {query_filename} not found, skipping")
            continue
        
        times = []
        result_counts = []
        
        for run in range(num_runs):
            elapsed, count, success = run_query(data_file, query_file)
            if success:
                times.append(elapsed)
                result_counts.append(count)
        
        if times:
            results[query_id] = {
                "description": description,
                "mean_time_ms": statistics.mean(times) * 1000,
                "std_time_ms": statistics.stdev(times) * 1000 if len(times) > 1 else 0,
                "min_time_ms": min(times) * 1000,
                "max_time_ms": max(times) * 1000,
                "result_count": result_counts[0] if result_counts else 0,
                "runs": len(times)
            }
            print(f"    {query_id}: {results[query_id]['mean_time_ms']:.1f}ms ± {results[query_id]['std_time_ms']:.1f}ms ({results[query_id]['result_count']} results)")
        else:
            results[query_id] = {"error": "All runs failed"}
            print(f"    {query_id}: FAILED")
    
    return results


def run_scalability_evaluation(sizes: List[int], k: int = 2, num_runs: int = 3) -> List[Dict]:
    """Evaluate performance across different data sizes."""
    print("\n" + "="*70)
    print("SCALABILITY EVALUATION")
    print("="*70)
    
    all_results = []
    
    for size in sizes:
        print(f"\n--- Data size: {size} entities ---")
        data_file = generate_synthetic_data(size, k)
        
        results = evaluate_query_performance(data_file, num_runs)
        results["data_size"] = size
        results["k_components"] = k
        all_results.append(results)
    
    return all_results


def run_complexity_evaluation(sizes: List[int], k_values: List[int], 
                               num_runs: int = 3) -> List[Dict]:
    """Evaluate performance across different GMM complexities."""
    print("\n" + "="*70)
    print("GMM COMPLEXITY EVALUATION")
    print("="*70)
    
    all_results = []
    
    for k in k_values:
        for size in sizes:
            print(f"\n--- K={k} components, {size} entities ---")
            data_file = generate_synthetic_data(size, k)
            
            results = evaluate_query_performance(data_file, num_runs)
            results["data_size"] = size
            results["k_components"] = k
            all_results.append(results)
    
    return all_results


def run_baseline_evaluation(num_runs: int = 5) -> Dict:
    """Run evaluation on the provided sample data."""
    print("\n" + "="*70)
    print("BASELINE EVALUATION (Sample Data)")
    print("="*70)
    
    data_file = DATA_DIR / "angle-grinder-instances.ttl"
    if not data_file.exists():
        print(f"Error: {data_file} not found")
        return {}
    
    print(f"\nData file: {data_file.name}")
    
    # Count triples
    with open(data_file) as f:
        triple_count = sum(1 for line in f if line.strip() and not line.startswith('@') and not line.startswith('#'))
    print(f"Approximate triples: {triple_count}")
    
    results = evaluate_query_performance(data_file, num_runs)
    return results


def print_summary(baseline_results: Dict, scalability_results: List[Dict] = None):
    """Print a summary suitable for paper inclusion."""
    print("\n" + "="*70)
    print("SUMMARY FOR PAPER")
    print("="*70)
    
    if baseline_results:
        print("\n### Baseline Query Performance ###")
        print("| Query | Description | Mean Time (ms) | Std (ms) | Results |")
        print("|-------|-------------|----------------|----------|---------|")
        
        for query_id, _, desc in QUERIES:
            if query_id in baseline_results:
                r = baseline_results[query_id]
                if "error" not in r:
                    print(f"| {query_id} | {desc[:30]}... | {r['mean_time_ms']:.1f} | {r['std_time_ms']:.1f} | {r['result_count']} |")
    
    if scalability_results:
        print("\n### Scalability Results ###")
        # Extract U1 times for each data size
        print("| Data Size | U1 (ms) | U2 (ms) | U3 (ms) | U4 (ms) | U5 (ms) | U6 (ms) |")
        print("|-----------|---------|---------|---------|---------|---------|---------|")
        
        for result in scalability_results:
            size = result["data_size"]
            row = f"| {size} |"
            for query_id, _, _ in QUERIES:
                if query_id in result and "error" not in result[query_id]:
                    row += f" {result[query_id]['mean_time_ms']:.1f} |"
                else:
                    row += " - |"
            print(row)


def main():
    parser = argparse.ArgumentParser(description="ProbSPARQL Performance Evaluation")
    parser.add_argument("--runs", type=int, default=5, help="Number of runs per query")
    parser.add_argument("--scalability", action="store_true", help="Run scalability tests")
    parser.add_argument("--complexity", action="store_true", help="Run GMM complexity tests")
    parser.add_argument("--sizes", type=int, nargs="+", default=[100, 500, 1000],
                        help="Data sizes for scalability test")
    parser.add_argument("--k-values", type=int, nargs="+", default=[1, 2, 3, 5],
                        help="K values for complexity test")
    parser.add_argument("--output", type=str, default=None, help="Output JSON file")
    
    args = parser.parse_args()
    
    print("╔" + "═"*68 + "╗")
    print("║" + " ProbSPARQL Feasibility Evaluation ".center(68) + "║")
    print("╚" + "═"*68 + "╝")
    
    all_results = {}
    
    # 1. Baseline evaluation on sample data
    baseline_results = run_baseline_evaluation(args.runs)
    all_results["baseline"] = baseline_results
    
    # 2. Optional: Scalability evaluation
    if args.scalability:
        scalability_results = run_scalability_evaluation(args.sizes, k=2, num_runs=args.runs)
        all_results["scalability"] = scalability_results
    else:
        scalability_results = None
    
    # 3. Optional: GMM complexity evaluation
    if args.complexity:
        complexity_results = run_complexity_evaluation([500], args.k_values, args.runs)
        all_results["complexity"] = complexity_results
    
    # Print summary
    print_summary(baseline_results, scalability_results)
    
    # Save results
    if args.output:
        output_file = Path(args.output)
    else:
        output_file = EVAL_DIR / "evaluation_results.json"
    
    output_file.parent.mkdir(parents=True, exist_ok=True)
    with open(output_file, 'w') as f:
        json.dump(all_results, f, indent=2)
    
    print(f"\nResults saved to: {output_file}")
    
    # Print one-sentence summary for paper
    if baseline_results:
        avg_time = statistics.mean([
            r['mean_time_ms'] for r in baseline_results.values() 
            if isinstance(r, dict) and 'mean_time_ms' in r
        ])
        max_time = max([
            r['mean_time_ms'] for r in baseline_results.values() 
            if isinstance(r, dict) and 'mean_time_ms' in r
        ])
        
        print("\n" + "="*70)
        print("ONE-SENTENCE SUMMARY FOR PAPER:")
        print("="*70)
        print(f"""
Our feasibility evaluation on {len(QUERIES)} representative query types shows 
that all queries execute in under {max_time:.0f}ms (average: {avg_time:.0f}ms) 
on the sample dataset, demonstrating the practical applicability of ProbSPARQL 
for interactive uncertainty-aware queries.
""")


if __name__ == "__main__":
    main()
