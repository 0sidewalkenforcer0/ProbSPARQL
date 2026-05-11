# ProbSPARQL: Technical Implementation Report
## Architectural Extensions to Apache Jena for Probabilistic SPARQL

---

## 1. System Architecture Overview

ProbSPARQL extends Apache Jena 6.0.0-SNAPSHOT across four architectural layers: grammar and parser, algebra and AST, query execution engine, and the probabilistic data type and function registry. Each layer is modified minimally and surgically—standard SPARQL queries that contain no probabilistic extensions are processed through the original Jena pipeline with zero overhead in the algebra and execution stages.

The extending lifecycle for a probabilistic query is:

```
SPARQL String
    │
    ▼
ARQParser (modified)          ← Layer 1: Grammar/Parser
 → ElementSimilarityJoin
 → ElementFuseJoin
    │
    ▼
AlgebraGeneratorProbabilistic ← Layer 2: Algebra/AST
 → OpSimilarityJoin (OpExt)
 → OpFuseJoin (OpExt)
    │
    ▼
QueryEngineProbabilistic       ← Layer 3: Execution Engine
 → OpExecutorProbabilistic
  → QueryIterPrunedSimilarityJoin / QueryIterFuseJoin
    │
    ▼
Probabilistic datatypes / FunctionRegistry ← Layer 4: Data Types & Functions
 → uq:gmmLiteral, uq:histLiteral, uq:dirichletLiteral
 → prob:jsd, prob:jsdivergence, prob:cdf, prob:mean, …
```

---

## 2. Grammar and Parser Extensions

### 2.1 JavaCC Grammar (`main.jj`)

Two new keyword tokens are declared and two syntax forms are supported for each operator: a *relational* binary form and a *legacy* unary (filter) form.

**Token declarations:**
```javacc
<SIMILARITYJOIN> : { "DIVJOIN" }
<FUSEJOIN>       : { "FUSEJOIN" }
```

**Syntax forms for DIVJOIN:**

| Form | Syntax | Mode |
|------|--------|------|
| Relational | `{ leftPat } DIVJOIN(?v1,?v2,θ,α) { rightPat }` | `legacyMode = false` |
| Legacy filter | `DIVJOIN(?v1,?v2,θ,α) { pat }` | `legacyMode = true` |

The grammar defines four production rules:

```javacc
// ── Relational forms (binary join) ──────────────────────────────────────
Element RelationalSimilarityJoinGraphPattern() {
  LOOKAHEAD(GroupGraphPattern() <SIMILARITYJOIN>)
  leftPattern = GroupGraphPattern()
  <SIMILARITYJOIN> <LPAREN> leftVar <COMMA> rightVar <COMMA> tolerance <COMMA> tailProbability <RPAREN>
  rightPattern = GroupGraphPattern()
  → new ElementSimilarityJoin(leftPattern, rightPattern, leftVar, rightVar, tolerance, tailProbability)
}

Element RelationalFuseJoinGraphPattern() {
  LOOKAHEAD(GroupGraphPattern() <FUSEJOIN>)
  leftPattern = GroupGraphPattern()
  <FUSEJOIN> <LPAREN> leftVar <COMMA> rightVar <COMMA> tolerance <COMMA> resultVar <RPAREN>
  rightPattern = GroupGraphPattern()
  → new ElementFuseJoin(leftPattern, rightPattern, …)
}

// ── Legacy forms (filter semantics) ─────────────────────────────────────
Element SimilarityJoinGraphPattern() { … → new ElementSimilarityJoin(pattern, …, tolerance, tailProbability) }
Element FuseJoinGraphPattern()       { … → new ElementFuseJoin(pattern, …)       }
```

For `DIVJOIN`, `θ` is the similarity threshold and `α` is the one-sided tail probability passed into the sequential decision used by `V3_SPRT` and the adaptive decision phase of `V5_ADAPTIVE`.

All four rules are incorporated in `GraphPatternNotTriples()` and in the outer `GroupGraphPatternSub()` loop (see §2.2).

### 2.2 Generated Parser (`ARQParser.java`) — Lookahead Fix

Because `main.jj` was modified after `ARQParser.java` was last auto-generated, the outer `GroupGraphPatternSub` loop in the generated file did not contain the necessary lookahead to dispatch the relational binary form. The symptom is that the parser first consumes `{ ?rv1 … }` as a standalone `GroupGraphPattern`, then enters `SimilarityJoinGraphPattern()` with the current token already positioned at `DIVJOIN`, causing `jj_2_4`'s lookahead to fail and `leftPattern` remaining `null` — triggering legacy mode for every query.

**Root cause in `ARQParser.java` (before fix):**
```java
// The loop body unconditionally delegated to GraphPatternNotTriples(),
// which by then had already consumed the left group as a standalone element.
el = GraphPatternNotTriples();
elg.addElement(el);
```

**Fix applied to `ARQParser.java`:**
```java
// Test BEFORE consuming whether the upcoming token stream matches
// { GroupGraphPattern } DIVJOIN(...):
if (jj_2_4(2147483647)) {           // jj_2_4 scans GroupGraphPattern() DIVJOIN
    el = SimilarityJoinGraphPattern();  // which now correctly captures leftPattern
} else {
    el = GraphPatternNotTriples();
}
elg.addElement(el);
```

The `jj_2_4` method performs an infinite-depth lookahead that scans for the exact token sequence `GroupGraphPattern() DIVJOIN`, without consuming the input. When this lookahead succeeds, `SimilarityJoinGraphPattern()` is called from a position where its own internal lookahead (`LOOKAHEAD(GroupGraphPattern() <SIMILARITYJOIN>)`) succeeds, enabling `leftPattern` to be populated and `legacyMode = false`.

### 2.3 Query Routing

`QueryEngineProbabilistic.Factory.accept()` inspects the parsed `ElementGroup` tree recursively. It accepts a query for probabilistic processing if and only if `ElementSimilarityJoin` or `ElementFuseJoin` is present anywhere in the syntax tree. Standard queries (containing only BGPs, `BIND`, `FILTER`, `OPTIONAL`, etc.) are rejected by this factory and fall through to the default `QueryEngineMain`, consuming zero extra cost.

---

## 3. Algebra and AST Modifications

### 3.1 New Syntax Elements

Two new `Element` subclasses are added to the syntax tree:

| Class | Superclass | Fields |
|-------|-----------|--------|
| `ElementSimilarityJoin` | `Element` | `leftPattern`, `rightPattern`, `leftVar`, `rightVar`, `tolerance`, `tailProbability` |
| `ElementFuseJoin` | `Element` | `leftPattern`, `rightPattern`, `leftVar`, `rightVar`, `tolerance`, `resultVar` |

Both classes have two constructors: one for the relational (dual-pattern) form where `leftPattern ≠ null`, and one for the legacy (single-pattern) form where `leftPattern == null`. The `visit(ElementVisitor)` method delegates to `ElementVisitorProbabilistic` when available, or visits both sub-patterns for standard Jena visitors.

### 3.2 New Algebra Operators (`OpExt` Subclasses)

#### Design Choice: Extending `OpExt`

The most critical design decision is that `OpSimilarityJoin` and `OpFuseJoin` both extend `org.apache.jena.sparql.algebra.op.OpExt`, **not** `OpBase` directly.

**Rationale — the Visitor pattern invariant:**

Jena's algebra transformation infrastructure (`Transformer`, `TransformSimplify`, `TransformCopy`, etc.) relies on a push-down/pop-up visitor stack maintained by `ApplyTransformVisitor`. Every `Op.visit(opVisitor)` call is expected to call `opVisitor.visit(this)`, which pushes the current operator's transformed result onto the stack. `OpExt` provides this correctly as a `final` method:

```java
// OpExt.java (Jena source)
@Override
public final void visit(OpVisitor opVisitor) {
    opVisitor.visit(this);   // routes to visitExt(OpExt) in standard visitors
}
```

Under a previous implementation where `OpSimilarityJoin` extended `OpBase` directly, its `visit()` was:
```java
// Before fix — INCORRECT
public void visit(OpVisitor opVisitor) {
    if (leftOp != null) leftOp.visit(opVisitor); // delegates to sub-op, NOT self!
}
```

This caused `ApplyTransformVisitor.visit(OpSimilarityJoin)` to never be called. Instead, `leftOp.visit()` pushed the compiled sub-op (a `OpBGP`) onto the result stack. `TransformSimplify` then saw two consecutive `OpBGP` nodes and merged them, silently erasing `OpSimilarityJoin` from the algebra tree. All queries using `DIVJOIN` executed as plain cross-joins with no JSD filtering, returning all `n(n-1)/2` pairs.

**After fix (`OpExt` extension):**

```java
public class OpSimilarityJoin extends OpExt {
    public OpSimilarityJoin(Op leftOp, Op rightOp, Var leftVar, Var rightVar,
                            double tolerance, double tailProbability,
                            boolean legacyMode) {
        super("SimilarityJoin");   // tag used for SSE serialization
        …
    }

    @Override
    public Op effectiveOp() {
        return leftOp != null ? leftOp : rightOp; // used by query planners
    }

    @Override
    public QueryIterator eval(QueryIterator input, ExecutionContext execCxt) {
        throw new UnsupportedOperationException(
            "Execution handled by OpExecutorProbabilistic");
    }

    @Override
    public Op apply(Transform transform) {
        return this;  // preserve self through all algebra transforms
    }
}
```

Because `OpExt` routes all visitor calls through `opVisitor.visit(this)` → `visitExt(OpExt this)`, and because `TransformCopy.visitExt(op)` returns the op unchanged by default, `OpSimilarityJoin` survives the full Jena optimization pipeline (TransformSimplify, TransformMergeBGPs, sequence rewriting, etc.) without modification.

The same analysis and fix applies to `OpFuseJoin`, which uses the analogous `Op1`-based implementation in the `jena/` module.

### 3.3 Algebra Generator (`AlgebraGeneratorProbabilistic`)

`AlgebraGeneratorProbabilistic` extends `AlgebraGenerator` and overrides three compilation entry points:

| Override | Purpose |
|----------|---------|
| `compileElement(Element)` | Intercepts `ElementSimilarityJoin` / `ElementFuseJoin` at top-level |
| `compileOneInGroup(Element, Op, Deque)` | Intercepts the same within a group, composing with the accumulated `Op` |
| `compileUnknownElement(Element, String)` | Safety fallback |
| `compileElementSubquery(ElementSubQuery)` | Creates a new `AlgebraGeneratorProbabilistic` for sub-queries, preventing standard `AlgebraGenerator` from being used recursively |

**Compilation for relational semantics (`leftPattern != null`):**
```
compileSimilarityJoinInGroup(simJoin, current):
  leftOp  = compileElement(simJoin.leftPattern)
  rightOp = compileElement(simJoin.rightPattern)
  simJoinOp = new OpSimilarityJoin(leftOp, rightOp, leftVar, rightVar, θ, α, false)
  if current ≠ ⊤:
      return OpJoin(current, simJoinOp)
  return simJoinOp
```

**Compilation for legacy semantics (`leftPattern == null`):**
```
  leftOp = rightOp = compileElement(simJoin.rightPattern)
  return new OpSimilarityJoin(leftOp, rightOp, leftVar, rightVar, θ, α, legacyMode=true)
```

---

## 4. Execution Engine Extensions

### 4.1 `QueryEngineProbabilistic`

`QueryEngineProbabilistic` extends `QueryEngineMain`. Its primary responsibilities are:

1. **Algebra compilation**: overrides `createOp(Query)` to use `AlgebraGeneratorProbabilistic`, ensuring `ElementSimilarityJoin` / `ElementFuseJoin` nodes are compiled into `OpSimilarityJoin` / `OpFuseJoin` rather than triggering the fallback path in `AlgebraGenerator.compileUnknownElement()`.

2. **Executor registration**: sets `ARQConstants.sysOpExecutorFactory` in the query's `Context` to `OpExecutorProbabilistic.factory` before execution begins.

3. **Selective activation**: the inner `Factory` class implements `accept(Query, …)` by recursively scanning the syntax tree; it returns `true` only when a `ProbSPARQL`-specific element is found. Standard queries bypass this engine entirely.

### 4.2 `OpExecutorProbabilistic`

`OpExecutorProbabilistic` extends Jena's `OpExecutor`. Two override sites are used:

#### `exec(Op op, QueryIterator input)` — top-level dispatch

Since `OpExt` now routes visitor calls through `visitExt()`, the standard dispatcher (`OpExecutor.exec`) would ultimately call `OpExt.eval()`, which is intentionally unsupported. The `instanceof` checks in `exec()` intercept both operators before the dispatcher is reached:

```java
@Override
protected QueryIterator exec(Op op, QueryIterator input) {
    if (op instanceof OpFuseJoin)        return execute((OpFuseJoin) op, input);
    if (op instanceof OpSimilarityJoin)  return execute((OpSimilarityJoin) op, input);
    return super.exec(op, input);
}
```

#### `execute(OpSimilarityJoin, QueryIterator)` — two-mode execution

| Mode | Condition | Iterator |
|------|-----------|----------|
| Legacy (filter) | `isLegacyMode == true` | `QueryIterSimilarityJoinFilter` — single cross-join, JSD ≤ θ predicate |
| Relational (join) | `isLegacyMode == false` | `QueryIterSimilarityJoin` (standard nested-loop) or `QueryIterPrunedSimilarityJoin` (five-level pruning cascade) |

Pruning is activated by the JVM system property `probsparql.simjoin.pruning=true` at runtime.

#### `executeOp(Op, QueryIterator)` — recursive sub-operator dispatch

A second override of `executeOp` covers the case where `OpSimilarityJoin` / `OpFuseJoin` appear as sub-operators (e.g., inside a `UNION` or `OPTIONAL`), ensuring correct recursive execution.

### 4.3 Iterator Hierarchy

| Iterator | Input | Algorithm | Notes |
|----------|-------|-----------|-------|
| `QueryIterSimilarityJoinFilter` | Single stream | Scan-filter loop, JSD per binding | Legacy mode |
| `QueryIterSimilarityJoin` | Left stream + right `Op` | Materialized nested-loop | Non-legacy, no pruning |
| `QueryIterPrunedSimilarityJoin` | Left stream + right `Op` | Five-level cascade (see below) | Non-legacy with pruning |
| `QueryIterFuseJoinFilter` | Single stream | Scan-filter-fuse | Legacy FUSEJOIN |
| `QueryIterFuseJoin` | Left stream + right `Op` | Materialized nested-loop + Bayesian fusion | Non-legacy FUSEJOIN |

**Five-level pruning cascade in `PrunedSimJoinEvaluator`:**

| Level | Technique | Early-reject criterion |
|-------|-----------|----------------------|
| L1 | Dimensionality check | `d₁ ≠ d₂` |
| L2 | Mean-distance bound | `‖μ₁ − μ₂‖ > bound(θ)` |
| L3 | Variance bound | diagonal spread incompatible with θ |
| L4 | Component-pair bounds filter | `BoundsFilterSampler` distance bound |
| L5 | Full MC-sampled JSD | `JSD(GMM₁, GMM₂) > θ` via `AdaptiveSampler` |

Pruning statistics (pairs pruned per level, total pairs, result count) are accumulated in `PruningStats` and published through `Exp2PruningHolder`. The holder keeps a thread-local slot for in-process callers and a latest-stats snapshot that the remote benchmark harness reads through `prob:lastDivJoinStats(...)` after iterator exhaustion.

---

## 5. Data Types and Function Registry

### 5.1 Probabilistic Literal Types

ProbSPARQL currently registers three probabilistic literal families:

| Datatype | RDF datatype URI | Java value class | Notes |
|----------|------------------|------------------|-------|
| GMM | `http://example.org/ontology/uncertainty#gmmLiteral` | `GMMValue` | Multivariate Gaussian mixture with `K`, `d`, weights, means, and covariance matrices |
| Histogram | `http://example.org/ontology/uncertainty#histLiteral` | `HistogramValue` | Multidimensional histogram with joint CDF semantics |
| Dirichlet | `http://example.org/ontology/uncertainty#dirichletLiteral` | `DirichletValue` | Simplex-valued distribution used for datatype-extensibility experiments |

#### GMM literal details

| Aspect | Detail |
|--------|--------|
| RDF datatype URI | `http://example.org/ontology/uncertainty#gmmLiteral` |
| Lexical form | JSON object with exactly six fields: `K`, `d`, `covariance_type`, `weights`, `means`, `covariances` |
| Java value class | `GMMValue` — stores K, d, weight array, mean matrix `[K][d]`, covariance tensor `[K][d][d]` |
| Registration | `TypeMapper.getInstance().registerDatatype(GMMDatatype.INSTANCE)` called in `ProbSPARQL.init()` |
| Validation | Checked on parse: weight sum ∈ [1 ± 1e-6], all arrays dimensionally consistent |

A parsed probabilistic literal is recovered transparently by Jena's literal machinery. When Jena evaluates a `BIND(prob:jsd(?d1, ?d2) AS ?jsd)` or legacy `BIND(prob:jsdivergence(?d1, ?d2) AS ?jsd)` expression, the function implementation casts the `NodeValue`'s underlying Java object to the parsed distribution value without re-parsing the JSON string.

### 5.2 Registered SPARQL Functions

All functions are registered via `FunctionRegistry.get().put(URI, Class)` during `ProbSPARQL.init()` and extend Jena's standard `FunctionBase1`, `FunctionBase2`, or `FunctionBaseN`.

#### Category 1 — Thresholding / Querying (5 functions)

| URI | Arity | Description |
|-----|-------|-------------|
| `prob:pdf` | 2 | Point probability density: PDF(GMM, x) |
| `prob:cdf` | 2 | Cumulative probability: P(X ≤ x) |
| `prob:logpdf` | 2 | Log-density (numerically stable) |
| `prob:logcdf` | 2 | Log-CDF |
| `prob:histcdf` | 2 | CDF for histogram-typed distributions |

#### Category 2 — Divergence / Comparison (8 functions)

| URI | Arity | Description |
|-----|-------|-------------|
| `prob:jsd` | 2 | Preferred numerical Jensen-Shannon divergence interface |
| `prob:jsdivergence` | 2 | Legacy GMM-only compatibility wrapper for the V1-V5 similarity-evaluator stack |
| `prob:kldivergence` | 2 | KL divergence (asymmetric) |
| `prob:histjsd` | 2 | JSD for histogram-typed distributions |
| `prob:jsdMode` | 3 | Benchmark-only dispatcher for the legacy GMM V1-V5 mode stack without mutating global JVM state |
| `prob:lastDivJoinStats` | 1 | Benchmark-only accessor for the latest server-side DIVJOIN pruning counters |
| `prob:sameTerm` | 2 | RDF term identity, preserving lexical differences |
| `prob:sameDistribution` | 2 | Datatype value equality, matching SPARQL `=` distribution semantics |

`prob:jsd` is the stable numerical interface. For GMM paths it uses a fixed MC-10K estimator; other supported distribution types use their own type-specific numerical implementations or a sample-based fallback.

The legacy `prob:jsdivergence` wrapper still supports nine configurable V1-V5 / GT modes (via the `probsparql.mode` JVM property): fixed MC sample counts (`GT_100`, `GT_1K`, `GT_5K`, `GT_10K`), sequential probability ratio testing (`V3_SPRT`), analytic bounds filtering (`V4_BOUNDS`), and a five-level adaptive cascade (`V5_ADAPTIVE`, the default). In V3/V4/V5, the returned score may be the by-product of a threshold-oriented similarity decision pipeline rather than a uniformly precise JSD estimator.

Histogram literals now use the multidimensional schema
`{"dimensions":d,"edges":[...],"weights":[...]}` as the canonical form.  The
parser still accepts the older 1-D `{"bins":[...],"weights":[...]}` schema for
backwards compatibility, but serialization emits the multidimensional form.
For multidimensional histograms, `prob:cdf` / `prob:histcdf` evaluate the joint
CDF `P(X1<=x1, ..., Xd<=xd)`.

#### Category 3 — Transformations (7 functions)

`prob:scale`, `prob:shift`, `prob:linear`, `prob:marginal`, `prob:joint`, `prob:convolve`, `prob:multiply` — all return a new GMM literal computed analytically (when possible) or via sampling.

#### Category 4 — Distribution Manipulation (9 functions)

| URI | Description |
|-----|-------------|
| `prob:mean` | Expected value |
| `prob:std` | Standard deviation |
| `prob:map` | Apply a scalar function to all samples |
| `prob:modecount` | Number of modes (mixture components with weight > ε) |
| `prob:mix` | Weighted mixture of two GMMs |
| `prob:fuse` | Bayesian sensor fusion (product of distributions, re-normalised) |
| `prob:quantile` | Quantile function Q(p) |
| `prob:histmean` | Mean of a histogram distribution |
| `prob:sample` | Draws `n` samples from a supported distribution and returns a JSON array |

#### Category 5 — Property Functions (2)

`prob:exactJoin` and `prob:fuzzyJoin` are registered as SPARQL 1.1 magic property functions, enabling triple-pattern-style join syntax (e.g., `?a prob:fuzzyJoin (?b ?tol)`).

### 5.3 Initialisation Sequence

`ProbSPARQL.init()` is idempotent and thread-safe (guarded by `synchronized` + `initialized` flag). It must be called before any probabilistic query is executed. The sequence is:

1. `JenaSystem.init()` — ensures Jena base modules are loaded.
2. Register `GMMDatatype`, `HistogramDatatype`, and `DirichletDatatype` with Jena's `TypeMapper`.
3. Register all 29 SPARQL functions into `FunctionRegistry`.
4. Register 2 property functions into `PropertyFunctionRegistry`.
5. Register `QueryEngineProbabilistic.Factory` with `QueryEngineRegistry` (highest priority).

---

## 6. Current Benchmark Harness Alignment

The active benchmark harness is remote-first. Java benchmark classes run as
local clients and send SPARQL over HTTP to preloaded Fuseki services through
`RemoteBenchmarkClient`. Generated TTL datasets are not loaded by the benchmark
runners themselves; they must be generated separately and deployed as Fuseki
services whose names match the runner's dataset naming convention.

### 6.1 Scalar Function Queries

Experiments 1, 4, and 5 mostly use standard SPARQL `BIND` and `FILTER`
expressions with registered scalar functions such as `prob:cdf`, `prob:mean`,
`prob:std`, `prob:jsd`, and `prob:sample`. These queries follow Jena's standard
expression-evaluation path. `QueryEngineProbabilistic` is not required unless
the query syntax contains `DIVJOIN` or `FUSEJOIN`.

### 6.2 DIVJOIN Queries

Experiment 2 exercises the relational `DIVJOIN(?left, ?right, tolerance,
tailProbability)` syntax. The server-side parser, algebra generator, and
probabilistic executor are therefore required on the Fuseki endpoint. When
`probsparql.simjoin.pruning=true`, execution uses `QueryIterPrunedSimilarityJoin`
and publishes pruning counters through `Exp2PruningHolder`; the remote harness
then reads them with `prob:lastDivJoinStats(...)`.

### 6.3 Mode-Specific JSD Queries

Experiment 3 compares the legacy GMM JSD strategy stack without mutating global
JVM mode state. It uses the benchmark-only scalar function
`prob:jsdMode(?d1, ?d2, "V3_SPRT")` and analogous mode strings for `V1_MC`,
`V2_STRATIFIED`, `V4_BOUNDS`, and `V5_ADAPTIVE`. Reference JSD values are
embedded in the remote TTL datasets as `prob:referenceJSD`.

### 6.4 Public Interface Boundary

The current public boundary is:

- `prob:jsd` is the preferred numerical JSD function and supports GMM,
  histogram, Dirichlet, and cross-type sample-based fallback paths.
- `DIVJOIN` is the query-level similarity-decision operator. Its fourth
  argument is the one-sided tail probability used by the sequential decision
  logic.
- `prob:jsdivergence` remains as a legacy GMM-only compatibility wrapper for
  the V1-V5 similarity-evaluator stack.
- `prob:jsdMode` and `prob:lastDivJoinStats` are benchmark support functions,
  not general user-facing APIs.
