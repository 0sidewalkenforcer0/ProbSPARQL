# ProbSPARQL Benchmark — 实验计划 vs 实际实现 一致性分析报告

**分析日期：** 2026-03-20  
**基准文件：** `benchmark/experiment_plan.md`

---

## 一、总览

| 实验 | 计划优先级 | 实现状态 |
|------|-----------|---------|
| Exp 1: 系统开销（System Overhead） | High | ⚠️ 部分实现，方法论存在偏差 |
| Exp 2: 引擎内 vs 外部处理 | Medium | ❌ 完全缺失 |
| Exp 3.1: SimJoin 分类准确率 | High | ⚠️ 已实现，但关键参数与计划不符 |
| Exp 3.2: 收敛分析 | High | ✅ 基本一致 |
| Exp 3.3: 选择性灵敏度 | Medium | ✅ θ 值一致，数据集模型有差异 |
| Exp 4: 直方图泛化 | High | ❌ 完全缺失 |

---

## 二、一致部分

| 方面 | 计划 | 实际实现 |
|------|------|---------|
| Exp 3.2 样本数序列 | 100, 500, 1K, 5K, 10K, 50K, 100K | `{100, 500, 1_000, 5_000, 10_000, 50_000, 100_000}` ✓ |
| Exp 3.2 重复次数 | 50 次/配置 | `REPETITIONS = 50` ✓ |
| Exp 3.3 θ 值集合 | 0.01, 0.05, 0.1, 0.2, 0.3, 0.5 | `{0.01, 0.05, 0.10, 0.20, 0.30, 0.50}` ✓ |
| 数据集领域 | 角磨机 + GMM 编码 | 一致（`ag:`, `cfm:`, `uq:` 命名空间） ✓ |
| 输出格式 | CSV + 图表 | CSV + matplotlib 图表 ✓ |
| Exp 3.3 输出指标 | 时间、结果集大小、准确率 | `Median_ms`, `ResultCount`, `Accuracy`, `F1` ✓ |

---

## 三、不一致部分（详细）

### 3.1 采样方法数量：4 个 → 5 个

- **计划**：M1 (Naive MC)、M2 (Importance Sampling)、M3 (Stratified)、M4 (TBD)
- **实现**：V1_MC、V2_STRATIFIED、V3_SPRT、V4_BOUNDS、**V5_ADAPTIVE**（多出一个未在计划中定义的 Adaptive 方法）
- 分析脚本将其标为 M5；计划中 M4 标注为 "TBD"，实现中拆分成了 V4_BOUNDS + V5_ADAPTIVE 两个方法
- **影响**：论文中方法数量与描述对不上

---

### 3.2 分类阈值 θ 不一致（关键）

| | 计划 | 实现 |
|--|------|------|
| Exp 3.1 默认 θ | **0.2** | `THETA = 0.3`（[ClassificationAccuracyBenchmark.java](../src/main/java/org/apache/jena/probsparql/ClassificationAccuracyBenchmark.java)） |
| 数据生成 θ | 0.2（Dataset B 说明） | 0.3（[generate_sim_join_data.py](scripts/generate_sim_join_data.py)） |
| Exp 3.3 θ 范围解读 | 围绕 θ=0.2 变化 | 围绕 θ=0.3 变化 |

计划中 Dataset B 与 Exp 3.3 均明确以 θ=0.2 为参考点，但实现统一改用了 0.3，导致数据集标签定义（"hard" 的意义）也随之改变。

---

### 3.3 Dataset B（Exp 3.1）JSD 范围与样本对数量不一致

| 难度 | 计划 JSD 范围 | 计划对数 | 实现 JSD 范围 | 实现对数（per dataset） |
|------|-------------|---------|-------------|----------------------|
| Easy-similar | [0.01, 0.05] | 200 | [0.0, 0.1) ∪ (0.5, 0.693] | ~50（与 easy-dissimilar 合并） |
| Easy-dissimilar | [0.4, 0.6] | 200 | 同上 | 同上 |
| Medium | [0.10,0.15]∪[0.25,0.30] | 200 | [0.10,0.25)∪(0.35,0.5] | ~100 |
| Hard | [0.18, 0.22] | 200 | [0.25, 0.35] | ~100 |
| Mixed | 无 | — | 1/3 easy + 1/3 medium + 1/3 hard | ~100（新增） |

- 计划总计 **800 对**；实现用 `--n 100`（100对/类别），但 SelectivityBenchmark 以笛卡尔积方式运行（100左实体 × 100右实体 = 10,000 对/查询），`PairsTotal=10000`
- 计划中 "hard" 要求 JSD ∈ [0.18, 0.22]（极近 θ=0.2 的边界案例），实现中 "hard" 为 [0.25, 0.35]（θ=0.3 的边界）

---

### 3.4 Dataset B 文件格式不一致

- **计划**：JSON 文件，`pair_id → {GMM₁, GMM₂, true_JS, difficulty_label}`
- **实现**：Turtle `.ttl` 文件（`simjoin_easy.ttl`, `simjoin_medium.ttl`, `simjoin_hard.ttl`, `simjoin_mixed.ttl`）

计划使用 JSON 是为了直接被 Python 脚本读取并与 SPARQL 分离；实现改用 TTL 以便直接载入 Jena 模型，逻辑更合理但偏离了设计文档。

---

### 3.5 Exp 3.1 重复次数不足

- **计划**：每个配置重复 **10 次**以捕获方差
- **实现**：[ClassificationAccuracyBenchmark.java](../src/main/java/org/apache/jena/probsparql/ClassificationAccuracyBenchmark.java) `REPEAT = 3`（仅 3 次）

---

### 3.6 Exp 1（系统开销）实现方法论偏差

**计划设计：**
- 在 Jena Fuseki 上加载 Dataset A 的不同规模（1K–100K triple）
- 对比查询对 Q1a（CDF FILTER）/Q1b（Product+Mean）/Q1c（字面量解析）
- 分别测 GMM 复杂度 K ∈ {1, 3, 5, 10}
- 每个配置 30 次，取中位数和 IQR

**实际实现：**
- [analyze_exp1_scalability.py](scripts/analyze_exp1_scalability.py) 直接读取 `simjoin_results.csv` 与 `simjoin_accuracy_latency.csv`，用 `GT_10K`（10K 样本 MC）作为"确定性基线代理"，**不是真正的 deterministic SPARQL 基线**
- [ScalabilityBenchmark.java](../src/main/java/org/apache/jena/probsparql/ScalabilityBenchmark.java) 存在但：
  - 仅做 5 次（`BENCHMARK_RUNS = 5`，非计划中的 30 次）
  - 查询为通用 U1-CDF/U2-Stats/U3-PDF，与计划的 Q1a/Q1b/Q1c 命名和内容不对应
  - **未集成到** `run_all_experiments.sh` 中
- 生成的图表（`exp1_latency_bars.png`, `exp1_overhead_ratio.png`）实为 SimJoin 方法间的延迟对比，而非 ProbSPARQL vs 标准 SPARQL 的开销分析

---

### 3.7 Exp 2（引擎内 vs 外部处理）—— 完全缺失

- **计划**：Medium 优先级，完整设计包括:
  - Approach A：ProbSPARQL 引擎内 JSD 过滤
  - Approach B：SPARQL 导出 + Python 后处理
  - 在 100/500/1K/5K/10K 对和 10%/50%/90% 选择率条件下对比端到端时间
- **实现**：`run_all_experiments.sh` 中无 Exp 2 步骤；无 `exp2_in_engine_vs_external.sh`；无 `exp2_external_baseline.py`
- **影响**：论文 §6.2.2 缺乏数据支撑

---

### 3.8 Exp 4（直方图泛化）—— 完全缺失

- **计划**：High 优先级
  - Dataset D：将 GMM 数据转换为直方图字面量（B ∈ {20, 50, 100} bins）
  - Sub-exp 4.1：直方图 vs GMM vs 确定性基线的开销对比
  - Sub-exp 4.2：直方图下 SimJoin 准确率对比
- **实现**：
  - 无 `generate_histogram_variants.py`
  - 无直方图 TTL 文件
  - 无 `analyze_exp4.py`
  - 无相关结果 CSV
- **影响**：论文 §6.3（框架泛化性）完全无数据

---

### 3.9 检查清单脚本命名与分工不符

| 计划要求的脚本 | 实际对应脚本 | 差异 |
|--------------|------------|------|
| `generate_rdf_kg.py` | [generate_dataset.py](scripts/generate_dataset.py) | 命名不同，功能相似 |
| `generate_rdf_kg_deterministic.py` | 无 | ❌ 缺失 |
| `generate_controlled_pairs.py` | [generate_sim_join_data.py](scripts/generate_sim_join_data.py) | 命名不同，功能相似 |
| `generate_convergence_pair.py` | 无（内嵌于 Java benchmark） | ❌ 缺失独立脚本 |
| `generate_histogram_variants.py` | 无 | ❌ 缺失 |
| `exp1_overhead.sh` | 无（ScalabilityBenchmark 未集成） | ❌ 缺失 |
| `exp2_in_engine_vs_external.sh` | 无 | ❌ 缺失 |
| `exp2_external_baseline.py` | 无 | ❌ 缺失 |
| `exp3_1_accuracy.py` | [ClassificationAccuracyBenchmark.java](../src/main/java/org/apache/jena/probsparql/ClassificationAccuracyBenchmark.java) | 语言改为 Java |
| `exp3_2_convergence.py` | [MultiMethodConvergenceBenchmark.java](../src/main/java/org/apache/jena/probsparql/MultiMethodConvergenceBenchmark.java) | 语言改为 Java |
| `exp3_3_selectivity.py` | [SelectivityBenchmark.java](../src/main/java/org/apache/jena/probsparql/SelectivityBenchmark.java) | 语言改为 Java |
| `exp4_histogram.sh` | 无 | ❌ 缺失 |
| `analyze_exp1.py` | [analyze_exp1_scalability.py](scripts/analyze_exp1_scalability.py) | 分析数据源偏差（见 3.6） |
| `analyze_exp2.py` | 无 | ❌ 缺失 |
| `analyze_exp4.py` | 无 | ❌ 缺失 |

---

## 四、需要修复的优先项

### 高优先级（影响核心论证）

1. **统一 θ 值**：将 `ClassificationAccuracyBenchmark.java` 中 `THETA` 和 `generate_sim_join_data.py` 中的 JSD ranges 统一为同一个值（0.2 或 0.3），并更新 `experiment_plan.md`
2. **实现 Exp 2**：补充引擎内 vs 外部处理对比，这是论文 §6.2.2 的核心支撑
3. **实现 Exp 4**：补充直方图数据生成和分析，这是论文 §6.3 泛化性的唯一证据
4. **修复 Exp 1**：让 `ScalabilityBenchmark.java` 真正对比 ProbSPARQL 查询 vs 等价 deterministic SPARQL 查询，并集成到 `run_all_experiments.sh`

### 中优先级（影响结果可信度）

5. **Exp 3.1 重复次数**：将 `REPEAT` 从 3 提高到 10（计划要求）
6. **Dataset B 对数量**：计划要求 800 对（200×4），当前约 400 对，影响统计功效
7. **确认 Dataset B JSD 范围**：根据最终选定的 θ 值重新对齐"hard"对的 JSD 区间

### 低优先级（文档一致性）

8. **同步 experiment_plan.md**：将 θ=0.3、5种方法（V1-V5）、TTL 格式等实际决策反映到计划文档中，或反向修改代码使其匹配原计划
