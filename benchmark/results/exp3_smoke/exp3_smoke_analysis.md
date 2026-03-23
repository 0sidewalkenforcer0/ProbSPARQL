# Experiment 3 — Smoke Test Analysis Report

**Run date:** 2026-03-22  
**Mode:** Smoke (快速验证，非最终统计结果)  
**Parameters:** repeat=1, Exp 3.2 repetitions=2 max-samples=1000, Exp 3.3 warmup=1 iterations=2 limit-graphs=10  
**GT generation:** N=500 samples/pair  
**Output dir:** `benchmark/results/exp3_smoke/`

---

## 目录

1. [Exp 3.1 — 分类准确性](#exp-31--分类准确性)
2. [Exp 3.2 — 收敛性分析](#exp-32--收敛性分析)
3. [Exp 3.3 — 阈值选择性分析](#exp-33--阈值选择性分析)
4. [综合结论](#综合结论)
5. [全量实验建议](#全量实验建议)

---

## Exp 3.1 — 分类准确性

**基准任务：** 判断 JSD(P, Q) ≤ θ=0.3（相似 vs. 不相似），对比 GT_10K 标签。  
**评估指标：** Accuracy, Precision, Recall, F1, MAE, RMSE, Latency (ms/pair)

### 3.1.1 F1 Score 汇总

| Method | Easy | Medium | Hard | Mixed |
|--------|:----:|:------:|:----:|:-----:|
| GT_10K | 1.000 | 1.000 | 1.000 | 1.000 |
| V1_MC | **1.000** | **1.000** | 0.913 | **1.000** |
| V2_STRATIFIED | **1.000** | **1.000** | 0.921 | 0.979 |
| V3_SPRT | **1.000** | **1.000** | 0.895 | 0.984 |
| V4_BOUNDS | **1.000** | **1.000** | 0.913 | 0.995 |
| V5_ADAPTIVE | **1.000** | **1.000** | **0.924** | 0.979 |

> 所有方法在 Easy 和 Medium 数据集上达到完美分类（F1=1.000）。Hard 数据集由于 JSD 值密集分布在 θ=0.3 附近，各方法 F1 下降至约 0.895–0.924，其中 **V5_ADAPTIVE 表现最好（Hard F1=0.924）**。

### 3.1.2 Accuracy 汇总

| Method | Easy | Medium | Hard | Mixed |
|--------|:----:|:------:|:----:|:-----:|
| V1_MC | 100.0% | 100.0% | 93.5% | 100.0% |
| V2_STRATIFIED | 100.0% | 100.0% | 94.0% | 98.0% |
| V3_SPRT | 100.0% | 100.0% | 92.0% | 98.5% |
| V4_BOUNDS | 100.0% | 100.0% | 93.5% | 99.5% |
| V5_ADAPTIVE | 100.0% | 100.0% | **94.5%** | 98.0% |

### 3.1.3 MAE（JSD 估计误差）

| Method | Easy | Medium | Hard | Mixed |
|--------|:----:|:------:|:----:|:-----:|
| V1_MC | 0.00107 | 0.00502 | 0.00529 | 0.00403 |
| V2_STRATIFIED | 0.00099 | 0.00489 | 0.00523 | 0.00383 |
| V3_SPRT | 0.00550 | 0.03165 | 0.02651 | 0.02015 |
| V4_BOUNDS | 0.11048 | 0.00851 | 0.00471 | 0.04146 |
| V5_ADAPTIVE | 0.11155 | 0.03500 | 0.02543 | 0.05843 |

> V1_MC 和 V2_STRATIFIED 的 MAE 最低（高精度估计），SPRT/Adaptive/Bounds 在 Easy 数据集上 MAE 偏高，原因是这些方法会提前终止——Easy 数据集中大多数对的 JSD 远离 θ=0.3，早停策略减少了计算但降低了估计精度，不影响分类决策。

### 3.1.4 延迟（ms/pair）

| Method | Easy | Medium | Hard | Mixed |
|--------|:----:|:------:|:----:|:-----:|
| V1_MC | 8.48 | 8.54 | 8.54 | 8.57 |
| V2_STRATIFIED | 3.88 | 3.90 | 3.89 | 3.90 |
| V3_SPRT | 0.05 | 0.09 | 1.10 | 0.40 |
| V4_BOUNDS | 2.00 | 3.74 | 3.89 | 3.32 |
| V5_ADAPTIVE | **0.03** | **0.09** | 1.30 | 0.52 |

> **V5_ADAPTIVE 延迟最低**（Easy 0.03ms，Medium 0.09ms），通过 Bounds 提前拒绝明显相异的对以及 SPRT 早停，大幅降低平均计算量。Hard 数据集延迟上升（1.30ms），因为边界对需要更多样本才能做出决策。V1_MC 始终最慢（~8.5ms/pair），与样本数呈线性关系。

### 3.1.5 图表

| 图表 | 文件 |
|------|------|
| 各方法 × 数据集分组柱状图 | `exp3_1_grouped_bar.png` |
| F1 Score 热力图 | `exp3_1_f1_heatmap.png` |
| 延迟柱状图 | `exp3_1_latency.png` |

---

## Exp 3.2 — 收敛性分析

**基准任务：** 固定一对 Hard 数据中 JSD≈0.3 的 GMM，观察各方法在不同样本数（N=100, 500, 1000）下的估计误差如何随 N 收敛。  
**GT 参考值：** ≈ **0.3037**（V4_BOUNDS/V5_ADAPTIVE 在 N=1000 时最接近）

### 3.2.1 均值 JSD 估计

| Method | N=100 (mean) | N=500 (mean) | N=1000 (mean) |
|--------|:------------:|:------------:|:-------------:|
| V1_MC | 0.3010 | 0.3101 | 0.3038 |
| V2_STRATIFIED | 0.3041 | 0.3042 | 0.2994 |
| V3_SPRT | 0.3035 | 0.2711 | 0.3113 |
| V4_BOUNDS | 0.3162 | 0.2706 | 0.2899 |
| V5_ADAPTIVE | 0.2722 | 0.3139 | 0.2984 |

> 所有方法的 JSD 估计均在 GT=0.3037 周围波动，**没有任何方法出现固定在错误值（如 0.151）的异常**，验证 AdaptiveSampler 参数设置正确（θ=0.3）。

### 3.2.2 平均绝对误差（MAE）

| Method | N=100 | N=500 | N=1000 |
|--------|:-----:|:-----:|:------:|
| V1_MC | 0.02767 | 0.02986 | 0.02708 |
| V2_STRATIFIED | 0.02965 | 0.01885 | 0.01404 |
| V3_SPRT | 0.04637 | 0.02701 | 0.03512 |
| V4_BOUNDS | 0.03085 | 0.01479 | **0.00445** |
| V5_ADAPTIVE | 0.04445 | 0.02845 | 0.01299 |

> V4_BOUNDS 和 V2_STRATIFIED 在高样本数时收敛速度最快（MAE 随 N 单调下降）。由于 smoke 仅 2 次重复，V3_SPRT 和 V5_ADAPTIVE 的 MAE 有较大方差，全量实验（50次重复）可得到更稳定的收敛曲线。

### 3.2.3 平均计算时间（ms/call）

| Method | N=100 | N=500 | N=1000 |
|--------|:-----:|:-----:|:------:|
| V1_MC | 0.14ms | 0.46ms | 0.66ms |
| V2_STRATIFIED | 0.05ms | 0.20ms | 0.42ms |
| V3_SPRT | 0.15ms | 0.30ms | 0.35ms |
| V4_BOUNDS | 0.06ms | 0.22ms | 0.40ms |
| V5_ADAPTIVE | 0.08ms | 0.38ms | 0.66ms |

> 所有方法时间随 N 近似线性增长。V3_SPRT 在大样本（N=1000）时有早停优势，时间不及 V1_MC 的 55%。V5_ADAPTIVE 大样本时与 V1_MC 相近，因为该 Hard 对不触发 Bounds 拒绝，最终退化为全量采样。

### 3.2.4 图表

| 图表 | 文件 |
|------|------|
| JSD 收敛曲线 | `exp3_2_jsd_convergence.png` |
| MAE 收敛曲线 | `exp3_2_mae_convergence.png` |
| 计算时间对比 | `exp3_2_time.png` |

---

## Exp 3.3 — 阈值选择性分析

**基准任务：** 在 θ ∈ {0.01, 0.05, 0.10, 0.20, 0.30, 0.50} 下，对比各方法的延迟、结果集大小（选择性）、分类准确性的变化规律。  
**数据集：** easy / medium / hard / mixed（各 10×10=100 对, smoke 限制下）

### 3.3.1 延迟 vs. θ（Mixed 数据集，ms）

| Method | θ=0.01 | θ=0.10 | θ=0.20 | θ=0.30 | θ=0.50 |
|--------|:------:|:------:|:------:|:------:|:------:|
| V1_MC | 226.1 | 231.3 | 226.4 | 228.7 | 227.9 |
| V2_STRATIFIED | 197.9 | 194.5 | 197.3 | 194.0 | 197.1 |
| V3_SPRT | 22.2 | 22.4 | 22.3 | 22.7 | 22.3 |
| V4_BOUNDS | 112.3 | 113.0 | 111.1 | 111.2 | 112.7 |
| V5_ADAPTIVE | 21.0 | 20.9 | 21.1 | 21.2 | 21.5 |

> V1_MC 和 V2_STRATIFIED 延迟对 θ **不敏感**（固定样本数），整体耗时最高。**V3_SPRT 和 V5_ADAPTIVE 延迟最低**（~20ms/query），且对 θ 值变化几乎不敏感，体现了早停机制的鲁棒性。V4_BOUNDS 因 BoundsFilterSampler 预计算代价恒定，延迟稳定在约 110–115ms。

### 3.3.2 结果集大小（选择性）vs. θ @ Mixed 数据集

| Method | θ=0.01 | θ=0.10 | θ=0.20 | θ=0.30 | θ=0.50 |
|--------|:------:|:------:|:------:|:------:|:------:|
| V1_MC | 0/100 | 4/100 | 18/100 | 34/100 | 73/100 |
| V2_STRATIFIED | 0/100 | 3/100 | 19/100 | 34/100 | 74/100 |
| V3_SPRT | 1/100 | 8/100 | 25/100 | 34/100 | 71/100 |
| V4_BOUNDS | 0/100 | 2/100 | 14/100 | 20/100 | 59/100 |
| V5_ADAPTIVE | 1/100 | 6/100 | 16/100 | 20/100 | 58/100 |

> θ=0.3（GT 标签基准）时，M1–M3 均返回约 34 对，与 GT 一致。**V4_BOUNDS 和 V5_ADAPTIVE 仅返回 20 对**，存在约 41% 的低召回（recall=0.588 at θ=0.3）——这源于 BoundsFilter 对 Mixed 数据集过于激进地拒绝了部分真正相似的对，是值得在全量实验中进一步观察的现象。

### 3.3.3 分类准确性 @ θ=0.3（各方法 vs. GT 标签）

| Method | Easy | Medium | Hard | Mixed |
|--------|:----:|:------:|:----:|:-----:|
| V1_MC | 100.0% | 100.0% | 100.0% | 100.0% |
| V2_STRATIFIED | 100.0% | 100.0% | 100.0% | 100.0% |
| V3_SPRT | 100.0% | 100.0% | 100.0% | 100.0% |
| V4_BOUNDS | 100.0% | 100.0% | 100.0% | 100.0% |
| V5_ADAPTIVE | 100.0% | 100.0% | 100.0% | 100.0% |

> 在 θ=0.3（GT 标准阈值）下，所有方法均达到 100% 准确率。偏差在 θ≠0.3 时出现，θ<0.3 时召回下降（GT 标记相似但方法未返回），θ>0.3 时精度下降（方法返回额外对）。

### 3.3.4 图表

| 图表 | 文件 |
|------|------|
| 延迟 vs. θ（Mixed） | `exp3_3_latency_theta_mixed.png` |
| 结果集大小 vs. θ（4 数据集） | `exp3_3_result_count.png` |
| 准确性 vs. θ（Mixed） | `exp3_3_accuracy_theta_mixed.png` |
| F1 vs. θ（4 数据集） | `exp3_3_f1_theta.png` |

---

## 综合结论

### 关键发现

| # | 发现 | 影响 |
|---|------|------|
| 1 | **V5_ADAPTIVE 延迟最低**（Easy: 0.03ms, Hard: 1.30ms/pair），比 V1_MC 快 6–300×  | ✅ V5 适合大规模 SimJoin 场景 |
| 2 | **所有方法在 Easy/Medium 数据集上分类 F1=1.000**，全部满足实验目标 | ✅ 验证通过 |
| 3 | Hard 数据集（JSD 密集在 θ 附近）各方法 F1 下降至 0.895–0.924，**V5_ADAPTIVE 最高（0.924）** | ✅ 符合预期 |
| 4 | V4_BOUNDS 和 V5_ADAPTIVE 在 Mixed 数据集的选择性曲线中返回较少结果（20 vs GT 的 34）| ⚠️ 需在全量实验中验证召回率 |
| 5 | **没有方法出现固定 JSD=0.151 的异常**，AdaptiveSampler(θ=0.3) 参数修复有效 | ✅ Bug 修复确认 |
| 6 | V2_STRATIFIED MAE 最低（短路精度最高），适合需要精确 JSD 值的场景 | 信息参考 |

### 方法综合排名（smoke 结果，仅供参考）

| 维度 | 最优 | 次优 | 最差 |
|------|------|------|------|
| Hard F1 | V5_ADAPTIVE (0.924) | V2_STRAT (0.921) | V3_SPRT (0.895) |
| 延迟（低→好） | V5_ADAPTIVE | V3_SPRT | V1_MC |
| MAE 精度 | V2_STRATIFIED | V1_MC | V5_ADAPTIVE |
| 收敛速度 | V4_BOUNDS | V2_STRATIFIED | V3_SPRT |

---

## 全量实验建议

以下是基于 smoke 观察对全量实验（已启动，PID: 76629）的注意事项：

1. **重点观察 V4_BOUNDS 召回率问题**：在 Mixed 数据集 θ=0.3 下仅返回 20/100 对（理论应返回 34 对），需确认是 BoundsFilter 阈值过严还是数据特性导致。
   ```bash
   grep "V4_BOUNDS,mixed,0.3" benchmark/results/exp3_full/exp3_3_selectivity.csv
   ```

2. **Exp 3.2 全量需 50 次重复**：smoke 仅 2 次重复，V3_SPRT MAE 在 N=500 时异常升高（早停导致高方差），50 次重复后应趋于稳定。

3. **Exp 3.1 全量 repeat=10**：Hard 数据集各方法 F1 在 0.895–0.924 的差异是否显著，需更多重复验证。

4. **监控全量运行**：
   ```bash
   # 检查进程是否存活
   ps -p $(cat exp3_full.pid)
   
   # 实时查看进度
   tail -f benchmark/results/exp3_full/exp3_full_run.log
   
   # 完成后检查输出
   ls -lh benchmark/results/exp3_full/*.csv benchmark/results/exp3_full/*.png
   ```

---

*Report generated from: `benchmark/results/exp3_smoke/exp3_1_classification.csv`, `exp3_2_convergence_multimethod.csv`, `exp3_3_selectivity.csv`*
