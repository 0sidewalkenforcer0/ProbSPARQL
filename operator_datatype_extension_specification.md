# Operator Datatype Extension Specification

## 背景

当前 ProbSPARQL 支持三类概率 literal：

- GMM: `uq:gmmLiteral`
- Histogram: `uq:histLiteral`
- Dirichlet: `uq:dirichletLiteral`

Supervisor 提出的目标是让 operator 尽可能支持三种 datatype。这个目标需要分层理解：有些 operator 是自然多态的，例如 `prob:jsd`, `prob:mean`, `prob:sample`；有些 operator 是历史遗留或类型专用接口，例如 `prob:jsdivergence`, `prob:jsdMode`, `prob:histcdf`；还有一些 operator 在数学语义上不适合直接拓展到所有 datatype，例如 `prob:fuse`, `prob:convolve`, `prob:modeCount`。

因此建议不要机械地把所有 operator 都改成三列全支持，而是把接口分成：

- 公共多态接口：用户应该优先使用，尽量支持 GMM / Histogram / Dirichlet。
- 类型专用 alias 或 legacy 接口：保留兼容性，不强行拓展。
- 语义需要重新定义的接口：先写清数学语义，再决定是否实现。

## 当前实现状态

### 已经是三类型多态的接口

这些接口当前已经支持 GMM / Histogram / Dirichlet：

| Operator | 当前状态 | 备注 |
|---|---|---|
| `prob:jsd` | 已支持三类及 cross-type | Same-type 有专门路径；cross-type 走 `Sampleable` fallback；要求维度兼容 |
| `prob:cdf` | 已支持三类 | GMM 和 Histogram 是分布 CDF；Dirichlet 当前是第 0 维 marginal CDF，不是 joint CDF |
| `prob:mean` | 已支持三类 | 返回 JSON vector string |
| `prob:std` | 已支持三类 | 返回 JSON vector string |
| `prob:map` | 已支持三类 | Dirichlet 若 `alpha <= 1`，fallback 到 mean |
| `prob:sample` | 已支持三类 | 三个 value class 都实现 `Sampleable` |
| `prob:sameTerm` | 已支持任意 RDF term | 不是 distribution-specific |
| `prob:sameDistribution` | 已支持任意 RDF term/value | 对 distribution literal 依赖 parsed value equality |

这些接口是当前最接近“polymorphic probabilistic operator layer”的部分，应该作为未来用户文档和论文描述的主接口。

### 当前仍是 GMM-only 的接口

| Operator | 当前状态 | 是否建议拓展 |
|---|---|---|
| `prob:pdf` | GMM-only | 建议拓展到 Histogram / Dirichlet |
| `prob:logpdf` | GMM-only | 建议拓展到 Histogram / Dirichlet |
| `prob:logcdf` | GMM-only | 可以拓展到 Histogram / Dirichlet，复用 `prob:cdf` 后取 log |
| `prob:kldivergence` | GMM-only | 可拓展，但要谨慎定义 KL 语义和 support mismatch |
| `prob:scale` | GMM-only | 可拓展到 Histogram；Dirichlet 不建议直接拓展 |
| `prob:shift` | GMM-only | 可拓展到 Histogram；Dirichlet 不建议直接拓展 |
| `prob:linearTransform` | GMM-only | 可拓展到 Histogram；Dirichlet 不建议直接拓展 |
| `prob:marginal` | GMM-only | 可拓展到 Histogram；Dirichlet 需要新返回类型或约定 |
| `prob:joint` | GMM × GMM | 可拓展到 Histogram × Histogram；Dirichlet 不建议直接拓展 |
| `prob:convolve` | GMM × GMM | 可拓展到 1D/ND Histogram；Dirichlet 不建议拓展 |
| `prob:multiply` | GMM × GMM | 可拓展到 same-grid Histogram；Dirichlet 需要重新定义 |
| `prob:mix` | GMM × GMM + weight | 可拓展到 same-grid Histogram；Dirichlet mixture 通常不再是 Dirichlet |
| `prob:fuse` | GMM × GMM Gaussian product | 不建议作为三类型公共接口强行拓展 |
| `prob:modeCount` | GMM component count | 不建议拓展；Histogram/Dirichlet 的“mode count”不是同一语义 |
| `prob:quantile` | 1D GMM-only | 可拓展为“univariate quantile”；不应承诺多维 quantile |

### 不建议拓展的 legacy/type-specific 接口

| Operator | 建议 |
|---|---|
| `prob:jsdivergence` | 保持 GMM-only legacy。它本质是 similarity evaluator wrapper，V3/V4/V5 的返回值是 similarity decision pipeline 的副产物，不应该变成新的多态 JSD 主接口。多态数值 JSD 应使用 `prob:jsd`。 |
| `prob:jsdMode` | 保持 GMM-only benchmark function。它是为了实验中切换 V1-V5 / `V3_SPRT` / ground truth modes，不是面向三类 datatype 的公共 API。 |
| `prob:histcdf` | 保持 Histogram-only alias。公共多态 CDF 应使用 `prob:cdf`。 |
| `prob:histjsd` | 保持 Histogram-only alias。公共多态 JSD 应使用 `prob:jsd`。 |
| `prob:histmean` | 保持 Histogram-only alias。公共多态 mean 应使用 `prob:mean`。 |
| `prob:lastDivJoinStats` | 保持 N/A。它是 benchmark instrumentation，不吃 distribution literal。 |

## 推荐目标状态

### Tier 1: 应该优先多态化的公共接口

这些接口最适合作为三类 datatype 的共同 API：

| Operator | GMM | Histogram | Dirichlet | 实现建议 |
|---|---|---|---|---|
| `prob:pdf` | 已有 | 新增 | 新增 | 对所有 `Sampleable` 调 `logPdf(x)` 后 `exp` |
| `prob:logpdf` | 已有 | 新增 | 新增 | 对所有 `Sampleable` 直接调 `logPdf(x)` |
| `prob:cdf` | 已有 | 已有 | 已有 | 保持；但 Dirichlet 说明为 marginal CDF |
| `prob:logcdf` | 已有 | 新增 | 新增 | 复用 `prob:cdf` 结果后取 `log`；处理 0 为 `-Inf` |
| `prob:jsd` | 已有 | 已有 | 已有 | 保持主接口；继续支持 cross-type fallback |
| `prob:mean` | 已有 | 已有 | 已有 | 保持 |
| `prob:std` | 已有 | 已有 | 已有 | 保持 |
| `prob:map` | 已有 | 已有 | 已有 | 保持 |
| `prob:sample` | 已有 | 已有 | 已有 | 保持 |
| `prob:sameTerm` | 已有 | 已有 | 已有 | 保持 generic RDF term equality |
| `prob:sameDistribution` | 已有 | 已有 | 已有 | 保持 parsed value equality |

这些改动可以增强“polymorphic probabilistic datatype layer”的可信度，同时不会引入太多新语义。

### Tier 2: 可拓展，但需要明确数学语义

这些接口可以拓展，但需要先写清楚返回类型和数学含义：

| Operator | 建议语义 | 实现难度 | 风险 |
|---|---|---|---|
| `prob:kldivergence` | Same-type: Histogram exact KL, Dirichlet MC KL 或解析 KL；cross-type sample-based KL | 中 | KL 非对称，support mismatch 可能为 `Infinity` |
| `prob:scale` | GMM: affine; Histogram: transform bin edges; Dirichlet: 不支持 | 低-中 | Histogram 权重不变但 edges 改变，density 解释要清楚 |
| `prob:shift` | GMM: affine; Histogram: shift edges; Dirichlet: 不支持 | 低 | Dirichlet 定义在 simplex，普通 shift 会离开 simplex |
| `prob:linearTransform` | GMM: affine; Histogram: affine transform edges; Dirichlet: 不支持 | 中 | 负 scale 会改变 bin order，需要规范化 edges |
| `prob:marginal` | GMM: 已有；Histogram: 对其他维求和；Dirichlet: 返回 Beta marginal 或 JSON approximation | 中 | 当前没有 `BetaValue` datatype |
| `prob:joint` | GMM × GMM: 已有；Histogram × Histogram: independent product grid | 中 | Dirichlet independent joint 不再是 Dirichlet |
| `prob:convolve` | GMM: 已有；Histogram: discrete convolution | 中-高 | 多维 grid 对齐和边界处理复杂 |
| `prob:multiply` | GMM: 已有；Histogram: same-grid pointwise product + normalize | 中 | 不同 grid 需要重采样或拒绝 |
| `prob:mix` | GMM: 已有；Histogram: same-grid weighted average | 低-中 | Dirichlet mixture 不闭包，不能返回 Dirichlet |
| `prob:quantile` | Univariate only: GMM 1D, Histogram 1D, Dirichlet marginal | 中 | 多维 quantile 没有唯一标准定义 |

这些可以作为第二阶段做，但不建议一次性全做。

### Tier 3: 不建议拓展，保留专用/legacy 语义

| Operator | 原因 |
|---|---|
| `prob:jsdivergence` | 名字像数值 JSD，但实现语义是 legacy GMM similarity evaluator；拓展会和 `prob:jsd` 冲突 |
| `prob:jsdMode` | 实验函数，绑定 V1-V5 GMM evaluator；Histogram/Dirichlet 没有对应 V1-V5 策略 |
| `prob:histcdf` | 类型专用 alias，应该由 `prob:cdf` 承担多态职责 |
| `prob:histjsd` | 类型专用 alias，应该由 `prob:jsd` 承担多态职责 |
| `prob:histmean` | 类型专用 alias，应该由 `prob:mean` 承担多态职责 |
| `prob:modeCount` | GMM 的 component count 和 Histogram/Dirichlet 的 mode 数不是同一概念 |
| `prob:fuse` | 当前是 GMM Gaussian product / Bayesian fusion；Histogram 和 Dirichlet 的 fusion 需要应用语义，不适合作为无条件三类型 operator |
| `prob:lastDivJoinStats` | Benchmark stats，不是 distribution operator |

## 推荐实现方案

### Phase 1: 低风险多态补齐

目标：让最自然的概率查询接口三类型一致。

建议实现：

1. 重构 `prob:pdf` 为 polymorphic：
   - 若 literal value 实现 `Sampleable`，解析 point vector 后返回 `exp(sampleable.logPdf(point))`。
   - GMM 可以继续复用现有精确实现，也可以统一走 `GMMValue.logPdf`。
   - Histogram 使用 cell mass / volume density。
   - Dirichlet 使用 simplex density；point 必须是 JSON array，维度匹配。

2. 重构 `prob:logpdf` 为 polymorphic：
   - 直接走 `Sampleable.logPdf(point)`。
   - 统一 point parsing：1D 可接受 number 或 `"[x]"`；多维必须 JSON array。

3. 重构 `prob:logcdf` 为 polymorphic：
   - 复用 `prob:cdf` 的 dispatch 逻辑。
   - `cdf == 0` 返回 `Double.NEGATIVE_INFINITY`。
   - Dirichlet 仍然是 dim 0 marginal CDF，文档写清楚。

4. 保持 `prob:jsd`, `prob:mean`, `prob:std`, `prob:map`, `prob:sample` 现状。

这一步最适合先做，因为它不会改变 legacy evaluator，也不会引入新的返回 datatype。

### Phase 2: Histogram transformation support

目标：把实数空间上的 transformation 从 GMM 拓展到 Histogram。

建议实现：

1. `scale`, `shift`, `linearTransform`：
   - 对 Histogram 的 edges 做 affine transform。
   - weights 保持不变。
   - 如果 scale 为负，需要反转对应维度 edges，并同步重排 weights。

2. `marginal`：
   - 对 Histogram 多维 grid 沿非目标维度求和。
   - 返回 1D Histogram。

3. `joint`：
   - GMM × GMM 保持现有。
   - Histogram × Histogram 返回 independent joint Histogram，edges 拼接，weights 做 outer product。

4. `mix`：
   - Same-grid Histogram 做 weighted average。
   - 不同 grid 第一版直接拒绝，不做自动 rebin。

这些改动语义比较清楚，但实现需要小心 multidimensional histogram 的 row-major index 和 weight 重排。

### Phase 3: Divergence / product 类增强

目标：增强比较和组合能力，但要先接受更复杂的数学边界。

建议实现：

1. `prob:kldivergence`：
   - GMM 保持现有。
   - Histogram same-grid exact KL。
   - Dirichlet 可以实现解析 KL，因为 Dirichlet-Dirichlet KL 有闭式公式。
   - Cross-type KL 可选 sample-based，但 support mismatch 可能导致 `Infinity`，需要文档说明。

2. `prob:multiply`：
   - GMM 保持现有。
   - Histogram same-grid pointwise multiply + normalize。
   - Dirichlet 不建议作为 Dirichlet × Dirichlet 返回 Dirichlet，除非定义为某种 approximation。

3. `prob:convolve`：
   - Histogram 可做 discrete convolution。
   - 多维 convolution 和 grid spacing 对齐比较复杂，建议先只做 1D Histogram。

4. `prob:quantile`：
   - Histogram 1D 可实现 inverse CDF。
   - Dirichlet 可实现 marginal quantile，但当前没有 Beta datatype；可以返回 numeric。
   - 多维 quantile 不建议支持。

## 不建议做的事情

不建议把所有 operator 的三列都硬改成 ✅。原因：

- 有些名字本来就是类型专用：`histcdf`, `histjsd`, `histmean`。
- 有些是实验/legacy 接口：`jsdivergence`, `jsdMode`, `lastDivJoinStats`。
- 有些数学上不闭包：Dirichlet 做普通 shift/scale 后不再是 Dirichlet；Dirichlet mixture 通常不再是 Dirichlet；independent joint of two Dirichlet 不等于一个 Dirichlet。
- 有些概念不是同一个：GMM `modeCount` 是 component count，不等于 Histogram local maxima count，也不等于 Dirichlet mode structure。

更合理的说法是：

> ProbSPARQL provides a polymorphic core API for distribution evaluation, summary statistics, sampling, and numerical divergence (`prob:cdf`, `prob:pdf/logpdf` after extension, `prob:jsd`, `prob:mean/std/map`, `prob:sample`). Type-specific and legacy operators remain available for compatibility or benchmark control, but are not forced into the polymorphic API.

## 建议给 supervisor 的结论

可以承诺拓展，但应分阶段：

1. 第一阶段：补齐 `pdf/logpdf/logcdf` 到三类型，这最符合 polymorphic layer 的目标。
2. 第二阶段：把实数空间 transformation 拓展到 Histogram，包括 `scale/shift/linearTransform/marginal/joint/mix`。
3. 第三阶段：谨慎拓展 `kldivergence/multiply/convolve/quantile`，每个都要写清返回类型和数学定义。
4. 明确不拓展 `jsdivergence/jsdMode/hist*/lastDivJoinStats`，因为它们是 legacy、benchmark 或类型专用接口；公共多态入口应该分别是 `prob:jsd`, `prob:cdf`, `prob:mean`。
