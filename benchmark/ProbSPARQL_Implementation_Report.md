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
GMMDatatype / FunctionRegistry ← Layer 4: Data Types & Functions
 → prob:jsdivergence, prob:cdf, prob:mean, …
```

---

## 2. Grammar and Parser Extensions

### 2.1 JavaCC Grammar (`main.jj`)

Two new keyword tokens are declared and two syntax forms are supported for each operator: a *relational* binary form and a *legacy* unary (filter) form.

**Token declarations:**
```javacc
<SIMILARITYJOIN> : { "SIMILARITYJOIN" }
<FUSEJOIN>       : { "FUSEJOIN" }
```

**Syntax forms for SIMILARITYJOIN:**

| Form | Syntax | Mode |
|------|--------|------|
| Relational | `{ leftPat } SIMILARITYJOIN(?v1,?v2,θ,α) { rightPat }` | `legacyMode = false` |
| Legacy filter | `SIMILARITYJOIN(?v1,?v2,θ,α) { pat }` | `legacyMode = true` |

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

For `SIMILARITYJOIN`, `θ` is the similarity threshold and `α` is the one-sided tail probability passed into the sequential confidence bounds used by `V3_SPRT` and the SPRT phase of `V5_ADAPTIVE`.

All four rules are incorporated in `GraphPatternNotTriples()` and in the outer `GroupGraphPatternSub()` loop (see §2.2).

### 2.2 Generated Parser (`ARQParser.java`) — Lookahead Fix

Because `main.jj` was modified after `ARQParser.java` was last auto-generated, the outer `GroupGraphPatternSub` loop in the generated file did not contain the necessary lookahead to dispatch the relational binary form. The symptom is that the parser first consumes `{ ?rv1 … }` as a standalone `GroupGraphPattern`, then enters `SimilarityJoinGraphPattern()` with the current token already positioned at `SIMILARITYJOIN`, causing `jj_2_4`'s lookahead to fail and `leftPattern` remaining `null` — triggering legacy mode for every query.

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
// { GroupGraphPattern } SIMILARITYJOIN(...):
if (jj_2_4(2147483647)) {           // jj_2_4 scans GroupGraphPattern() SIMILARITYJOIN
    el = SimilarityJoinGraphPattern();  // which now correctly captures leftPattern
} else {
    el = GraphPatternNotTriples();
}
elg.addElement(el);
```

The `jj_2_4` method performs an infinite-depth lookahead that scans for the exact token sequence `GroupGraphPattern() SIMILARITYJOIN`, without consuming the input. When this lookahead succeeds, `SimilarityJoinGraphPattern()` is called from a position where its own internal lookahead (`LOOKAHEAD(GroupGraphPattern() <SIMILARITYJOIN>)`) succeeds, enabling `leftPattern` to be populated and `legacyMode = false`.

### 2.3 Query Routing

`QueryEngineProbabilistic.Factory.accept()` inspects the parsed `ElementGroup` tree recursively. It accepts a query for probabilistic processing if and only if `ElementSimilarityJoin` or `ElementFuseJoin` is present anywhere in the syntax tree. Standard queries (containing only BGPs, `BIND`, `FILTER`, `OPTIONAL`, etc.) are rejected by this factory and fall through to the default `QueryEngineMain`, consuming zero extra cost.

---

## 3. Algebra and AST Modifications

### 3.1 New Syntax Elements

Two new `Element` subclasses are added to the syntax tree:

| Class | Superclass | Fields |
|-------|-----------|--------|
| `ElementSimilarityJoin` | `Element` | `leftPattern`, `rightPattern`, `leftVar`, `rightVar`, `tolerance` |
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

This caused `ApplyTransformVisitor.visit(OpSimilarityJoin)` to never be called. Instead, `leftOp.visit()` pushed the compiled sub-op (a `OpBGP`) onto the result stack. `TransformSimplify` then saw two consecutive `OpBGP` nodes and merged them, silently erasing `OpSimilarityJoin` from the algebra tree. All queries using `SIMILARITYJOIN` executed as plain cross-joins with no JSD filtering, returning all `n(n-1)/2` pairs.

**After fix (`OpExt` extension):**

```java
public class OpSimilarityJoin extends OpExt {
    public OpSimilarityJoin(Op leftOp, Op rightOp, Var leftVar, Var rightVar,
                            double tolerance, boolean legacyMode) {
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
  simJoinOp = new OpSimilarityJoin(leftOp, rightOp, leftVar, rightVar, θ, false)
  if current ≠ ⊤:
      return OpJoin(current, simJoinOp)
  return simJoinOp
```

**Compilation for legacy semantics (`leftPattern == null`):**
```
  leftOp = rightOp = compileElement(simJoin.rightPattern)
  return new OpSimilarityJoin(leftOp, rightOp, leftVar, rightVar, θ, legacyMode=true)
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

Pruning statistics (pairs pruned per level, total pairs, result count) are accumulated in `PruningStats` and published via a `ThreadLocal` holder (`Exp2PruningHolder`) for retrieval by the benchmark harness after iterator exhaustion.

---

## 5. Data Types and Function Registry

### 5.1 Probabilistic Literal Type: `GMMDatatype` / `GMMValue`

| Aspect | Detail |
|--------|--------|
| RDF datatype URI | `http://example.org/ontology/uncertainty#gmmLiteral` |
| Lexical form | JSON object with exactly six fields: `K`, `d`, `covariance_type`, `weights`, `means`, `covariances` |
| Java value class | `GMMValue` — stores K, d, weight array, mean matrix `[K][d]`, covariance tensor `[K][d][d]` |
| Registration | `TypeMapper.getInstance().registerDatatype(GMMDatatype.INSTANCE)` called in `ProbSPARQL.init()` |
| Validation | Checked on parse: weight sum ∈ [1 ± 1e-6], all arrays dimensionally consistent |

A `GMMValue` node is serialised and recovered transparently by Jena's literal machinery. When Jena evaluates a `BIND(prob:jsd(?d1, ?d2) AS ?jsd)` or legacy `BIND(prob:jsdivergence(?d1, ?d2) AS ?jsd)` expression, the function implementation directly casts the `NodeValue`'s underlying Java object to the underlying distribution value without re-parsing the JSON string.

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

#### Category 2 — Divergence / Comparison (4 functions)

| URI | Arity | Description |
|-----|-------|-------------|
| `prob:jsd` | 2 | Preferred numerical Jensen-Shannon divergence interface |
| `prob:jsdivergence` | 2 | Legacy GMM-only compatibility wrapper for the V1-V5 similarity-evaluator stack |
| `prob:kldivergence` | 2 | KL divergence (asymmetric) |
| `prob:histjsd` | 2 | JSD for histogram-typed distributions |

`prob:jsd` is the stable numerical interface. For GMM paths it uses a fixed MC-10K estimator; other supported distribution types use their own type-specific numerical implementations or a sample-based fallback.

The legacy `prob:jsdivergence` wrapper still supports nine configurable V1-V5 / GT modes (via the `probsparql.mode` JVM property): fixed MC sample counts (`GT_100`, `GT_1K`, `GT_5K`, `GT_10K`), SPRT-based sequential testing (`V3_SPRT`), analytic bounds filtering (`V4_BOUNDS`), and a five-level adaptive cascade (`V5_ADAPTIVE`, the default). In V3/V4/V5, the returned score may be the by-product of a threshold-oriented similarity decision pipeline rather than a uniformly precise JSD estimator.

#### Category 3 — Transformations (7 functions)

`prob:scale`, `prob:shift`, `prob:linear`, `prob:marginal`, `prob:joint`, `prob:convolve`, `prob:multiply` — all return a new GMM literal computed analytically (when possible) or via sampling.

#### Category 4 — Distribution Manipulation (8 functions)

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

#### Category 5 — Property Functions (2)

`prob:exactJoin` and `prob:fuzzyJoin` are registered as SPARQL 1.1 magic property functions, enabling triple-pattern-style join syntax (e.g., `?a prob:fuzzyJoin (?b ?tol)`).

### 5.3 Initialisation Sequence

`ProbSPARQL.init()` is idempotent and thread-safe (guarded by `synchronized` + `initialized` flag). It must be called before any probabilistic query is executed. The sequence is:

1. `JenaSystem.init()` — ensures Jena base modules are loaded.
2. Register `GMMDatatype` and `HistogramDatatype` with Jena's `TypeMapper`.
3. Register all 22 SPARQL functions into `FunctionRegistry`.
4. Register 2 property functions into `PropertyFunctionRegistry`.
5. Register `QueryEngineProbabilistic.Factory` with `QueryEngineRegistry` (highest priority).

---

## 6. Impact Analysis: Does Experiment 1 Need Re-running?

### 6.1 What Experiment 1 Measures

Experiment 1 measures the **execution-time overhead** that ProbSPARQL introduces over standard deterministic SPARQL for four query types:

| Query | SPARQL constructs used |
|-------|----------------------|
| Q1 — Threshold Filtering | BGP + `BIND(prob:cdf(…) AS ?p)` + `FILTER` |
| Q2 — Statistical Summary | BGP + `BIND(prob:multiply(…))` + `BIND(prob:mean(…))` |
| Q3 — Distribution Comparison | BGP + `BIND(prob:jsd(…))` or legacy `BIND(prob:jsdivergence(…))` + `FILTER` |
| Q4 — Pure Graph Traversal | BGP only (no `prob:` functions) |

**None of the four queries use `SIMILARITYJOIN` or `FUSEJOIN` syntax.** All probabilistic computations are expressed via `BIND` + standard SPARQL `FILTER`.

### 6.2 Execution Path for BIND/FILTER Queries

For a query containing only `BIND` and `FILTER`:

1. **Parsing** — `ARQParser` produces `ElementBind` and `ElementFilter` nodes. The tokens `SIMILARITYJOIN` and `FUSEJOIN` never appear, so neither the new lookahead branch in `GroupGraphPatternSub` nor `SimilarityJoinGraphPattern()` is ever entered.

2. **Engine selection** — `QueryEngineProbabilistic.Factory.accept()` walks the syntax tree and finds no `ElementSimilarityJoin` or `ElementFuseJoin`. It returns `false`. The query is handled entirely by the pre-existing `QueryEngineMain`.

3. **Algebra compilation** — Because `QueryEngineMain` is used, `AlgebraGeneratorProbabilistic` is **not** invoked. The standard `AlgebraGenerator` compiles the query into `OpProject(OpFilter(OpExtend(OpBGP)))`. No `OpSimilarityJoin` is produced, and the `OpExt` fix is irrelevant.

4. **Execution** — `OpExecutorProbabilistic.exec()` is registered only inside `QueryEngineProbabilistic`'s context (step 2 of §4.1). Since `QueryEngineMain` is used, `OpExecutorProbabilistic` is **never registered** and the standard `OpExecutor` runs the plan.

5. **Function evaluation** — `prob:cdf`, `prob:mean`, `prob:jsd`, `prob:jsdivergence`, etc., are SPARQL scalar functions evaluated by Jena's standard expression evaluator when it encounters `OpExtend`. Their execution still stays on the scalar-function path; only the internal semantics of the legacy `prob:jsdivergence` wrapper differ from the newer numerical `prob:jsd` interface.

### 6.3 Analysis of Each Bug Fix

#### Fix A — `OpSimilarityJoin extends OpExt` (visitor pattern)

- **Scope of change**: `OpSimilarityJoin.java` only.  
- **Activation condition**: This fix has any effect only when `OpSimilarityJoin` is instantiated and placed into an algebra tree. `OpSimilarityJoin` is only created in `AlgebraGeneratorProbabilistic.compileSimilarityJoin()`, which is only called when an `ElementSimilarityJoin` node is present.  
- **Conclusion**: Because Experiment 1 queries contain no `SIMILARITYJOIN` syntax, `OpSimilarityJoin` is never instantiated, the `visit()` method is never called, and `TransformSimplify` never encounters this operator. **Fix A has zero effect on Experiment 1 query execution.**

#### Fix B — `ARQParser.java` lookahead dispatch

- **Scope of change**: The `GroupGraphPatternSub` loop in `ARQParser.java`. Specifically, the loop body was changed to test `jj_2_4(2147483647)` before each non-triple group element.  
- **Activation condition**: `jj_2_4` scans the upcoming token stream for the pattern `GroupGraphPattern() SIMILARITYJOIN`. This scan is a lookahead-only operation that does **not consume input**. It returns `false` for any token sequence that is not `LBRACE … RBRACE SIMILARITYJOIN`, which covers all tokens produced by standard SPARQL constructs including `FILTER`, `BIND`, `OPTIONAL`, `GRAPH`, `VALUES`, and plain BGP triples.  
- **Conclusion**: For every element in an Experiment 1 query, `jj_2_4` returns `false`, the new branch is never taken, and `GraphPatternNotTriples()` is called exactly as before. **Fix B has zero effect on Experiment 1 query parsing.**

### 6.4 Conclusion

**Experiment 1 does not need to be re-run.** The two recent bug fixes operate exclusively on the code paths activated by `SIMILARITYJOIN` syntax. The complete execution path for `BIND`/`FILTER` queries — from tokenisation through parsing, algebra compilation, engine selection, and iterator execution — is identical before and after the fixes. The numerical results, timing measurements, and overhead ratios produced by Experiment 1 remain fully valid.
