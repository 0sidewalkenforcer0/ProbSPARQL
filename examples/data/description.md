1. 背景介绍
在B02（材料影响评估）子项目的研究语境中，这些缩写分别代表了材料的特性、工艺参数或测量信号的关键指标：
CC (Carbon Content / 碳含量)：指材料的碳含量。B02在建立应变硬化行为（Strain Hardening Behavior）模型时，将不同碳含量的材料（如25CrMo4, 42CrMo4, 50CrMo4）作为关键输入参数进行评估
。在B02构建的材料科学知识图谱（Ontology）中，碳含量也被明确建模为一个重要属性节点（ns:CarbonContent）
。
Temp (Temperature / 温度)：通常指测试温度 (Test temperatures) 或 回火温度 (Tempering temperatures)。这是B02在评估工艺参数不确定性及其对材料机械性能影响时，重点考察的过程变量
。
RMS (Root Mean Square / 均方根值)：在B02的多模态无损检测中，特指巴克豪森噪声 (Barkhausen Noise) 信号的均方根值。通过提取和对比不同测试样本巴克豪森信号的RMS值（如系统界面中的 RMS avg），研究人员可以追踪材料在旋转弯曲疲劳测试中随周期演变的损伤情况
。
Hardness (硬度)：指材料的物理硬度属性。B02不仅关注表面硬度，还重点研究材料（包括传统制造和增材制造零件）在不同热处理状态（如感应淬火、回火）下的硬度深度分布曲线 (Depth profile of hardness)，用于全面分析零部件的初始状态与微观结构
。
RS (Residual Stress / 残余应力)：指残存在材料内部的应力。B02深入研究残余应力分布轮廓 (Profile of residual stresses)，利用X射线衍射等方法测量加工或热处理后材料内部的残余应力状态，并将其与硬度、微观结构结合，作为评估材料疲劳行为、裂纹萌生以及预测剩余使用寿命（RUL）的核心依据
。在B02的本体图谱中，它同样是材料状态的底层关键属性（ns:ResidualStress）
。

2. 例子展示(该例子并不全部正确)
```
# --- 1. 定义A03和B02跨组协作的本体命名空间 (Prefixes) ---
@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .

# A03 定义的核心本体 [2]
@prefix ag:   <http://example.org/anglegrinder#> .           # 领域本体 (Angle Grinder Ontology)
@prefix cfm:  <http://example.org/circularfactorymeasure#> . # 测量本体 (Circular Factory Measure Ontology)
@prefix uq:   <http://example.org/uncertainty#> .            # 不确定性本体 (Probability Modelling Partition)

# B02 定义的材料学本体 (Kadi4Mat / Material Science) [3]
@prefix ns:   <http://example.org/materialscience#> .        


# --- 2. 实例定义：B02正在检测的独特齿轮 (Product Instance) ---
ag:bevelgear_instance_001 a ag:BevelGear ;
    rdfs:label "Bevel Gear with CC42 and Temp550" ;
    # B02的确定性工艺参数：碳含量42，回火温度550
    ns:hasCarbonContent "42"^^xsd:integer ;
    ns:hasTemperingTemperature "550"^^xsd:integer ;
    # 关联到B02/B03的多模态材料特性
    cfm:hasCharacteristic ag:material_state_char_001 .

# 定义这个特征是一个可测量的特征 [6]
ag:material_state_char_001 a cfm:MeasurableCharacteristics ;
    rdfs:label "Joint Material State (RMS, Hardness, RS)" .


# --- 3. 测量与融合事件 (B03 Bayesian Fusion) ---
cfm:measurement_b03_fusion a cfm:Measurement ;
    rdfs:label "B03 Fused Measurement for Material State" ;
    cfm:measuresCharacteristic ag:material_state_char_001 ;
    # 引入概率随机变量
    cfm:hasProbabilisticValue cfm:random_variable_001 .


# --- 4. 核心：A03存储B03输出的GMM概率节点 (Without Information Loss) ---
# B03将 (RMS, Hardness, RS) 三个维度拟合为了一个 d=3 的联合高斯分布 [4, 5]
cfm:random_variable_001 a cfm:RandomVariable ;
    uq:hasDistribution """{
        "K": 1, 
        "d": 3, 
        "covariance_type": "full", 
        "weights": [0.33, 0.33, 0.34], 
        "means": [
            [631.7, 360.0, 4.3]
        ], 
        "covariances": [
            [
                [150.0, 12.0, -5.0],
                [12.0, 45.0, 2.1],
                [-5.0, 2.1, 8.4]
            ]
        ]
    }"""^^uq:gmmLiteral .
```

3. 参考数据
CC	Temp.	RMS	Hardness	RS
25	30	270.11889	480	22.28333