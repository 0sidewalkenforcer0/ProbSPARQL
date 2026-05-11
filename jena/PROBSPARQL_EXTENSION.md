# ProbSPARQL Extension for Apache Jena

This is a modified version of Apache Jena with ProbSPARQL extensions for probabilistic SPARQL queries.

## Modified Files

### 1. Grammar (JavaCC)
- `jena-arq/Grammar/main.jj`
  - Added FUSEJOIN and DIVJOIN syntax support
  - Added FuseJoinGraphPattern() and SimilarityJoinGraphPattern() rules
  - Added these operators to GraphPatternNotTriples()

### 2. Syntax Layer
- `jena-arq/src/main/java/org/apache/jena/sparql/syntax/ElementFuseJoin.java` (new)
- `jena-arq/src/main/java/org/apache/jena/sparql/syntax/ElementSimilarityJoin.java` (new)

### 3. Algebra Layer
- `jena-arq/src/main/java/org/apache/jena/sparql/algebra/op/OpFuseJoin.java` (new)
- `jena-arq/src/main/java/org/apache/jena/sparql/algebra/op/OpSimilarityJoin.java` (new)
- `jena-arq/src/main/java/org/apache/jena/sparql/algebra/AlgebraGenerator.java` (modified)
- `jena-arq/src/main/java/org/apache/jena/sparql/algebra/Transform.java` (modified)
- `jena-arq/src/main/java/org/apache/jena/sparql/algebra/TransformCopy.java` (modified)

### 4. SSE Tags
- `jena-arq/src/main/java/org/apache/jena/sparql/sse/Tags.java` (modified)

## New SPARQL Syntax

### FUSEJOIN
Performs Bayesian fusion of probability distributions when JS divergence is within tolerance.

```sparql
SELECT ?result WHERE {
  FUSEJOIN(?dist1, ?dist2, 0.1, ?result) {
    ?s1 :hasDist ?dist1 .
    ?s2 :hasDist ?dist2 .
  }
}
```

### DIVJOIN
Filters results based on JS divergence between distributions being within tolerance.

```sparql
SELECT ?s1 ?s2 WHERE {
  DIVJOIN(?dist1, ?dist2, 0.1, 0.05) {
    ?s1 :hasDist ?dist1 .
    ?s2 :hasDist ?dist2 .
  }
}
```

The four arguments are the left distribution, the right distribution, the JSD
threshold, and the one-sided tail probability used by the sequential confidence
bounds in `V3_SPRT` and `V5_ADAPTIVE`.

The relational binary form is also supported:

```sparql
SELECT ?s1 ?s2 WHERE {
  { ?s1 :hasDist ?dist1 . }
  DIVJOIN(?dist1, ?dist2, 0.1, 0.05)
  { ?s2 :hasDist ?dist2 . }
}
```

## Building

To build the modified Jena, use the provided build script:

```bash
cd jena
./build.sh
```

Or manually set JAVA_HOME and build:

```bash
cd jena
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home
mvn clean install -DskipTests
```

**Note:** Make sure you have Java 21 installed. The build script automatically sets the correct JAVA_HOME path for macOS Homebrew installations.

This will install the modified Jena JARs to your local Maven repository.

## Integration with ProbSPARQL

The main ProbSPARQL project (in the parent directory) uses these modified Jena sources.
Make sure to build this modified Jena first before building the main project.
