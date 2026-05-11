# Artifact 对齐论文的差异清单

本文档以 `ISWC26_ProbSPARQL.pdf` 和 `ISWC_SupplementaryMaterial.pdf` 为准，记录当前代码、benchmark、结果文件中还没有对齐论文的地方。

原则：论文不再修改。后续需要修改的是代码、benchmark query、runner、数据生成脚本、结果文件和仓库文档，使 artifact 能支撑论文中的接口、语义和实验表述。

## 1. 公开接口需要补齐

### 1.1 实现 `prob:divergenceTest`

论文和补充材料把 `prob:divergenceTest(E1, E2, epsilon, alpha)` 定义为 Boolean divergence-test filter predicate。其语义是：

- 输入两个 distribution-valued expressions。
- 输入 tolerance `epsilon` 和 decision parameter `alpha`。
- 当配置的 divergence decision strategy 不能拒绝 `H0: JSD(P,Q) <= epsilon` 时返回 `true`。
- 它和 `DIVJOIN` 使用同一类 Boolean compatibility decision；区别是 `prob:divergenceTest` 是 filter function，`DIVJOIN` 是 join operator。

当前代码没有该函数，`ProbSPARQL.init()` 中也没有注册。

需要改动：

- 新增 `src/main/java/org/apache/jena/probsparql/functions/comparison/DivergenceTest.java`。
- 在 `src/main/java/org/apache/jena/probsparql/ProbSPARQL.java` 中注册 URI：

```text
http://probsparql.org/function#divergenceTest
```

- 内部应复用当前 `evaluateSimilarity(leftNode, rightNode, epsilon, alpha)` 或等价 Boolean decision path。
- 返回类型应为 boolean，而不是 JSD 数值。
- 增加测试：
  - GMM-GMM compatible pair returns true。
  - GMM-GMM incompatible pair returns false。
  - histogram/Dirichlet 支持路径和 `DIVJOIN` 保持一致。
  - 维度不匹配或非 distribution literal 抛出 SPARQL function error。

### 1.2 保留并解释 `prob:jsd` 与 `prob:divergenceTest` 的层次差异

论文区分两层接口：

- `prob:jsd(E1,E2)`：返回 scalar Jensen-Shannon divergence estimate。
- `prob:divergenceTest(E1,E2,epsilon,alpha)`：返回 Boolean compatibility decision。
- `DIVJOIN(?d1, ?d2, epsilon, alpha)`：把同样的 Boolean decision 放到 join condition 中。

当前代码中 `prob:jsd` 已基本符合论文方向，但 artifact 文档和 query 需要统一这个层次。

需要改动：

- README、overview、examples 中不要再把 `prob:jsd`、legacy `prob:jsdivergence`、`DIVJOIN` 混写成同一类接口。
- 数值 JSD query 使用 `prob:jsd`。
- Boolean decision filter 使用新增的 `prob:divergenceTest`。
- Join 场景使用 `DIVJOIN`。
- `prob:jsdivergence` 只作为 legacy compatibility API 出现，不作为论文主接口。

## 2. V3 / V5 对外命名需要对齐论文中的 SPRT

论文已经固定使用：

- `SPRT`
- `Wald-SPRT`
- sequential probability ratio test
- likelihood-ratio boundary
- Adaptive-Cascade = deterministic bound -> SPRT -> stratified MC fallback

当前代码里的 `SPRTSampler` 内部实现已经调整为 sequential confidence-bound / two one-sided tests 风格。这里先不要求修改算法逻辑；为了对齐已经冻结的论文，artifact 层面只需要把对外命名、benchmark label、文档术语统一回论文中的 `SPRT` / `V3_SPRT`。

需要改动：

- 不修改 `SPRTSampler` / `AdaptiveSampler` 的内部算法逻辑。
- Exp3、benchmark、README、overview 等对外方法标签恢复为论文用语：

```text
V3_SPRT
```

- 之前 benchmark 中改成 `V3_TOST` 的地方需要改回：
  - `src/main/java/org/apache/jena/probsparql/Exp3Benchmark.java`
  - `benchmark/scripts/Experiments3/analyze_exp3.py`
  - `benchmark/scripts/Experiments3/run_exp3.sh`
  - `benchmark/experiment_plan.md`
  - README / overview 中相关描述

注意：

- class 名 `SPRTSampler` 本身已经符合论文命名。
- 当前实现细节先不动，后续只有在需要严格复现 Wald-SPRT 统计过程时再单独评估。

## 3. Exp3 结果表需要重新生成并对齐论文 Table 1 / Table S.5

当前 `benchmark/results/exp3/exp3_classification.csv` 中，部分 MAE 数值和论文表格存在 V3/V4 互换迹象。例如 Hard workload：

- 当前 CSV：
  - `V3_SPRT`: `MAE = 0.018363`
  - `V4_BOUNDS`: `MAE = 0.006199`
- 论文 Table 1 / Table S.5：
  - `SPRT`: `.0062`
  - `Det-Bound`: `.0184`

既然论文表格固定，artifact 应重新生成与论文一致的结果文件。

需要改动：

- 在对外标签恢复为 `V3_SPRT` 后重新运行 Exp3。
- 确认输出方法名和论文一致：
  - `MC-Naive`
  - `MC-Stratified`
  - `SPRT`
  - `Det-Bound`
  - `Adaptive-Cascade`
- 如果代码内部仍使用 `V1_MC`、`V2_STRATIFIED` 等标签，分析脚本需要导出论文友好的 label。
- 更新：
  - `benchmark/results/exp3/exp3_classification.csv`
  - `benchmark/results/exp3/exp3_per_pair.csv`
  - 对应图表或 summary 文件

验收标准：

- Hard/Mixed workload 的 F1、MAE、latency 能复现论文 Table 1。
- Full supplement Table S.5 能从 artifact CSV 直接追溯。

## 4. Histogram datatype 需要兼容论文 schema

论文中的 histogram 表达方式包括：

1D 示例：

```json
{"dimensions": 1, "edges": [0, 10, 20], "weights": [0.3, 0.7]}
```

2D 示例中 `weights` 是嵌套 tensor：

```json
{
  "dimensions": 2,
  "edges": [[0.0, 10.0, 20.0], [0.0, 5.0, 10.0]],
  "weights": [[0.10, 0.20], [0.30, 0.40]]
}
```

当前实现更偏向 flat canonical schema：

```json
{"dimensions": 2, "edges": [[...], [...]], "weights": [0.10, 0.20, 0.30, 0.40]}
```

代码需要支持论文 schema，而不是要求论文改。

需要改动：

- `HistogramDatatype` 新 schema parser 支持：
  - `dimensions = 1` 时，`edges` 可以是 flat array，也可以是 nested array。
  - `weights` 可以是 flat array，也可以是维度匹配的 nested tensor。
- 内部 `HistogramValue` 可以继续保存 flat weights。
- `unparse()` 可以继续输出 canonical flat schema，但 parse 必须接受论文中的 nested schema。
- 增加测试：
  - 论文 1D 示例可解析。
  - 论文 2D 示例可解析。
  - 解析后的 mean/cdf/pdf/jsd 与 flat schema 等价。

## 5. Cross-type JSD / DIVJOIN 需要实现论文中的 routing

Supplement S.8.9 规定 cross-type comparison 的语义更强：

- GMM vs Histogram：parametric operand projected to histogram bin masses，走 deterministic-bound + bins。
- Dirichlet vs Histogram：同样利用 histogram bin partition。
- GMM vs Dirichlet：使用 explicit application-level reference-domain mapping，走 SPRT。
- 如果没有 compatible mapping，表达式 undefined / error。

当前实现主要是 `Sampleable` sample-based fallback，且要求 dimensions 一致。这不能支撑论文 Table S.8。

需要改动：

- 为 cross-type comparison 增加 routing 层，而不是统一落到 sample-based fallback：
  - Hist-involved pair 优先使用 histogram bin partition projection。
  - 可计算 discrete JSD lower bound。
  - Boolean decision 场景可用 deterministic-bound pre-filter。
  - unresolved case 再进入 SPRT 或 fallback。
- 明确 GMM-Dirichlet 的 reference-domain mapping 配置方式。
  - 如果论文实验使用固定 application-level mapping，artifact 中应提供对应代码和数据说明。
- `prob:jsd` scalar path 和 `DIVJOIN` Boolean path 都要能解释 cross-type 行为：
  - scalar `prob:jsd` 返回可复现数值。
  - `DIVJOIN` 返回 Boolean-compatible pairs。

需要补/更新实验：

- 重新生成 `benchmark/data/exp4/exp4_crosstype_gt.csv`，不能包含 `nan`。
- 重新跑 `Exp4CrossTypeJSD`。
- 输出能支撑 Table S.8 的 precision、recall、F1、latency。

## 6. Supplement S.8.7 的 complete DIVJOIN runtime 需要补 active benchmark

论文 Table S.6 是完整 `DIVJOIN` query runtime，不是单独 per-pair classification。

它比较：

- MC-Naive
- MC-Stratified
- SPRT
- Det-Bound
- Adaptive-Cascade

并使用 Easy / Medium / Hard / Mixed workload。

当前 active benchmark 缺少能直接复现 Table S.6 的 runner 和结果文件。

需要改动：

- 新增或恢复一个 active Exp3/Exp2 runner，用于完整 `DIVJOIN` query runtime。
- 输入数据应对应论文中的 Easy / Medium / Hard / Mixed workloads。
- 每个 workload 含 N=2400 candidate pairs。
- 输出字段至少包括：
  - Workload
  - Strategy
  - ReturnedPairs
  - LatencyMs
  - Speedup
- 结果应保存到 active result path，而不是只留在 `legacy_5variants`。

验收标准：

- artifact 中有 CSV 能直接追溯 Supplement Table S.6。
- result count 和论文表一致或在 README 中说明运行随机性/容差。

## 7. GMM dimensionality scaling 需要改成论文维度集合

Supplement S.8.8 固定维度集合为：

```text
d in {1, 2, 4, 8}
```

当前 benchmark 使用：

```text
d in {1, 3, 5, 10}
```

需要改动：

- `src/main/java/org/apache/jena/probsparql/Exp1DimensionBenchmark.java`
- `benchmark/scripts/Experiments1/dimension/generate_exp1_dimension.py`
- `benchmark/scripts/Experiments1/dimension/run_exp1_dimension.sh`
- `benchmark/scripts/Experiments1/dimension/README.md`

将默认维度改成：

```text
1 2 4 8
```

然后重新生成：

- `benchmark/data/exp1/dimension`
- `benchmark/results/exp1/dimension/exp1_dimension_raw.csv`
- `benchmark/results/exp1/dimension/exp1_dimension_summary.csv`

同时清理 Q4 variant：

- 论文中的 distribution comparison 应对应一个明确路径。
- 如果论文描述的是 scalar JSD，应使用 `prob:jsd`。
- 不应在主结果中混入 `JSDIVERGENCE` legacy variant，除非 supplement 明确解释该对照。

## 8. Exp1 / Exp2 的 query 与结果需要按论文语义重跑

当前 active queries 已经部分改成：

- scalar JSD 使用 `prob:jsd`
- similarity decision 使用 `DIVJOIN`

但现有结果文件可能仍来自旧的 `prob:jsdivergence` 或 legacy adaptive path。

需要改动：

- 基于当前 query 重新跑 active experiments。
- 确认论文引用的 result table 对应当前 query，而不是旧 query。
- 对历史结果目录做隔离：
  - 当前论文结果放 active result path。
  - 旧结果移动或标注为 legacy/archive。

重点检查：

- `benchmark/results/exp1/dimension/exp1_dimension_summary.csv`
- `benchmark/results/exp2/legacy_5variants`
- `benchmark/results/exp3`
- `benchmark/results/exp4`

## 9. Artifact 文档需要跟论文术语统一

仓库文档中仍有一些和论文不一致的术语或新接口。

需要改动：

- README / overview 中将 `V3_TOST` 改回论文使用的 `SPRT` / `V3_SPRT`。
- `DIVJOIN` 第四参数解释为论文中的 decision parameter / significance level `alpha`。
- `prob:jsdivergence` 只作为 legacy compatibility 说明。
- `prob:divergenceTest` 实现后加入 function table。
- `prob:sample` 和 `prob:sameTerm` 如果论文没有覆盖，应标为 artifact extension，不放入论文核心接口清单。

## 10. 建议实施顺序

1. 实现 `prob:divergenceTest`，补齐论文公开接口。
2. 将 V3/V5 对外名称和文档标签恢复为论文中的 SPRT / Adaptive-Cascade 术语，不改内部算法逻辑。
3. 让 Exp3 输出重新对齐 Table 1 / Table S.5。
4. 扩展 `HistogramDatatype` parser，兼容论文中的 histogram schema。
5. 实现 cross-type routing，重跑 Exp4，生成可支撑 Table S.8 的结果。
6. 补 complete DIVJOIN runtime benchmark，支撑 Table S.6。
7. 将 dimension benchmark 默认维度改成 `{1,2,4,8}`，重跑 Table S.7。
8. 重新跑 Exp1/Exp2 active experiments，确保结果来自当前论文语义。
9. 最后同步 README、overview、examples 和 benchmark documentation。
