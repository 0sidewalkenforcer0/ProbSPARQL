# ProbSPARQL 代码库理解文档

> 文档版本：2026-05-11
> 项目版本：1.0.0-SNAPSHOT
> 基于 Apache Jena 6.0.0-SNAPSHOT + Java 21

---

## 1. 项目概述

**ProbSPARQL** 是对 Apache Jena 的概率扩展，旨在支持对含有不确定数值数据的知识图谱进行概率性 SPARQL 查询。核心思想是将传感器测量、物理建模等场景中固有的**属性级不确定性（attribute-level uncertainty）**直接编码进 RDF 数据，并通过扩展 SPARQL 语言暴露一套概率操作符。

### 1.1 核心能力

| 维度 | 内容 |
|---|---|
| **数据模型** | GMM、Histogram、Dirichlet 三类概率 literal，通过 Jena 自定义 RDF datatype 解析为内部值对象 |
| **SPARQL 函数** | 29 个概率函数（PDF/CDF、JSD/KL、采样、变换、融合、相等性判断等） |
| **特殊连接算子** | 2 个自定义连接（FUSEJOIN、DIVJOIN） |
| **服务器** | 基于 Jena-Fuseki 的 HTTP SPARQL 端点 |
| **JSD 计算方案** | 9 种计算模式（GT-100/1K/5K/10K, V1-V5） |

### 1.2 典型应用场景

项目以工业场景中的**角磨机**（Angle Grinder）的磨损检测与传感器融合为主要 Demo：
- 每个零件的尺寸参数（齿轮齿长、主轴直径等）用 GMM 建模
- 利用 CDF 判断零件磨损概率是否超过阈值
- 利用 FUSEJOIN 融合来自不同传感器（卡尺 vs 激光）的同一物理量的测量结果

---

## 2. 工程结构

```
ProbSPARQL/
├── pom.xml                        # Maven 构建（Java 21, Jena 6.0.0-SNAPSHOT, Jackson）
├── start-fuseki.sh                # 服务器启动脚本
├── jena/                          # Apache Jena 完整修改源码（约 50 个子模块）
│   ├── PROBSPARQL_EXTENSION.md    # 扩展说明文档
│   └── jena-arq/                  # 核心 ARQ（SPARQL 引擎）修改
├── src/
│   ├── main/java/org/apache/jena/
│   │   ├── probsparql/            # ProbSPARQL 核心实现
│   │   └── sparql/                # Jena 内部扩展（语法/代数/执行器）
│   └── test/java/                 # ProbSPARQL 单元测试
├── benchmark/
│   ├── README.md                  # 当前实验实现说明
│   ├── queries/                   # Exp1-Exp5 的 SPARQL 查询
│   ├── scripts/                   # 数据生成、远程实验运行、分析脚本
│   ├── data/                      # 生成数据目录（git ignore）
│   └── results/                   # 生成结果目录（git ignore）
├── examples/
│   ├── data/angle-grinder-instances.ttl  # 真实工业数据
│   └── queries/                   # 示例查询
└── grammar/probsparql_extensions.jj      # JavaCC 语法扩展参考
```

---

## 3. 数据模型：概率 Literal 层

**核心文件：** `src/main/java/org/apache/jena/probsparql/datatypes/`

当前实现注册三类概率 datatype：

| Datatype | URI | Java 值对象 | 说明 |
|---|---|---|---|
| GMM | `http://example.org/ontology/uncertainty#gmmLiteral` | `GMMValue` | 多维 Gaussian Mixture Model |
| Histogram | `http://example.org/ontology/uncertainty#histLiteral` | `HistogramValue` | 多维 histogram，CDF 语义为联合 CDF |
| Dirichlet | `http://example.org/ontology/uncertainty#dirichletLiteral` | `DirichletValue` | simplex 上的 Dirichlet 分布 |

### 3.1 Gaussian Mixture Model (GMM)

#### 数学定义

$$P(\mathbf{x}) = \sum_{k=1}^{K} w_k \cdot \mathcal{N}(\mathbf{x} \mid \boldsymbol{\mu}_k, \boldsymbol{\Sigma}_k)$$

其中：
- $K$：分量数量
- $d$：数据维度
- $w_k \geq 0$，$\sum_k w_k = 1$
- $\boldsymbol{\Sigma}_k$：协方差矩阵

#### 协方差类型

| 类型 | 描述 | 参数大小 |
|---|---|---|
| `full` | 完整 $d \times d$ 矩阵 | $K \times d^2$ |
| `diag` | 对角线，每维独立方差 | $K \times d$ |
| `spherical` | 标量，各维共享方差 | $K$ |

#### RDF 序列化格式（JSON Literal）

```json
{
  "K": 2,
  "d": 1,
  "covariance_type": "full",
  "weights": [0.6, 0.4],
  "means": [[10.2], [9.5]],
  "covariances": [[[0.04]], [[0.09]]]
}
```

作为自定义 Jena 类型注册，URI 为 `http://example.org/ontology/uncertainty#gmmLiteral`。

#### 验证规则（GMMValue.java）

- 权重求和为 1.0（容差 1e-3）
- 协方差矩阵需为正半定（Cholesky 分解检验）
- 维度一致性检查
- 关键方法：`deepCopy()`、`toJsonString()`、Cholesky 分解工具

### 3.2 Histogram Literal

`uq:histLiteral` 现在使用统一的多维 schema：

```json
{"dimensions":2,"edges":[[0.0,1.0,2.0],[10.0,20.0,30.0]],"weights":[0.1,0.2,0.3,0.4]}
```

其中 `weights` 表示多维网格 cell 的概率质量，按 row-major 顺序展开。旧的一维 schema `{"bins":[...],"weights":[...]}` 仍可解析，但系统输出统一使用新的 `dimensions + edges + weights` 形式。对多维 histogram，`prob:cdf` / `prob:histcdf` 的语义是联合 CDF：`P(X1<=x1, ..., Xd<=xd)`。

### 3.3 Dirichlet Literal

Dirichlet literal 用于表示 simplex 上的分类概率分布，当前主要服务 datatype extensibility、`prob:mean` / `prob:std` / `prob:cdf` / `prob:jsd` 等多态接口验证。

---

## 4. 29 个 SPARQL 函数

所有函数通过 `ProbSPARQL.init()` 注册到 `FunctionRegistry`，URI 前缀为 `http://probsparql.org/function#`。

### 4.1 概率阈值（Thresholding）5 个

| 函数 | URI 后缀 | 参数 | 返回 |
|---|---|---|---|
| PDF | `pdf` | distribution, point | 概率密度值 |
| CDF | `cdf` | distribution, point | 累积概率 $\in [0,1]$ |
| LogPDF | `logpdf` | distribution, point | $\ln$ 密度 |
| LogCDF | `logcdf` | distribution, point | $\ln$ 累积概率 |
| HistogramCDF | `histcdf` | Histogram, point | histogram CDF / 联合 CDF |

**实现要点（CDF.java）：** 入口按 literal value 类型分派；GMM 路径对每个分量高斯计算 CDF 并加权求和，Histogram 路径计算 joint CDF，Dirichlet 路径使用对应 value backend。

### 4.2 比较度量（Comparison）8 个

| 函数 | URI 后缀 | 数学定义 | 范围 |
|---|---|---|---|
| PolyJSD | `jsd` | $JS(P\|Q) = \frac{1}{2}KL(P\|M) + \frac{1}{2}KL(Q\|M)$ | $[0, \ln 2]$ |
| JSDivergence | `jsdivergence` | legacy GMM-only similarity-evaluator compatibility wrapper | score depends on mode |
| KLDivergence | `kldivergence` | $KL(P\|Q) = \mathbb{E}_P[\ln P/Q]$ | $[0, +\infty)$ |
| HistogramJSD | `histjsd` | histogram 专用 JSD | $[0, \ln 2]$ |
| JSDMode | `jsdMode` | benchmark 专用：按 mode 调用 V1-V5 GMM evaluator | numeric score |
| LastDivJoinStats | `lastDivJoinStats` | benchmark 专用：读取 server-side DIVJOIN pruning stats | numeric counter |
| SameTerm | `sameTerm` | RDF term equality | boolean |
| SameDistribution | `sameDistribution` | datatype value equality for distribution literals | boolean |

`prob:jsd` 是新的数值接口：多态分布比较，GMM 路径固定使用 MC 10K。  
`prob:jsdivergence` 保留为 legacy GMM-only 接口；其内部现在对应 similarity evaluator，主要服务旧的 V1-V5 模式和 join 场景。
`prob:sameTerm` 比较 RDF term 本身，因此对 GMM component 顺序敏感；`prob:sameDistribution` 比较解析后的分布值，因此对 GMM component 顺序不敏感。

**legacy `prob:jsdivergence` / similarity evaluator 的 9 种模式（SimilarityEvaluator.java，JSDivergenceConfig.java）：**

| 模式 | 策略 | 样本数 | 用途 |
|---|---|---|---|
| `GT_100` | 简单 Monte Carlo | 100 | 粗略 Ground Truth |
| `GT_1K` | 简单 Monte Carlo | 1,000 | 中等 GT |
| `GT_5K` | 简单 Monte Carlo | 5,000 | 精确 GT |
| `GT_10K` | 简单 Monte Carlo | 10,000 | 最精确 GT（对照基准） |
| `V1_MC` | 简单 Monte Carlo（数值估计器） | 可配 | 基线 JSD 估计 |
| `V2_STRATIFIED` | 分层采样（数值估计器） | 可配 | 改进精度 |
| `V3_SPRT` | 顺序假设检验 / 置信边界早停 | ≤ 最大值 | 面向 threshold 的快速判定 |
| `V4_BOUNDS` | 下界过滤 | ≤ 最大值 | 快速拒绝明显不同对 |
| `V5_ADAPTIVE` | V4+V3+V2 流水线 | 自适应 | **默认 legacy similarity pipeline** |

### 4.3 变换算子（Transformation）7 个

| 函数 | 数学含义 | 实现要点 |
|---|---|---|
| `scale` | $P(\lambda x)$ | 缩放均值和协方差 |
| `shift` | $P(x - c)$ | 平移均值 |
| `linearTransform` | $a_0 + a_1 x + \ldots$ | 多项式变换 |
| `marginal` | 提取第 $i$ 维 | 选择均值/协方差子矩阵 |
| `joint` | 独立 GMM 的联合分布 | 块对角协方差 |
| `convolve` | $P(x+y)$ 卷积 | 高斯卷积：加均值、合并协方差 |
| `multiply` | $P(xy)$ 乘积分布 | Delta 方法近似 |

### 4.4 操作算子（Manipulation）9 个

| 函数 | 说明 |
|---|---|
| `mean` | 期望值 $\mathbb{E}[X] = \sum_k w_k \mu_k$ |
| `std` | 标准差 |
| `quantile` | 百分位数（数值求解） |
| `map` | 最大后验估计（MAP） |
| `modeCount` | 模态数量（峰值个数） |
| `mix` | 从多个 GMM 创建混合分布 |
| `fuse` | **Bayesian 融合**（Gaussian Product） |
| `histmean` | Histogram 均值 |
| `sample` | 从支持的分布中抽样，返回 JSON array |

---

## 5. Bayesian 融合（Fuse.java）

**文件：** `src/main/java/org/apache/jena/probsparql/functions/manipulation/Fuse.java`

### 5.1 数学公式

对两个 GMM 的 $m$ 个先验分量和 $n$ 个似然分量，融合后共有 $m \times n$ 个分量：

$$\boldsymbol{\Sigma}_{fused} = \left(\boldsymbol{\Sigma}_p^{-1} + \boldsymbol{\Sigma}_l^{-1}\right)^{-1}$$

$$\boldsymbol{\mu}_{fused} = \boldsymbol{\Sigma}_{fused}\left(\boldsymbol{\Sigma}_p^{-1}\boldsymbol{\mu}_p + \boldsymbol{\Sigma}_l^{-1}\boldsymbol{\mu}_l\right)$$

$$w_{ij} \propto c_{ij} \cdot w_p^{(i)} \cdot w_l^{(j)}$$

其中一致性度量：

$$c_{ij} = \mathcal{N}(\boldsymbol{\mu}_p^{(i)}; \boldsymbol{\mu}_l^{(j)}, \boldsymbol{\Sigma}_p^{(i)} + \boldsymbol{\Sigma}_l^{(j)})$$

### 5.2 实现细节

- 矩阵逆通过 LU 分解（带主元）实现（见 `MatrixUtils.java`）
- 输出格式固定为 `full` 协方差
- 若所有权重归一化后为 0 则抛出异常（不兼容分布对）

---

## 6. 特殊连接算子

### 6.1 FUSEJOIN 语法

```sparql
{ ?s ag:hasMeasure ?ct . ?ct uq:hasDist ?distCT . }
FUSEJOIN(?distCT, ?distLaser, 0.3, ?fused)
{ ?s ag:getLaserMeasure ?laser . ?laser uq:hasDist ?distLaser . }
```

**语义：** 对左右两侧模式的所有笛卡尔积，若 $JS(\mathtt{distL}, \mathtt{distR}) \leq \text{tolerance}$，则将融合结果绑定到结果变量。

### 6.2 DIVJOIN 语法

```sparql
{ ?s uq:hasDistL ?gl . }
DIVJOIN(?gl, ?gr, 0.3, 0.05)
{ ?t uq:hasDistR ?gr . }
```

**语义：** 返回满足 `JSD(?gl, ?gr) <= 0.3` 的匹配对。第 4 个参数 `0.05` 是单侧置信边界的 tail probability，用于 `V3_SPRT` 以及 `V5_ADAPTIVE` 内部的 sequential confidence-bound decision。

### 6.3 实现栈（完整流水线）

```
SPARQL 字符串（含 FUSEJOIN/DIVJOIN 关键字）
        ↓
修改后的 JavaCC 解析器（jena-arq/src/main/javacc/sparql_11.jj）
        ↓
语法元素：ElementFuseJoin / ElementSimilarityJoin
        ↓
AlgebraGeneratorProbabilistic.compile()
        ↓
代数算子：OpFuseJoin / OpSimilarityJoin
        ↓
QueryEngineProbabilistic（工厂注册覆盖默认引擎）
        ↓
OpExecutorProbabilistic.execute()（分派到概率算子）
        ↓
QueryIterFuseJoin / QueryIterSimilarityJoin
  1. 物化左表（materialize left）
  2. 物化右表（materialize right）
  3. 嵌套循环：merge bindings → JS divergence → fuse/filter
        ↓
结果绑定流（ResultSet）
```

### 6.4 关键文件对照表

| 层次 | FuseJoin | SimilarityJoin |
|---|---|---|
| 语法元素 | `sparql/syntax/ElementFuseJoin.java` | `sparql/syntax/ElementSimilarityJoin.java` |
| 代数算子 | `sparql/algebra/op/OpFuseJoin.java` | `sparql/algebra/op/OpSimilarityJoin.java` |
| 代数生成器 | `sparql/algebra/AlgebraGeneratorProbabilistic.java` | 同文件 |
| 执行引擎 | `sparql/engine/QueryEngineProbabilistic.java` | 同文件 |
| 执行器分派 | `sparql/engine/main/OpExecutorProbabilistic.java` | 同文件 |
| 迭代器 | `sparql/engine/iterator/QueryIterFuseJoin.java` | `QueryIterSimilarityJoin.java` |
| 注册中心 | `sparql/engine/join/ProbabilisticJoins.java` | 同文件 |

---

## 7. V5 自适应采样器（AdaptiveSampler.java）

这是 JSD 计算的核心优化，完整决策流程如下：

```
输入：GMM 对 (P, Q)，最大样本数 maxSamples
        ↓
Bounds Check（O(1) 解析边界，BoundsFilterSampler）
→ 若 lower_bound > tolerance：直接拒绝（filteredByBounds++）
→ 若 upper_bound ≤ tolerance：直接接受
        ↓
SPRT 测试（顺序概率比检验，SPRTSampler）
→ 固定步长采样，累积似然比
→ 若超过上边界：接受；若低于下边界：拒绝
→ 若达到最大采样：进入下一阶段
        ↓
分层采样（StratifiedSampler）
→ 按组件权重比例分配样本
→ 使用 Cholesky 抽样，数值稳定
→ 计算最终 JSD 估计值
```

**统计跟踪**（用于 V5 分解分析）：
- `filteredByBounds` — 被边界过滤的对数
- `earlyBySPRT` — SPRT 早停的对数
- `fullStratified` — 需要完整分层采样的对数
- 各阶段耗时（纳秒）

---

## 8. 基准测试框架

当前 benchmark 采用 remote-first 方式：本地 Java benchmark client 通过 HTTP 调用预先加载在 Fuseki 上的数据集服务。`benchmark/data` 和 `benchmark/results` 是生成目录，不作为当前实验协议的一部分提交。详细说明见 `benchmark/README.md`。

### 8.1 当前实验套件

| 实验 | Java Runner | 目的 | 关键输入 |
|---|---|---|---|
| Exp1 component | `ScalabilityBenchmark.java` | DET vs PROB 系统开销 | `benchmark/queries/exp1/component` |
| Exp1 dimension | `Exp1DimensionBenchmark.java` | 固定 K=3，维度 1/2/4/8 的扩展性 | `benchmark/queries/exp1/dimension` |
| Exp1 permutation | `Exp1PermutationBenchmark.java` | GMM component 顺序扰动下的语义稳定性 | `benchmark/queries/exp1/permutation` |
| Exp2 | `Exp2Benchmark.java` | In-engine filtering vs `DIVJOIN` | `benchmark/queries/exp2` |
| Exp3 | `Exp3Benchmark.java` | V1/V2/V3/V4/V5 JSD decision 方法对比 | remote TTL 中的 `prob:referenceJSD` |
| Exp4 | `Exp4*.java` | GMM / Histogram / Dirichlet datatype extensibility | `benchmark/queries/exp4` |
| Exp5 | `Exp5Benchmark.java` | In-engine early filter vs client-side post-processing | `benchmark/queries/exp5` |

### 8.2 运行方式

所有正式脚本位于 `benchmark/scripts/ExperimentsN`，通过 `ENDPOINT_TEMPLATE` 指定远程 Fuseki endpoint：

```bash
export ENDPOINT_TEMPLATE='https://fujitsu.example.org/{dataset}/query'
bash benchmark/scripts/Experiments2/run_exp2.sh
```

其中 `{dataset}` 会被替换成实验约定的 service name，例如 `exp2_npairs_5000_uf_0p2`、`simjoin_easy`、`exp1_E5_K3_D4` 等。

---

## 9. 初始化与注册系统

**入口：** `ProbSPARQL.init()`（`ProbSPARQL.java`）

初始化顺序：
1. Jena 系统初始化（`JenaSystem.init()`）
2. 注册 GMM、Histogram、Dirichlet 三类自定义数据类型（`TypeMapper`）
3. 注册 29 个 SPARQL 函数（`FunctionRegistry`）
4. 注册属性函数 `FuzzyJoinPF`、`ExactJoinPF`（`PropertyFunctionRegistry`）
5. 初始化概率连接策略（`ProbabilisticJoins`）
6. 注册概率查询引擎工厂（`QueryEngineRegistry`），由 `QueryEngineProbabilistic` 在含 `FUSEJOIN` / `DIVJOIN` 的查询中安装概率代数生成器和执行器

---

## 10. 服务器端部署

**文件：** `src/main/java/org/apache/jena/probsparql/server/ProbSPARQLFuseki.java`

- 基于 Jena-Fuseki 嵌入式服务器
- 启动时调用 `ProbSPARQL.init()`，注册所有扩展
- 默认端口：3030，dataset：`/probsparql`
- 支持 SPARQL Query/Update/Graph Store 协议

---

## 11. 测试覆盖

| 测试类 | 覆盖范围 |
|---|---|
| `GMMDatatypeTest.java` | GMM 序列化/反序列化、验证规则 |
| `ComparisonFunctionsTest.java` | JSD/KL 数值正确性 |
| `ManipulationFunctionsTest.java` | Mean/Std/Map/Fuse 数值正确性 |
| `ThresholdingFunctionsTest.java` | PDF/CDF 数值正确性 |
| `TransformationFunctionsTest.java` | Scale/Shift/Marginal 等变换 |
| `ProbabilisticJoinTest.java` | 属性函数连接 |
| `ProbabilisticJoinsTest.java` | FUSEJOIN/DIVJOIN 集成 |
| `TestOpFuseJoinExecution.java` | OpFuseJoin 代数执行 |
| `ProbSPARQLTest.java` | 端到端查询测试 |
| `GMMDataLoadingTest.java` | 真实数据加载 |

---

## 12. 构建流程

```bash
# 第一步（耗时约 10-15 分钟）：构建修改版 Jena
cd jena && mvn clean install -DskipTests && cd ..

# 第二步：构建 ProbSPARQL
mvn clean compile

# 运行测试
mvn test

# 启动 HTTP 端点
mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.server.ProbSPARQLFuseki"

# 运行正式 benchmark（远程 Fuseki endpoint）
export ENDPOINT_TEMPLATE='https://fujitsu.example.org/{dataset}/query'
bash benchmark/scripts/Experiments1/component/run_exp1_component.sh
bash benchmark/scripts/Experiments2/run_exp2.sh
bash benchmark/scripts/Experiments3/run_exp3.sh
```

---

## 13. 关键设计决策

1. **多态概率 datatype 层**：GMM 是主要数值不确定性表示，Histogram 和 Dirichlet 用于非参数分布和 datatype extensibility；`prob:jsd` 等接口尽量通过统一的 value 层分派。

2. **JSD 而非 KL 作为匹配度量**：JSD 对称、有界（$[0, \ln 2]$），可直接作为连接阈值；KL 散度在实践中可能发散至无穷。

3. **V5 自适应采样（三阶段过滤）**：通过边界过滤 → SPRT 早停 → 分层采样三级渐进，在精度和延迟间取得最佳平衡。

4. **侵入式 Jena 修改**：通过直接修改 Jena 源码（JavaCC 解析器、代数层、执行层），而非纯插件方式，以支持新语法关键字 `FUSEJOIN`/`DIVJOIN`。

5. **物化嵌套循环连接**：当前连接实现为内存物化的嵌套循环，对大数据集存在扩展性挑战；`DIVJOIN` 的 pruning cascade 主要减少 full JSD 计算，不改变候选对生成复杂度。

---

## 14. 已知局限与未来工作方向

| 局限 | 说明 |
|---|---|
| 嵌套循环连接 | $O(N \times M)$ 复杂度，无索引加速 |
| 内存物化 | 大数据集可能 OOM |
| 单线程 JSD | 连接中每对 JSD 串行计算，无并行 |
| GMM 组件爆炸 | Fuse 后 $K_1 \times K_2$ 个分量，反复融合后快速膨胀 |
| 无近似查询评估 | 连接必须扫描全部候选对（V4/V5 只过滤 JSD 计算，不跳过候选对生成） |
