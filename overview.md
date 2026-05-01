# ProbSPARQL 代码库理解文档

> 文档版本：2026-03-20  
> 项目版本：1.0.0-SNAPSHOT  
> 基于 Apache Jena 6.0.0-SNAPSHOT + Java 21

---

## 1. 项目概述

**ProbSPARQL** 是对 Apache Jena 的概率扩展，旨在支持对含有不确定数值数据的知识图谱进行概率性 SPARQL 查询。核心思想是将传感器测量、物理建模等场景中固有的**属性级不确定性（attribute-level uncertainty）**直接编码进 RDF 数据，并通过扩展 SPARQL 语言暴露一套概率操作符。

### 1.1 核心能力

| 维度 | 内容 |
|---|---|
| **数据模型** | Gaussian Mixture Models (GMMs) 作为自定义 RDF 数据类型 |
| **SPARQL 函数** | 22 个概率函数（PDF/CDF、JSD/KL、变换、融合等） |
| **特殊连接算子** | 2 个自定义连接（FUSEJOIN、SIMILARITYJOIN） |
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
│   │   ├── probsparql/            # ProbSPARQL 核心实现（63 个 Java 文件）
│   │   └── sparql/                # Jena 内部扩展（语法/代数/执行器）
│   └── test/java/                 # 单元测试（11 个 ProbSPARQL 相关测试类）
├── benchmark/
│   ├── data/                      # 合成数据集（S0-S4, simjoin easy/medium/hard/mixed）
│   ├── queries/                   # 18 个 SPARQL 基准查询
│   ├── results/                   # 已产生的 CSV 实验结果
│   └── scripts/                   # Python 绘图脚本
├── examples/
│   ├── data/angle-grinder-instances.ttl  # 真实工业数据
│   └── queries/                   # 示例查询
└── grammar/probsparql_extensions.jj      # JavaCC 语法扩展参考
```

---

## 3. 数据模型：Gaussian Mixture Model (GMM)

**核心文件：** `src/main/java/org/apache/jena/probsparql/datatypes/GMMDatatype.java` 和 `GMMValue.java`

### 3.1 数学定义

$$P(\mathbf{x}) = \sum_{k=1}^{K} w_k \cdot \mathcal{N}(\mathbf{x} \mid \boldsymbol{\mu}_k, \boldsymbol{\Sigma}_k)$$

其中：
- $K$：分量数量
- $d$：数据维度
- $w_k \geq 0$，$\sum_k w_k = 1$
- $\boldsymbol{\Sigma}_k$：协方差矩阵

### 3.2 协方差类型

| 类型 | 描述 | 参数大小 |
|---|---|---|
| `full` | 完整 $d \times d$ 矩阵 | $K \times d^2$ |
| `diag` | 对角线，每维独立方差 | $K \times d$ |
| `spherical` | 标量，各维共享方差 | $K$ |

### 3.3 RDF 序列化格式（JSON Literal）

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

作为自定义 Jena 类型注册，URI 为 `http://example.org/gmm-datatype`。

### 3.4 验证规则（GMMValue.java）

- 权重求和为 1.0（容差 1e-3）
- 协方差矩阵需为正半定（Cholesky 分解检验）
- 维度一致性检查
- 关键方法：`deepCopy()`、`toJsonString()`、Cholesky 分解工具

---

## 4. 22 个 SPARQL 函数

所有函数通过 `ProbSPARQL.init()` 注册到 `FunctionRegistry`，URI 前缀为 `http://probsparql.org/function#`。

### 4.1 概率阈值（Thresholding）4 个

| 函数 | URI 后缀 | 参数 | 返回 |
|---|---|---|---|
| PDF | `pdf` | GMM, point | 概率密度值 |
| CDF | `cdf` | GMM, point | 累积概率 $\in [0,1]$ |
| LogPDF | `logpdf` | GMM, point | $\ln$ 密度 |
| LogCDF | `logcdf` | GMM, point | $\ln$ 累积概率 |

**实现要点（CDF.java）：** 对每个分量高斯计算 CDF（多维积分），加权求和，使用 erf 函数近似。

### 4.2 比较度量（Comparison）2 个

| 函数 | URI 后缀 | 数学定义 | 范围 |
|---|---|---|---|
| PolyJSD | `jsd` | $JS(P\|Q) = \frac{1}{2}KL(P\|M) + \frac{1}{2}KL(Q\|M)$ | $[0, \ln 2]$ |
| JSDivergence | `jsdivergence` | legacy GMM-only similarity-evaluator compatibility wrapper | score depends on mode |
| KLDivergence | `kldivergence` | $KL(P\|Q) = \mathbb{E}_P[\ln P/Q]$ | $[0, +\infty)$ |

`prob:jsd` 是新的数值接口：多态分布比较，GMM 路径固定使用 MC 10K。  
`prob:jsdivergence` 保留为 legacy GMM-only 接口；其内部现在对应 similarity evaluator，主要服务旧的 V1-V5 模式和 join 场景。

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
| `lineartransform` | $a_0 + a_1 x + \ldots$ | 多项式变换 |
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
| `modecount` | 模态数量（峰值个数） |
| `mix` | 从多个 GMM 创建混合分布 |
| `fuse` | **Bayesian 融合**（Gaussian Product） |

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

### 6.2 SIMILARITYJOIN 语法

```sparql
{ ?s uq:hasDistL ?gl . }
SIMILARITYJOIN(?gl, ?gr, 0.3, 0.05)
{ ?t uq:hasDistR ?gr . }
```

**语义：** 返回满足 `JSD(?gl, ?gr) <= 0.3` 的匹配对。第 4 个参数 `0.05` 是单侧置信边界的 tail probability，用于 `V3_SPRT` 以及 `V5_ADAPTIVE` 内部的 sequential confidence-bound decision。

### 6.3 实现栈（完整流水线）

```
SPARQL 字符串（含 FUSEJOIN/SIMILARITYJOIN 关键字）
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

### 8.1 已有基准测试类

| 类 | 目的 | 输出 |
|---|---|---|
| `PerformanceBenchmark.java` | U1-U6 六类查询的端到端延迟 | JSON + Markdown 表 |
| `SimilarityJoinBenchmark.java` | V1-V5+GT_10K 各模式在 4 个数据集上的延迟 | `simjoin_results.csv`、`simjoin_v5_breakdown.csv` |
| `SimilarityJoinAccuracyBenchmark.java` | 各模式 MAE/RMSE/相关系数（vs GT_10K） | `simjoin_accuracy_latency.csv` |
| `ConvergenceBenchmark.java` | 分层采样在不同样本数下的收敛 | `simjoin_convergence.csv` |
| `ScalabilityBenchmark.java` | 不同数据规模（100-5000 实体）× GMM 复杂度（K=1-5） | CSV |
| `SamplingBenchmark.java` | 单函数级的采样策略对比 | — |
| `SamplingStrategyBenchmark.java` | 各采样策略的详细分解 | — |
| `ComprehensiveBenchmark.java` | 综合场景基准 | — |
| `FullBenchmark.java` | 集成运行所有基准 | — |

### 8.2 基准数据集

| 数据集 | 路径 | 内容 |
|---|---|---|
| S0-S4 | `benchmark/data/benchmark_S*.ttl` | SimilarityJoin 合成数据 |
| JSD_easy/mixed/hard | `benchmark/data/benchmark_JSD_*.ttl` | 按 JSD 难度分级 |
| simjoin_easy/medium/hard/mixed | `benchmark/data/simjoin_*.ttl` | selectivity 梯度数据集 |
| angle-grinder | `examples/data/angle-grinder-instances.ttl` | 真实工业传感器数据 |

### 8.3 基准查询（18 个 .sparql 文件）

| 查询 | 操作 | selectivity | 复杂度 |
|---|---|---|---|
| `MQ1_cdf_strict` | CDF 阈值过滤（严格） | ~5% | 低 |
| `MQ1_cdf_loose` | CDF 阈值过滤（宽松） | ~40% | 低 |
| `MQ2_jsd_strict` | JSD 比较（严格） | ~10% | 低 |
| `MQ2_jsd_loose` | JSD 比较（宽松） | ~40% | 低 |
| `MQ3_product_filtered` | 分布乘积 + 均值过滤 | ~35% | 中 |
| `MQ3_product_nofilter` | 分布乘积（无过滤） | 100% | 中 |
| `MQ4_fusion_map` | FUSEJOIN + MAP 提取 | ~80% | 高 |
| `MQ4_fusion_mean_std` | FUSEJOIN + 均值/标准差 | ~80% | 高 |
| `MacroQ1_wear_detection` | 磨损检测（CDF ≥ 0.85） | ~25% | 低-中 |
| `MacroQ2_anomaly_then_fuse` | 异常检测 → 融合二步流水线 | ~20% | 高 |
| `MacroQ3_power_and_wear` | 电机功率 × 磨损联合查询 | ~30% | 高 |
| `MacroQ4_full_inspection_report` | UNION 三路诊断分支 | 可变 | 极高 |
| `JSD_loose/medium/strict` | 纯 JSD 选择性测试 | 60%/25%/5% | 中 |
| `simjoin_benchmark` | SIMILARITYJOIN 基准 | 依数据集 | 高 |

---

## 9. 初始化与注册系统

**入口：** `ProbSPARQL.init()`（`ProbSPARQL.java`）

初始化顺序：
1. Jena 系统初始化（`JenaSystem.init()`）
2. 注册 GMM 自定义数据类型（`TypeMapper`）
3. 注册 22 个 SPARQL 函数（`FunctionRegistry`）
4. 注册属性函数 `FuzzyJoinPF`、`ExactJoinPF`（`PropertyFunctionRegistry`）
5. 覆盖代数生成器（`AlgebraGeneratorProbabilistic`）
6. 注册概率查询引擎工厂（`QueryEngineRegistry`）
7. 注册概率执行器（`QC.setFactory(OpExecutorProbabilistic.FACTORY)`）
8. 注册概率连接策略（`ProbabilisticJoins`）

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
| `ProbabilisticJoinsTest.java` | FUSEJOIN/SIMILARITYJOIN 集成 |
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

# 运行基准测试（示例）
mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.SimilarityJoinBenchmark"
mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.PerformanceBenchmark"

# 切换 JSD 计算模式
mvn exec:java -Dprobsparql.mode=V5_ADAPTIVE -Dexec.mainClass="..."
```

---

## 13. 关键设计决策

1. **GMM 作为统一不确定性表示**：相比简单高斯，GMM 支持多峰、非对称分布，更贴近真实传感器误差分布。

2. **JSD 而非 KL 作为匹配度量**：JSD 对称、有界（$[0, \ln 2]$），可直接作为连接阈值；KL 散度在实践中可能发散至无穷。

3. **V5 自适应采样（三阶段过滤）**：通过边界过滤 → SPRT 早停 → 分层采样三级渐进，在精度和延迟间取得最佳平衡。

4. **侵入式 Jena 修改**：通过直接修改 Jena 源码（JavaCC 解析器、代数层、执行层），而非纯插件方式，以支持新语法关键字 `FUSEJOIN`/`SIMILARITYJOIN`。

5. **物化嵌套循环连接**：当前连接实现为内存物化的嵌套循环，对大数据集存在扩展性挑战（已有 `ScalabilityBenchmark` 验证此瓶颈）。

---

## 14. 已知局限与未来工作方向

| 局限 | 说明 |
|---|---|
| 嵌套循环连接 | $O(N \times M)$ 复杂度，无索引加速 |
| 内存物化 | 大数据集可能 OOM |
| 单线程 JSD | 连接中每对 JSD 串行计算，无并行 |
| GMM 组件爆炸 | Fuse 后 $K_1 \times K_2$ 个分量，反复融合后快速膨胀 |
| 无近似查询评估 | 连接必须扫描全部候选对（V4/V5 只过滤 JSD 计算，不跳过候选对生成） |
