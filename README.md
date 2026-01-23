# ProbSPARQL: Querying Knowledge Graphs with Multi-dimensional, Uncertain Numeric Data

**Probabilistic SPARQL Extension for Apache Jena with Gaussian Mixture Models (GMMs)**

ProbSPARQL extends Apache Jena to support probabilistic queries over RDF data with uncertainty represented as Gaussian Mixture Models.

---

## Table of Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Verification](#quick-verification)
- [Running the SPARQL Endpoint](#running-the-sparql-endpoint)
- [Usage Examples](#usage-examples)
- [Available Functions](#available-functions)
- [Special Operators](#special-operators)
- [GMM Data Format](#gmm-data-format)
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

This executes a probabilistic query that evaluates PDF values on GMM distributions.

---

## Running the SPARQL Endpoint

ProbSPARQL includes a Fuseki-based HTTP server for remote SPARQL queries.

### Start the Server

```bash
mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.server.ProbSPARQLFuseki"
```

**Server URLs:**
| Endpoint | URL |
|----------|-----|
| SPARQL Query | `http://localhost:3030/probsparql/query` |
| SPARQL Update | `http://localhost:3030/probsparql/update` |
| Web UI | `http://localhost:3030/` |

### Load Sample Data

In a new terminal:

```bash
curl -X POST "http://localhost:3030/probsparql" \
  -H "Content-Type: text/turtle" \
  --data-binary @examples/data/angle-grinder-instances.ttl
```

### Execute a Test Query

```bash
curl -X POST "http://localhost:3030/probsparql/query" \
  -H "Content-Type: application/sparql-query" \
  -H "Accept: application/json" \
  --data 'PREFIX prob: <http://probsparql.org/function#>
PREFIX uq: <http://example.org/ontology/uncertainty#>
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

### Java API

```java
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.QueryRunner;

// Initialize ProbSPARQL extensions
ProbSPARQL.init();

// Execute a query
QueryRunner.runQuery(
    "examples/data/angle-grinder-instances.ttl",
    "examples/queries/U1_probabilistic_thresholding.sparql"
);
```

### Python Client

```python
from examples.client_example import ProbSPARQLClient

client = ProbSPARQLClient("http://localhost:3030/probsparql")
results = client.query("""
    PREFIX prob: <http://probsparql.org/function#>
    SELECT ?rv (prob:mean(?dist) AS ?mean)
    WHERE { ?rv <http://example.org/ontology/uncertainty#hasDistribution> ?dist }
    LIMIT 5
""")
print(results)
```

### Example SPARQL Queries

The `examples/queries/` directory contains ready-to-use queries:

| File | Description |
|------|-------------|
| `U1_probabilistic_thresholding.sparql` | Evaluate PDF/CDF at specific points |
| `U2_probabilistic_comparison.sparql` | Compare distributions using divergence measures |
| `U3_distribution_transformation.sparql` | Scale, shift, and transform distributions |
| `U4_distribution_manipulation.sparql` | Extract mean, std, quantiles |
| `U5_similarityjoin_test.sparql` | SIMILARITYJOIN operator examples |
| `U6_fusejoin_comparison.sparql` | FUSEJOIN (Bayesian fusion) examples |
| `U7_complex_filter_pattern.sparql` | Complex nested graph pattern with probabilistic filter |

### Run All Example Queries

A convenience script is provided to run all U1-U6 queries:

```bash
cd examples/queries

# Run all queries (U1-U7)
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
| `prob:pdf(?gmm, ?x)` | Probability density at point x | `prob:pdf(?dist, 5.0)` |
| `prob:cdf(?gmm, ?x)` | Cumulative probability up to x | `prob:cdf(?dist, 5.0)` |
| `prob:logpdf(?gmm, ?x)` | Log probability density | `prob:logpdf(?dist, 5.0)` |
| `prob:logcdf(?gmm, ?x)` | Log cumulative probability | `prob:logcdf(?dist, 5.0)` |

### Comparison Functions
| Function | Description | Example |
|----------|-------------|---------|
| `prob:kldivergence(?gmm1, ?gmm2)` | KL divergence between distributions | `prob:kldivergence(?d1, ?d2)` |
| `prob:jsdivergence(?gmm1, ?gmm2)` | Jensen-Shannon divergence | `prob:jsdivergence(?d1, ?d2)` |

### Transformation Functions
| Function | Description | Example |
|----------|-------------|---------|
| `prob:scale(?gmm, ?factor)` | Scale distribution | `prob:scale(?dist, 2.0)` |
| `prob:shift(?gmm, ?offset)` | Shift distribution | `prob:shift(?dist, 1.0)` |
| `prob:linear(?gmm, ?a, ?b)` | Linear transform (ax + b) | `prob:linear(?dist, 2.0, 1.0)` |
| `prob:convolve(?gmm1, ?gmm2)` | Convolve two distributions | `prob:convolve(?d1, ?d2)` |

### Manipulation Functions
| Function | Description | Example |
|----------|-------------|---------|
| `prob:mean(?gmm)` | Expected value | `prob:mean(?dist)` |
| `prob:std(?gmm)` | Standard deviation | `prob:std(?dist)` |
| `prob:fuse(?gmm1, ?gmm2)` | Bayesian fusion | `prob:fuse(?prior, ?likelihood)` |
| `prob:quantile(?gmm, ?p)` | Quantile at probability p | `prob:quantile(?dist, 0.95)` |

---

## Special Operators

### FUSEJOIN - Bayesian Fusion Join

Performs Bayesian fusion on compatible distributions:

```sparql
PREFIX uq: <http://example.org/ontology/uncertainty#>

SELECT ?sensor ?posterior WHERE {
  { ?sensor uq:hasPriorDistribution ?prior . }
  FUSEJOIN(?prior, ?measurement, 0.1, ?posterior)
  { ?sensor uq:hasMeasurement ?measurement . }
}
```

### SIMILARITYJOIN - Similarity Filter

Filters pairs of distributions by similarity:

```sparql
PREFIX uq: <http://example.org/ontology/uncertainty#>

SELECT ?sensor1 ?sensor2 WHERE {
  { ?sensor1 uq:hasDistribution ?dist1 . }
  SIMILARITYJOIN(?dist1, ?dist2, 0.1)
  { ?sensor2 uq:hasDistribution ?dist2 . }
}
```

---

## GMM Data Format

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

---

## Project Structure

```
ProbSPARQL/
├── src/main/java/org/apache/jena/probsparql/
│   ├── ProbSPARQL.java          # Main initialization
│   ├── QueryRunner.java         # Command-line query runner
│   ├── datatypes/               # GMM datatype implementation
│   ├── functions/               # SPARQL function implementations
│   ├── propertyfunctions/       # Property function implementations
│   ├── algebra/                 # FUSEJOIN/SIMILARITYJOIN operators
│   ├── engine/                  # Query engine extensions
│   └── server/                  # Fuseki HTTP server
├── examples/
│   ├── data/                    # Sample RDF data files
│   ├── queries/                 # Sample SPARQL queries
│   └── ontologies/              # Ontology definitions
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
