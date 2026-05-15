# ProbSPARQL Benchmark Implementation

This directory contains the active benchmark implementation used to evaluate the
ProbSPARQL prototype. The current benchmark harness is remote-first: Java
benchmark clients run locally and send SPARQL queries over HTTP to preloaded
Apache Jena Fuseki services, typically on the Fujitsu server used for the paper
experiments.

## Execution Model

All active experiment runners use the same setup:

- Remote endpoint template: pass `ENDPOINT_TEMPLATE`, for example `https://fujitsu.example.org/{dataset}/query`.
- Dataset binding: `{dataset}` is replaced with the logical dataset/service name expected by each experiment.
- Timing protocol: default `3` warm-up runs and `10` measured runs.
- Local data loading: disabled in the benchmark runners; TTL data must be generated separately and loaded into Fuseki before running the experiments.
- Output: generated CSVs and logs are written under `benchmark/results/expN`.

The shared HTTP helper is `src/main/java/org/apache/jena/probsparql/RemoteBenchmarkClient.java`.

## Common Commands

```bash
export ENDPOINT_TEMPLATE='https://fujitsu.example.org/{dataset}/query'

bash benchmark/scripts/Experiments1/component/run_exp1_component.sh
bash benchmark/scripts/Experiments1/dimension/run_exp1_dimension.sh
bash benchmark/scripts/Experiments1/permutation/run_exp1_permutation.sh
bash benchmark/scripts/Experiments2/run_exp2.sh
bash benchmark/scripts/Experiments3/run_exp3.sh
bash benchmark/scripts/Experiments4/run_exp4.sh
bash benchmark/scripts/Experiments5/run_exp5.sh
```

Set `SKIP_BUILD=1` to reuse an existing Maven build.

## Experiment 1: System Overhead and Robustness

Experiment 1 measures the cost of probabilistic scalar functions relative to
deterministic SPARQL baselines, plus two supplements for representation
robustness and dimensionality.

### Exp1 Component

- Runner: `benchmark/scripts/Experiments1/component/run_exp1_component.sh`
- Java class: `org.apache.jena.probsparql.ScalabilityBenchmark`
- Queries: `benchmark/queries/exp1/component`
- Default scales: `E1 E3 E5 E7`
- Default mixture counts: `K = 1, 3, 5, 10`
- Remote services: `exp1_{scale}_det` and `exp1_{scale}_K{k}`
- Outputs: `benchmark/results/exp1/component/exp1_raw.csv`, `exp1_summary.csv`

### Exp1 Dimension

- Runner: `benchmark/scripts/Experiments1/dimension/run_exp1_dimension.sh`
- Java class: `org.apache.jena.probsparql.Exp1DimensionBenchmark`
- Queries: `benchmark/queries/exp1/dimension`
- Default configuration: `scale = E5`, `K = 3`, dimensions `1, 2, 4, 8`
- Remote services: `exp1_E5_K3_D1`, `exp1_E5_K3_D2`, `exp1_E5_K3_D4`, `exp1_E5_K3_D8`
- Outputs: `benchmark/results/exp1/dimension/exp1_dimension_raw.csv`, `exp1_dimension_summary.csv`

### Exp1 Permutation

- Runner: `benchmark/scripts/Experiments1/permutation/run_exp1_permutation.sh`
- Java class: `org.apache.jena.probsparql.Exp1PermutationBenchmark`
- Queries: `benchmark/queries/exp1/permutation`
- Default configuration: `scale = E5`, `K = 3, 5, 10`
- Q4 default: `poly`, which uses `prob:jsd`; set `Q4_VARIANT=legacy` only when explicitly testing `prob:jsdivergence`
- Remote services: `exp1_{scale}_det`, `exp1_{scale}_K{k}`, `exp1_{scale}_K{k}_permuted`
- Outputs: `benchmark/results/exp1/permutation/exp1_permutation_raw.csv`, `exp1_permutation_summary.csv`

## Experiment 2: In-Engine Filtering vs DIVJOIN

Experiment 2 compares ordinary in-engine filtering plans with the dedicated
`DIVJOIN` similarity-decision operator over Exp1-style CT-vs-SL measurement
pairs.

- Runner: `benchmark/scripts/Experiments2/run_exp2.sh`
- Java class: `org.apache.jena.probsparql.Exp2Benchmark`
- Queries: `benchmark/queries/exp2`
- Compared plans: `InEngine_CheapFirst`, `InEngine_JSDFirst`, `DIVJOIN`
- Data model: independent crown-gear tooth characteristics with CT and SL
  random-variable measurements; candidate pairs are the CT set crossed with
  the SL set.
- Default target pair count: `5000`
- Unimodal fractions: `0.2`, `0.5`, `0.8`
- Selectivity thresholds: calibrated from remote data at the 10%, 50%, and 90% percentiles
- Remote services: `exp2_npairs_{N}_uf_0p2`, `exp2_npairs_{N}_uf_0p5`, `exp2_npairs_{N}_uf_0p8`
- Outputs: `exp2_calibration.csv`, `exp2_inengine_cheapfirst.csv`, `exp2_inengine_jsdfirst.csv`, `exp2_similarityjoin.csv`, `exp2_pruning_stats.csv`

For pruning statistics, the Fuseki JVM must enable:

```text
-Dprobsparql.simjoin.pruning=true
-Dprobsparql.simjoin.deduplicate=true
```

The server exposes the latest sequential DIVJOIN pruning counters through
`prob:lastDivJoinStats(...)`; Exp2 runs are therefore intended to be sequential,
not concurrent.

## Experiment 3: Divergence Decision Methods

Experiment 3 evaluates the GMM JSD strategy stack in a controlled classification
setting using Exp1-style tooth CT/SL measurement pairs.

- Runner: `benchmark/scripts/Experiments3/run_exp3.sh`
- Java class: `org.apache.jena.probsparql.Exp3Benchmark`
- Methods: `V1_MC`, `V2_STRATIFIED`, `V3_SPRT`, `V4_BOUNDS`, `V5_ADAPTIVE`
- Similarity threshold: `theta = 0.3`
- Workloads: `easy`, `medium`, `hard`, `mixed`
- Expected aligned pairs per workload: `2400`
- Remote services: `simjoin_easy`, `simjoin_medium`, `simjoin_hard`, `simjoin_mixed`
- Reference JSD: embedded on each tooth characteristic as `prob:referenceJSD`
- Mode dispatch: `prob:jsdMode(?d1, ?d2, "V3_SPRT")` and analogous mode strings
- Outputs: `benchmark/results/exp3/exp3_classification.csv`, `exp3_per_pair.csv`

Exp3 does not use a query directory because the method-dispatch queries are
constructed inside the Java benchmark.

## Experiment 4: Datatype Extensibility

Experiment 4 checks whether the probabilistic datatype layer and comparison
functions work across GMM, multidimensional histogram, and Dirichlet literals.

- Runner: `benchmark/scripts/Experiments4/run_exp4.sh`
- Java classes: `Exp4DispatchTest`, `Exp4MicroBenchmark`, `Exp4CrossTypeJSD`, `Exp4EndToEnd`, `Exp4DirichletDemo`
- Queries: `benchmark/queries/exp4`
- Outputs: `benchmark/results/exp4/*.csv`

Active sub-experiments:

- Dispatch validation: verifies `prob:mean`, `prob:std`, `prob:map`, `prob:cdf`, and `prob:jsd` across supported datatypes.
- Microbenchmark: compares per-operation latency for GMM, histogram, and Dirichlet services.
- Cross-type JSD: evaluates GMM-histogram and Dirichlet-histogram comparisons.
- End-to-end queries: runs Q2 filtering and Q4 JSD workloads over GMM and histogram variants.
- Dispatch/micro/cross-type datasets use realistic component/measurement or
  random-variable wrappers instead of bare distribution literals.
- Dirichlet demo: qualitative checks for Dirichlet query support.

Required remote service names are documented in
`benchmark/scripts/Experiments4/run_exp4.sh`.

## Experiment 5: Execution Placement

Experiment 5 compares where probabilistic filtering is executed:

- In-engine early filtering: `FILTER(prob:cdf(?d, 9.8) >= 0.9)` executes in Fuseki.
- Post-processing late filtering: the client fetches candidates and applies the same CDF predicate in Java.
- Data model: Exp1-style crown-gear tooth CT distributions with optional SL and
  laser follow-up measurements.

Details:

- Runner: `benchmark/scripts/Experiments5/run_exp5.sh`
- Java class: `org.apache.jena.probsparql.Exp5Benchmark`
- Queries: `benchmark/queries/exp5`
- Required argument: `--dataset`, supplied by the shell script
- Output: `benchmark/results/exp5/exp5_summary.csv`

## Data Generation

Dataset generators are kept under the corresponding `benchmark/scripts/ExperimentsN`
directories. They write TTL files under `benchmark/data`, but generated datasets
are not intended to be committed. After generation, load the TTL files into
Fuseki services using the service names expected by the runners.

Important generators:

- `Experiments1/component/generate_exp1_component_probabilistic.py`
- `Experiments1/component/generate_exp1_component_deterministic.py`
- `Experiments1/dimension/generate_exp1_dimension.py`
- `Experiments1/permutation/generate_exp1_permutation.py`
- `Experiments2/generate_exp2.py`
- `Experiments3/generate_exp3.py`
- `Experiments4/generate_dispatch_micro_datasets.py`
- `Experiments4/generate_histogram_datasets.py`
- `Experiments4/generate_crosstype_pairs.py`
- `Experiments4/generate_dirichlet_dataset.py`
- `Experiments5/generate_exp5.py`

## Repository Conventions

- Active runners live under `benchmark/scripts/ExperimentsN`.
- Active query files live under `benchmark/queries/expN`.
- Generated datasets live under `benchmark/data` and should remain ignored by Git.
- Generated results live under `benchmark/results` and should remain ignored by Git.
- Historical exploratory queries and plots should remain under `legacy` directories and are not part of the current benchmark protocol.
