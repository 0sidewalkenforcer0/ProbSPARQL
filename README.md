# ProbSPARQL

Probabilistic SPARQL extension for Apache Jena with Gaussian Mixture Models (GMMs).

## Quick Start

### Using QueryRunner (Java API)

```java
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.QueryRunner;

// Initialize ProbSPARQL
ProbSPARQL.init();

// Load data and execute query
String dataFile = "examples/data/angle-grinder-instances.ttl";
String queryFile = "examples/queries/U1_probabilistic_thresholding.sparql";
QueryRunner.runQuery(dataFile, queryFile);
```

### Using HTTP Server (Fuseki)

```bash
# Start HTTP server
./start-fuseki.sh 3030 examples/data/angle-grinder-instances.ttl

# Query via HTTP
curl -X POST http://localhost:3030/probsparql/query \
  -H "Content-Type: application/sparql-query" \
  --data 'PREFIX prob: <http://probsparql.org/function#>
          SELECT ?rv (prob:mean(?dist) AS ?mean) WHERE {
            ?rv <http://example.org/ontology/uncertainty#hasDistribution> ?dist
          } LIMIT 5'
```

### Using Python Client

```python
from probsparql_client import ProbSPARQLClient

client = ProbSPARQLClient("http://localhost:3030/probsparql")
results = client.query("""
  PREFIX prob: <http://probsparql.org/function#>
  SELECT ?rv (prob:mean(?dist) AS ?mean) WHERE {
    ?rv <http://example.org/ontology/uncertainty#hasDistribution> ?dist
  } LIMIT 5
""")
```

## Functions (22 total)

| Category | Functions | Description |
|----------|-----------|-------------|
| **Thresholding** | `pdf`, `cdf`, `logpdf`, `logcdf` | Evaluate probability density/cumulative distribution functions |
| **Comparison** | `kldivergence`, `jsdivergence` | Measure divergence between distributions (KL, Jensen-Shannon) |
| **Transformation** | `scale`, `shift`, `linear`, `marginal`, `joint`, `convolve`, `multiply` | Transform distributions (scaling, shifting, marginalization, convolution, etc.) |
| **Manipulation** | `mean`, `std`, `map`, `modecount`, `mix`, `fuse`, `quantile` | Extract statistics and combine distributions (mean, std dev, MAP, fusion, quantiles) |

## Special Operators

### FUSEJOIN - Bayesian Fusion

FUSEJOIN performs Bayesian fusion of compatible probability distributions using relational join semantics.

**New Relational Syntax:**
```sparql
{ 
  ?sensor uq:hasPriorDistribution ?prior .
} 
FUSEJOIN(?prior, ?measurement, 0.1, ?posterior) 
{ 
  ?sensor uq:hasMeasurement ?measurement .
}
```

**Legacy Syntax (still supported):**
```sparql
FUSEJOIN(?dist1, ?dist2, 0.3, ?fusedDist) { }
```

- Filters pairs by JS divergence ≤ tolerance
- Creates fused distribution using Bayesian product
- Supports left and right table patterns for relational semantics

### SIMILARITYJOIN - Similarity Filtering

SIMILARITYJOIN filters compatible distributions without fusion.

**New Relational Syntax:**
```sparql
{ 
  ?sensor1 uq:hasDistribution ?dist1 .
} 
SIMILARITYJOIN(?dist1, ?dist2, 0.1) 
{ 
  ?sensor2 uq:hasDistribution ?dist2 .
}
```

**Legacy Syntax (still supported):**
```sparql
SIMILARITYJOIN(?dist1, ?dist2, 0.3) { }
```

- Filters pairs by JS divergence ≤ tolerance
- Keeps original distributions (no fusion)
- Supports left and right table patterns for relational semantics

## GMM Format

GMM distributions are represented as JSON literals with custom datatype `uq:gmmLiteral`:

```json
{"K":1,"d":1,"covariance_type":"full","weights":[1.0],"means":[[25.0]],"covariances":[[[0.25]]]}
```

**Fields:**
- `K`: Number of Gaussian components
- `d`: Dimensionality
- `covariance_type`: `"full"`, `"diagonal"`, or `"spherical"`
- `weights`: Array of component weights (must sum to 1.0)
- `means`: Array of mean vectors (K × d)
- `covariances`: Array of covariance matrices (K × d × d for full, K × d for diagonal, K × 1 for spherical)

**Example in RDF:**
```turtle
ex:sensor1 uq:hasDistribution 
  "{\"K\":2,\"d\":1,\"weights\":[0.4,0.6],\"means\":[[5.0],[8.0]],\"covariances\":[[[4.0]],[[3.0]]]}"^^uq:gmmLiteral .
```

## Usage Examples

### Example 1: Probabilistic Thresholding
```sparql
PREFIX prob: <http://probsparql.org/function#>
SELECT ?sensor ?density WHERE {
  ?sensor uq:hasDistribution ?gmm .
  BIND(prob:pdf(?gmm, 6.0) AS ?density)
  FILTER(?density > 0.1)
}
```

### Example 2: Distribution Comparison
```sparql
PREFIX prob: <http://probsparql.org/function#>
SELECT ?s1 ?s2 ?divergence WHERE {
  ?s1 uq:hasDistribution ?gmm1 .
  ?s2 uq:hasDistribution ?gmm2 .
  BIND(prob:jsdivergence(?gmm1, ?gmm2) AS ?divergence)
  FILTER(?divergence < 0.1)
}
```

### Example 3: FUSEJOIN with Relational Semantics
```sparql
PREFIX prob: <http://probsparql.org/function#>
SELECT ?sensor ?prior ?measurement ?posterior WHERE {
  { ?sensor uq:hasPriorDistribution ?prior . }
  FUSEJOIN(?prior, ?measurement, 0.1, ?posterior)
  { ?sensor uq:hasMeasurement ?measurement . }
}
```

## HTTP Server

ProbSPARQL includes a Fuseki-based HTTP server for remote query execution:

**Endpoints:**
- SPARQL Query: `http://localhost:3030/probsparql/query`
- SPARQL Update: `http://localhost:3030/probsparql/update`
- Web UI: `http://localhost:3030/`

**Start Server:**
```bash
./start-fuseki.sh <port> <data-file.ttl>
```

## Example Queries

The `examples/queries/` directory contains comprehensive examples:
- `U1_probabilistic_thresholding.sparql` - PDF/CDF evaluation
- `U2_probabilistic_comparison.sparql` - Distribution comparison
- `U3_distribution_transformation.sparql` - Transformations
- `U4_distribution_manipulation.sparql` - Statistics and fusion
- `U5_similarityjoin_test.sparql` - SIMILARITYJOIN examples
- `U6_fusejoin_comparison.sparql` - FUSEJOIN examples
- `fusejoin_example.sparql` - Detailed FUSEJOIN tutorial

## Build

```bash
mvn clean compile
mvn test
```

## Architecture

ProbSPARQL extends Apache Jena ARQ at multiple levels:

1. **Custom Datatype**: `GMMDatatype` for parsing and validating GMM literals
2. **SPARQL Functions**: 22 custom functions registered in Jena's function registry
3. **Property Functions**: `exactJoin` and `fuzzyJoin` for probabilistic matching
4. **Algebra Operators**: `OpFuseJoin` and `OpSimilarityJoin` for relational joins
5. **Query Engine**: `QueryEngineProbabilistic` for parsing FUSEJOIN/SIMILARITYJOIN syntax
6. **Iterators**: Custom query iterators implementing join algorithms

## Key Features

- **Attribute-level Uncertainty**: Represent uncertainty at the attribute level using GMMs
- **Probabilistic Queries**: Query uncertain data using probability distributions
- **Bayesian Fusion**: Combine multiple measurements using Bayesian inference
- **Distribution Operations**: Transform, compare, and manipulate probability distributions
- **Relational Joins**: FUSEJOIN and SIMILARITYJOIN with relational semantics
- **HTTP API**: RESTful endpoint for remote query execution
- **Extensible**: Plugin architecture for custom join strategies

## Property Functions

In addition to FUSEJOIN/SIMILARITYJOIN operators, ProbSPARQL provides property functions:

```sparql
# Exact join (JS divergence ≈ 0)
?sensor1 prob:exactJoin (?sensor2, ?fused) .

# Fuzzy join (JS divergence < tolerance)
?sensor1 prob:fuzzyJoin (?sensor2, 0.1, ?fused) .
```

## Requirements

- Java 21+
- Maven 3.6+
- Apache Jena 6.0.0-SNAPSHOT

## License

Apache License 2.0
