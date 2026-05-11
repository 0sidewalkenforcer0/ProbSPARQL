# Exp1 Dimension Scripts

This directory contains the fixed-`K`, varying-`d` supplement of Experiment 1.

Current setup:
- fixed `scale = E5`
- fixed `K = 3`
- varying `d ∈ {1, 2, 4, 8}`
- full Exp1 graph structure preserved
- runs `Q1–Q3` in deterministic and probabilistic modes
- runs `Q4` with both `prob:jsdivergence` and `prob:jsd`
- executes against remote Fuseki HTTP endpoints using `ENDPOINT_TEMPLATE`

Expected remote dataset services:
- `exp1_E5_K3_D1`
- `exp1_E5_K3_D2`
- `exp1_E5_K3_D4`
- `exp1_E5_K3_D8`
