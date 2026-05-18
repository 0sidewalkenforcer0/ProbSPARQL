# ProbSPARQL: Querying Knowledge Graphs with Multi-dimensional, Uncertain Numeric Data

**Probabilistic SPARQL Extension for Apache Jena with GMM, Histogram, and Dirichlet Literals**

ProbSPARQL extends Apache Jena to support probabilistic queries over RDF data with distribution-valued literals. The current prototype supports Gaussian mixture models (GMMs), multidimensional histograms, and Dirichlet distributions, with polymorphic numerical comparison through `prob:jsd`.

---

## Table of Contents

- [Requirements](#requirements)
- [Health Check (Recommended)](#health-check-recommended)
- [Installation](#installation)
- [Quick Verification](#quick-verification)
- [Running the SPARQL Endpoint](#running-the-sparql-endpoint)
- [Usage Examples](#usage-examples)
- [Available Functions](#available-functions)
- [Special Operators](#special-operators)
- [Probabilistic Data Formats](#probabilistic-data-formats)
- [Project Structure](#project-structure)
- [License](#license)

---

## Requirements

| Requirement | Version | Check Command |
|-------------|---------|---------------|
| Java (JDK)  | **21 or higher** | `java -version` |
| Maven       | **3.6 or higher** | `mvn -version` |

### Installing Java 21 (if needed)

**macOS (Homebrew):**
```bash
brew install --cask temurin@21
```

**Ubuntu/Debian:**
```bash
sudo apt install openjdk-21-jdk
```

**Windows:**
Download from [Adoptium](https://adoptium.net/temurin/releases/?version=21)

---

## Health Check (Recommended)

Before building, run the one-command environment doctor:

```bash
./doctor.sh
```

What it checks:

- Java version (must be 21+)
- Maven version (must be 3.6+)
- `pom.xml` compatibility settings (Java/Jena versions)
- Local Jena snapshot artifacts in `~/.m2`
- API consistency check for `RDFDatatype.canonicalizeLexicalForm(String)`

If any check fails, the script prints copy-paste repair commands.

---

## Installation

### Step 1: Clone the Repository

```bash
git clone <repository-url>
cd ProbSPARQL
```

### Step 2: Build the Modified Jena Library

ProbSPARQL uses a modified version of Apache Jena with probabilistic extensions. You must build it first:

```bash
cd jena
mvn clean install -DskipTests
cd ..
```

> ⏱️ **Note:** This step takes approximately 10-15 minutes on the first run.

### Step 3: Build ProbSPARQL

```bash
mvn clean compile
```

**Expected output:**
```
[INFO] BUILD SUCCESS
```

---

## Quick Verification

### Run Unit Tests

```bash
mvn test
```

### Run a Sample Query (Java API)

```bash
mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.QueryRunner" \
  -Dexec.args="examples/data/angle-grinder-instances.ttl examples/queries/U1_probabilistic_thresholding.sparql"
```

This executes a probabilistic query that evaluates PDF values on distribution literals.

---

## Running the SPARQL Endpoint

ProbSPARQL includes a Fuseki-based HTTP server for remote SPARQL queries.

### Start the Server

```bash
mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.server.ProbSPARQLFuseki" \
  -Dexec.args="3030 examples/data/factory-instances.ttl"
```

**Server URLs:**
| Endpoint | URL |
|----------|-----|
| SPARQL Query | `http://localhost:3030/probsparql/query` |
| SPARQL Update | `http://localhost:3030/probsparql/update` |
| Web UI | `http://localhost:3030/` |

The command above starts the endpoint on port `3030` and preloads the factory demo RDF data used by the Web UI.

### Execute a Test Query

```bash
curl -X POST "http://localhost:3030/probsparql/query" \
  -H "Content-Type: application/sparql-query" \
  -H "Accept: application/json" \
  --data 'PREFIX prob: <http://probsparql.org/function#>
PREFIX uq: <http://example.org/uncertainty#>
SELECT ?rv (prob:mean(?dist) AS ?mean) (prob:std(?dist) AS ?stddev)
WHERE {
  ?rv uq:hasDistribution ?dist .
} LIMIT 5'
```

**Expected output:** JSON results with random variables and their computed mean/stddev values.

### Stop the Server

Press `Ctrl+C` in the server terminal.

---

## Usage Examples

### Java CLI

```bash
mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.QueryRunner" \
  -Dexec.args="examples/data/angle-grinder-instances.ttl examples/queries/U1_probabilistic_thresholding.sparql"
```

### Python Client

With the Fuseki endpoint running:

```bash
pip install SPARQLWrapper
python examples/client_example.py
```

### Example SPARQL Queries

The `examples/queries/` directory contains ready-to-use queries:

| File | Description |
|------|-------------|
| `U1_probabilistic_thresholding.sparql` | Evaluate PDF/CDF at specific points |
| `U2_probabilistic_comparison.sparql` | Compare distributions using divergence measures |
| `U3_distribution_transformation.sparql` | Scale, shift, and transform distributions |
| `U4_distribution_manipulation.sparql` | Extract mean, std, quantiles |
| `U5_similarityjoin_test.sparql` | DIVJOIN operator examples |

### Run All Example Queries

A convenience script is provided to run the maintained U1-U5 examples:

```bash
cd examples/queries

# Run all maintained examples (U1-U5)
./run_all_queries.sh

# Run a single query
./run_all_queries.sh U1

# Run multiple specific queries
./run_all_queries.sh U1 U3 U5
```

---

## Available Functions

All functions use the prefix: `PREFIX prob: <http://probsparql.org/function#>`

### Thresholding Functions
| Function | Description | Example |
|----------|-------------|---------|
| `prob:pdf(?dist, ?x)` | Probability density at point x | `prob:pdf(?dist, 5.0)` |
| `prob:cdf(?dist, ?x)` | Cumulative probability up to x; for multidimensional histograms this is the joint CDF | `prob:cdf(?dist, 5.0)` |
| `prob:logpdf(?dist, ?x)` | Log probability density | `prob:logpdf(?dist, 5.0)` |
| `prob:logcdf(?dist, ?x)` | Log cumulative probability | `prob:logcdf(?dist, 5.0)` |
| `prob:histcdf(?hist, ?x)` | Histogram CDF; accepts the multidimensional histogram schema | `prob:histcdf(?hist, "[1.0,2.0]")` |

### Comparison Functions
| Function | Description | Example |
|----------|-------------|---------|
| `prob:kldivergence(?gmm1, ?gmm2)` | KL divergence between distributions | `prob:kldivergence(?d1, ?d2)` |
| `prob:jsd(?dist1, ?dist2)` | Preferred JSD interface. Returns a numerical Jensen-Shannon divergence for supported distribution types; MC paths use a fixed 10K sample budget | `prob:jsd(?d1, ?d2)` |
| `prob:jsdivergence(?gmm1, ?gmm2)` | Legacy GMM-only compatibility wrapper for the threshold-aware V1-V5 similarity evaluator | `prob:jsdivergence(?d1, ?d2)` |
| `prob:histjsd(?hist1, ?hist2)` | Histogram-specific JSD | `prob:histjsd(?h1, ?h2)` |
| `prob:sameTerm(?a, ?b)` | RDF term equality; lexical-form sensitive | `prob:sameTerm(?d1, ?d2)` |
| `prob:sameDistribution(?a, ?b)` | Datatype value equality; uses parsed distribution equality | `prob:sameDistribution(?d1, ?d2)` |

### Transformation Functions
| Function | Description | Example |
|----------|-------------|---------|
| `prob:scale(?gmm, ?factor)` | Scale a GMM distribution | `prob:scale(?dist, 2.0)` |
| `prob:shift(?gmm, ?offset)` | Shift a GMM distribution | `prob:shift(?dist, 1.0)` |
| `prob:linearTransform(?gmm, ?a, ?b)` | Linear transform (ax + b) | `prob:linearTransform(?dist, 2.0, 1.0)` |
| `prob:marginal(?gmm, ?dim)` | Extract one marginal dimension | `prob:marginal(?dist, 0)` |
| `prob:joint(?gmm1, ?gmm2)` | Build an independent joint distribution | `prob:joint(?d1, ?d2)` |
| `prob:convolve(?gmm1, ?gmm2)` | Convolve two GMM distributions | `prob:convolve(?d1, ?d2)` |
| `prob:multiply(?gmm1, ?gmm2)` | Approximate product distribution | `prob:multiply(?d1, ?d2)` |

### Manipulation Functions
| Function | Description | Example |
|----------|-------------|---------|
| `prob:mean(?dist)` | Expected value | `prob:mean(?dist)` |
| `prob:std(?dist)` | Standard deviation | `prob:std(?dist)` |
| `prob:map(?dist)` | MAP / representative point | `prob:map(?dist)` |
| `prob:modeCount(?dist)` | Number of mixture modes | `prob:modeCount(?dist)` |
| `prob:mix(?gmm1, ?gmm2)` | Weighted mixture of two GMMs | `prob:mix(?d1, ?d2)` |
| `prob:fuse(?gmm1, ?gmm2)` | Bayesian fusion | `prob:fuse(?prior, ?likelihood)` |
| `prob:quantile(?gmm, ?p)` | Quantile at probability p | `prob:quantile(?dist, 0.95)` |
| `prob:histmean(?hist)` | Mean of a histogram distribution | `prob:histmean(?hist)` |
| `prob:sample(?dist, ?n)` | Draw n samples as a JSON array with shape `[n][dimensions]` | `prob:sample(?dist, 10)` |

Benchmark support functions such as `prob:jsdMode` and `prob:lastDivJoinStats` are registered for experiment harnesses, but they are not the primary public API.

---

## Special Operators

### DIVJOIN - Similarity Filter

Filters pairs of distributions by similarity:

```sparql
PREFIX uq: <http://example.org/ontology/uncertainty#>

SELECT ?sensor1 ?sensor2 WHERE {
  { ?sensor1 uq:hasDistribution ?dist1 . }
  DIVJOIN(?dist1, ?dist2, 0.1, 0.05)
  { ?sensor2 uq:hasDistribution ?dist2 . }
}
```

`DIVJOIN` takes four arguments:

- `?dist1`, `?dist2`: the distribution variables to compare
- `0.1`: the similarity threshold, interpreted as `JSD(?dist1, ?dist2) <= 0.1`
- `0.05`: the one-sided tail probability used by the sequential confidence bounds in `V3_SPRT` and the SPRT stage inside `V5_ADAPTIVE`

---

## Probabilistic Data Formats

### GMM Literals

GMM distributions are JSON literals with datatype `uq:gmmLiteral`:

```json
{
  "K": 2,
  "d": 1,
  "covariance_type": "full",
  "weights": [0.6, 0.4],
  "means": [[5.0], [8.0]],
  "covariances": [[[1.0]], [[2.0]]]
}
```

| Field | Description |
|-------|-------------|
| `K` | Number of Gaussian components |
| `d` | Dimensionality |
| `weights` | Component weights (sum to 1.0) |
| `means` | Mean vectors (K × d) |
| `covariances` | Covariance matrices (K × d × d) |

**RDF Example:**
```turtle
@prefix uq: <http://example.org/ontology/uncertainty#> .

ex:sensor1 uq:hasDistribution 
  "{\"K\":1,\"d\":1,\"weights\":[1.0],\"means\":[[25.0]],\"covariances\":[[[0.25]]]}"^^uq:gmmLiteral .
```

### Histogram Literals

For histogram literals, the preferred lexical form is:

```json
{
  "dimensions": 2,
  "edges": [[0.0, 1.0, 2.0], [10.0, 20.0, 30.0]],
  "weights": [0.1, 0.2, 0.3, 0.4]
}
```

`weights` stores cell probability masses on the Cartesian-product grid in row-major order.
Legacy 1-D histogram literals of the form `{"bins":[...],"weights":[...]}` are still accepted, but histogram values are now serialized back using the multidimensional schema above.

For multidimensional histograms, `prob:cdf` / `prob:histcdf` interpret the second argument as a JSON array and compute the **joint CDF** `P(X1<=x1, ..., Xd<=xd)`.

### Dirichlet Literals

Dirichlet literals use datatype `uq:dirichletLiteral` and are supported by the polymorphic datatype layer for the current comparison and manipulation functions used in the prototype.

---

## Project Structure

```
ProbSPARQL/
├── src/main/java/org/apache/jena/probsparql/
│   ├── ProbSPARQL.java          # Main initialization
│   ├── QueryRunner.java         # Command-line query runner
│   ├── datatypes/               # GMM, histogram, and Dirichlet datatypes
│   ├── functions/               # SPARQL function implementations
│   ├── propertyfunctions/       # Property function implementations
│   ├── algebra/                 # Custom query operators
│   ├── engine/                  # Query engine extensions
│   └── server/                  # Fuseki HTTP server
├── examples/
│   ├── data/                    # Sample RDF data files
│   ├── queries/                 # Sample SPARQL queries
│   └── ontologies/              # Ontology definitions
├── benchmark/
│   ├── README.md                # Current benchmark implementation
│   ├── queries/                 # Experiment query workloads
│   └── scripts/                 # Data generation and remote run scripts
├── jena/                        # Modified Apache Jena source
└── pom.xml                      # Maven build configuration
```

---

## Troubleshooting

### "Java version not supported"
Ensure Java 21+ is installed and set as default:
```bash
java -version  # Should show version 21 or higher
```

### "BUILD FAILURE" during Jena build
Make sure you're in the `jena/` directory and have sufficient memory:
```bash
export MAVEN_OPTS="-Xmx2g"
cd jena && mvn clean install -DskipTests
```

### "Connection refused" when querying endpoint
Make sure the Fuseki server is running:
```bash
mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.server.ProbSPARQLFuseki"
```

---

## License

Apache License 2.0
