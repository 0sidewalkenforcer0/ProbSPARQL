# ProbSPARQL

Probabilistic SPARQL extension for Apache Jena with Gaussian Mixture Models (GMMs).

## Quick Start

```bash
# Start HTTP server
./start-fuseki.sh 3030 examples/data/angle-grinder-instances.ttl

# Query
curl -X POST http://localhost:3030/probsparql/query \
  -H "Content-Type: application/sparql-query" \
  --data 'PREFIX prob: <http://probsparql.org/function#>
          SELECT ?rv (prob:mean(?dist) AS ?mean) WHERE {
            ?rv <http://example.org/ontology/uncertainty#hasDistribution> ?dist
          } LIMIT 5'
```

## Functions (22 total)

| Category | Functions |
|----------|-----------|
| **Thresholding** | `pdf`, `cdf`, `logpdf`, `logcdf` |
| **Comparison** | `kldivergence`, `jsdivergence` |
| **Transformation** | `scale`, `shift`, `linear`, `marginal`, `joint`, `convolve`, `multiply` |
| **Manipulation** | `mean`, `std`, `map`, `modecount`, `mix`, `fuse`, `quantile` |

## Special Operators

### FUSEJOIN - Bayesian Fusion
```sparql
FUSEJOIN(?dist1, ?dist2, 0.3, ?fusedDist) { }
```
Filters by JS divergence ≤ tolerance, creates fused distribution.

### SIMILARITYJOIN - Similarity Filtering
```sparql
SIMILARITYJOIN(?dist1, ?dist2, 0.3) { }
```
Filters by JS divergence ≤ tolerance, keeps original distributions.

## GMM Format

```json
{"K":1,"d":1,"covariance_type":"full","weights":[1.0],"means":[[25.0]],"covariances":[[[0.25]]]}
```

## Build

```bash
mvn clean compile
mvn test
```

## Requirements

- Java 21+
- Maven 3.6+

## License

Apache License 2.0
