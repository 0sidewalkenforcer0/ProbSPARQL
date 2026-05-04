# Histogram 多维扩展方案（简版）

## 1. 背景

当前项目中的 `Histogram` datatype 仅支持一维分布。

现状体现在：

- 词法格式只有一组 `bins` 和一组 `weights`
- `HistogramValue.sample()` 固定返回 `double[n][1]`
- `HistogramValue.logPdf()` 只读取 `x[0]`
- `prob:histcdf`、`prob:mean`、`prob:std`、`prob:map` 也都按一维标量语义实现

如果后续希望：

- `prob:jsd` 支持更一般的多维 histogram
- `SIMILARITYJOIN` 支持 `GMM / Histogram / Dirichlet` 三类 datatype

那么 histogram 需要升级为真正的多维联合分布表示。

---

## 2. 多维 Histogram 的语义

多维 histogram 可以理解为：

- 每一维都有一组 bin edges
- 所有维度的分箱做笛卡尔积，形成一个多维网格
- 每个网格单元（hyper-rectangle）存一个 probability mass
- 所有 mass 之和为 1

例如二维情况：

- 第 1 维 edges: `[0, 1, 2]`
- 第 2 维 edges: `[10, 20, 30]`

则网格共有 `2 x 2 = 4` 个 cell：

- `[0,1) x [10,20)`
- `[0,1) x [20,30)`
- `[1,2) x [10,20)`
- `[1,2) x [20,30)`

`weights` 表示随机变量落入这些 cell 的联合概率。

这与“一维 marginal histogram 的集合”不同。  
多维 histogram 表示的是 **联合分布**，因此可以表达维度之间的相关性。

---

## 3. 推荐的 JSON Schema

建议将 histogram 的词法格式统一为：

```json
{
  "dimensions": 2,
  "edges": [
    [0.0, 1.0, 2.0],
    [10.0, 20.0, 30.0, 40.0]
  ],
  "weights": [
    0.10, 0.15, 0.05,
    0.20, 0.25, 0.25
  ]
}
```

说明：

- `dimensions`：维数 `d`
- `edges[i]`：第 `i` 维的 bin boundaries
- `weights`：按固定顺序 flatten 存储的 cell probability masses

建议采用 **row-major** 顺序，即最后一维变化最快。

这样做的优点：

- 与 GMM 的 `dimensions` 字段风格一致
- 便于扩展到任意维数
- 与 `prob:jsd` / `SIMILARITYJOIN` 的内部实现衔接自然

---

## 4. 内部实现建议

建议将 `HistogramValue` 重构为以下逻辑结构：

- `int dimensions`
- `double[][] edges`
- `double[] weights`
- `int[] binCounts`
- `int[] strides`

核心能力：

- 根据 `weights` 对 cell 抽样
- 将扁平索引映射为多维 bin index
- 在每一维对应区间内均匀采样
- 对点 `x` 查找其所在 cell，并计算对应的 piecewise-constant density

也就是说，多维 histogram 的 `logPdf(x)` 仍保持现有语义：

- `logPdf(x) = log(mass / cell_volume)`

因此它与当前 1D histogram 的数学思路完全一致，只是区间宽度 `width` 变成了高维体积 `volume`。

---

## 5. 对现有功能的影响

### 5.1 必改部分

这些部分必须一起改：

- `HistogramDatatype`
- `HistogramValue`
- `HistogramJSD`
- `PolyJSD`

原因：

- datatype 需要能解析新的 JSON schema
- value 需要支持多维采样和密度
- same-type histogram JSD 需要支持多维网格兼容性检查
- polymorphic `prob:jsd` 需要正确走多维 histogram 路径

### 5.2 连带影响部分

这些函数当前默认 histogram 是 1D，后续需要重新定义语义：

- `prob:histcdf`
- `prob:cdf`
- `prob:mean`
- `prob:std`
- `prob:map`

建议语义：

- `mean`：返回多维向量
- `std`：返回各维标准差向量
- `map`：返回最大质量 cell 的中心向量
- `cdf`：若支持多维，则定义为联合 CDF `P(X1<=x1, ..., Xd<=xd)`；否则暂时限制为 1D

### 5.3 对 SIMILARITYJOIN 的影响

这次我们已经把 `SIMILARITYJOIN` 做成了多态分发：

- `GMM x GMM` 走现有 threshold-aware backend
- 其它支持类型走 `prob:jsd` 的多态路径

因此，一旦 histogram 本身支持多维：

- `SIMILARITYJOIN` 框架本身不需要大改
- 它会自然继承 histogram 的新能力

---

## 6. JSD 计算是否容易支持多维 Histogram

同类型 histogram 的 JSD 其实不难扩展。

只要两个 histogram：

- 维数相同
- 每一维的 edges 相同
- flatten 顺序一致

那么仍然可以把所有 cell 视为离散状态，直接对 `weights` 做离散 JSD：

```text
JSD(P,Q) = 1/2 KL(P||M) + 1/2 KL(Q||M)
```

所以多维 histogram 的 same-type `prob:jsd` 计算是比较直接的。

---

## 7. 主要风险

### 7.1 维度灾难

若每维有 `B` 个 bin、维度为 `d`，总 cell 数为：

```text
B^d
```

因此多维 histogram 只适合：

- 低维联合分布
- 粗粒度近似

不适合高维精细建模。

### 7.2 CDF 语义不如 1D 直观

一维 histogram 的 CDF 非常自然。  
多维 histogram 的 CDF 需要额外定义：

- 是联合 CDF
- 还是只支持某一维 marginal CDF

这部分需要和 supervisor 先对齐。

### 7.3 兼容性策略要先定

需要确认是否：

- 保留旧的 1D schema 兼容解析
- 或直接统一切到新 schema

若考虑历史数据，建议 parser 过渡期同时支持：

- 旧 schema：`bins + weights`
- 新 schema：`dimensions + edges + weights`

---

## 8. 推荐实施顺序

建议分两阶段做。

### Phase 1：核心多维能力

- 设计并确认 schema
- 重构 `HistogramDatatype`
- 重构 `HistogramValue`
- 升级 `HistogramJSD`
- 升级 `PolyJSD`
- 增加 datatype / JSD / `SIMILARITYJOIN` 测试

目标：

- 多维 histogram 能被正确解析、采样、计算 JSD，并参与 `SIMILARITYJOIN`

### Phase 2：统计函数与文档

- 升级 `prob:cdf`
- 升级 `prob:mean`
- 升级 `prob:std`
- 升级 `prob:map`
- 更新 benchmark 脚本、README、overview

目标：

- 整个 histogram 功能栈与多维语义一致

---

## 9. 建议结论

建议先和 supervisor 确认以下三点：

1. 是否接受将多维 histogram 定义为“多维网格上的联合分布”
2. 是否接受新的统一 schema：`dimensions + edges + weights`
3. `cdf` 在多维场景下是否要支持联合 CDF，还是暂时限制在 1D

如果这三点确认下来，后续实现路径会比较清晰。
