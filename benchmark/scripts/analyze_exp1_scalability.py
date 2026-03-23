#!/usr/bin/env python3
"""
Exp 1 Analysis: System Overhead — ProbSPARQL vs. Deterministic SPARQL
======================================================================
Reads: benchmark/results/simjoin_results.csv  (existing)
       benchmark/results/simjoin_accuracy_latency.csv (existing)

If ScalabilityBenchmark produces a separate CSV, also reads that.
Falls back to deriving overhead estimates from the accuracy-latency file.

Produces:
  - Figure 1: Line chart  x=dataset difficulty, y=latency per method
  - Figure 2: Bar chart   GT_10K (deterministic proxy) vs. V1-V5
  - Table:    Overhead ratios

Usage:
  python3 benchmark/scripts/analyze_exp1_scalability.py \
      --results-csv  benchmark/results/simjoin_results.csv \
      --accuracy-csv benchmark/results/simjoin_accuracy_latency.csv \
      --output       benchmark/results
"""

import argparse
import csv
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import matplotlib
import matplotlib.pyplot as plt

matplotlib.rcParams.update({
    "font.size": 12, "axes.labelsize": 14, "axes.titlesize": 15,
    "legend.fontsize": 10, "xtick.labelsize": 11, "ytick.labelsize": 11,
    "figure.dpi": 150,
})

# ─────────────────────────────────────────────────────────────────────────────
METHODS  = ["GT_10K", "V1_MC", "V2_STRATIFIED", "V3_SPRT", "V4_BOUNDS", "V5_ADAPTIVE"]
DATASETS = ["easy", "medium", "hard", "mixed"]

METHOD_LABELS = {
    "GT_10K":        "GT (N=10k) ← baseline",
    "V1_MC":         "V1: Monte Carlo",
    "V2_STRATIFIED": "V2: Stratified",
    "V3_SPRT":       "V3: SPRT",
    "V4_BOUNDS":     "V4: Bounds",
    "V5_ADAPTIVE":   "V5: Adaptive",
}
DATASET_LABELS = {
    "easy": "Easy", "medium": "Medium", "hard": "Hard", "mixed": "Mixed",
}
COLORS = ["#95a5a6", "#3498db", "#2ecc71", "#f39c12", "#e74c3c", "#9b59b6"]
# ─────────────────────────────────────────────────────────────────────────────


def load_results(path: str) -> dict:
    """Load simjoin_results.csv → {method: {dataset: [times]}}"""
    data: dict = defaultdict(lambda: defaultdict(list))
    with open(path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            data[row["Method"]][row["Dataset"]].append(float(row["Time_ms"]))
    return data


def load_accuracy_latency(path: str) -> dict:
    """Load simjoin_accuracy_latency.csv → {method: {dataset: latency_ms}}"""
    data: dict = {}
    with open(path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            m  = row["Method"]
            ds = row["Dataset"]
            data.setdefault(m, {})[ds] = float(row["Latency_ms"])
    return data


def compute_overhead(data: dict) -> dict:
    """Compute overhead ratio: method_time / GT_10K_time per dataset."""
    overhead: dict = {}
    for m in METHODS:
        if m == "GT_10K": continue
        if m not in data: continue
        overhead[m] = {}
        for ds in DATASETS:
            gt_times  = data.get("GT_10K", {}).get(ds, [1.0])
            m_times   = data.get(m, {}).get(ds, [0.0])
            gt_median = float(np.median(gt_times)) if gt_times else 1.0
            m_median  = float(np.median(m_times))  if m_times else 0.0
            overhead[m][ds] = m_median / gt_median if gt_median > 0 else 0.0
    return overhead


def print_overhead_table(data: dict) -> None:
    overhead = compute_overhead(data)
    col_w = 12
    header = f"{'Method':<18}" + "".join(f"{DATASET_LABELS.get(d,d):>{col_w}}" for d in DATASETS)
    print("\n── Overhead Ratio (ProbSPARQL / GT_10K) ─────────────────────────────")
    print(header)
    print("─" * len(header))
    for m in METHODS:
        if m == "GT_10K": continue
        row = f"{METHOD_LABELS.get(m,m):<18}"
        for ds in DATASETS:
            r = overhead.get(m, {}).get(ds, float("nan"))
            row += f"{r:>{col_w}.3f}×"
        print(row)

    print("\n── Median Latency (ms) ───────────────────────────────────────────────")
    print(header)
    print("─" * len(header))
    for m in METHODS:
        if m not in data: continue
        row = f"{METHOD_LABELS.get(m,m):<18}"
        for ds in DATASETS:
            ts = data.get(m, {}).get(ds, [float("nan")])
            row += f"{float(np.median(ts)):>{col_w}.2f}"
        print(row)
    print()


# ─────────────────────────────────────────────────────────────────────────────
# Figure 1 — Latency line chart (x=dataset, y=time, one line per method)
# ─────────────────────────────────────────────────────────────────────────────
def plot_latency_lines(data: dict, out_dir: Path) -> None:
    methods = [m for m in METHODS if m in data]
    x = np.arange(len(DATASETS))

    fig, ax = plt.subplots(figsize=(11, 6))
    for i, m in enumerate(methods):
        meds  = [float(np.median(data[m].get(ds, [0]))) for ds in DATASETS]
        stds  = [float(np.std(data[m].get(ds, [0])))    for ds in DATASETS]
        ax.plot(x, meds, "-o", color=COLORS[i % len(COLORS)],
                label=METHOD_LABELS.get(m, m), linewidth=2, markersize=7)
        ax.fill_between(x, [m-s for m,s in zip(meds,stds)],
                           [m+s for m,s in zip(meds,stds)],
                        color=COLORS[i % len(COLORS)], alpha=0.10)

    ax.set_xticks(x)
    ax.set_xticklabels([DATASET_LABELS[d] for d in DATASETS])
    ax.set_xlabel("Dataset Difficulty")
    ax.set_ylabel("Median Execution Time (ms)")
    ax.set_title("Exp 1 — Latency by Method × Dataset Difficulty")
    ax.legend(ncol=2)
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    out = out_dir / "exp1_latency_lines.png"
    fig.savefig(out);  print(f"Saved: {out}")
    plt.close(fig)


# ─────────────────────────────────────────────────────────────────────────────
# Figure 2 — Grouped bar chart (x=dataset, y=latency, bars=methods)
# ─────────────────────────────────────────────────────────────────────────────
def plot_latency_bars(data: dict, out_dir: Path) -> None:
    methods = [m for m in METHODS if m in data]
    n_m, n_ds = len(methods), len(DATASETS)
    width = 0.12
    x = np.arange(n_ds)

    fig, ax = plt.subplots(figsize=(13, 6))
    for i, m in enumerate(methods):
        meds = [float(np.median(data[m].get(ds, [0]))) for ds in DATASETS]
        stds = [float(np.std(data[m].get(ds, [0])))    for ds in DATASETS]
        offset = (i - n_m / 2 + 0.5) * width
        ax.bar(x + offset, meds, width,
               yerr=stds, capsize=3,
               label=METHOD_LABELS.get(m, m),
               color=COLORS[i % len(COLORS)],
               edgecolor="white")

    ax.set_xticks(x)
    ax.set_xticklabels([DATASET_LABELS[d] for d in DATASETS])
    ax.set_xlabel("Dataset Difficulty")
    ax.set_ylabel("Median Execution Time (ms)")
    ax.set_title("Exp 1 — Per-Query Latency Comparison")
    ax.set_yscale("log")
    ax.legend(ncol=3)
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    out = out_dir / "exp1_latency_bars.png"
    fig.savefig(out);  print(f"Saved: {out}")
    plt.close(fig)


# ─────────────────────────────────────────────────────────────────────────────
# Figure 3 — Overhead ratio bar chart
# ─────────────────────────────────────────────────────────────────────────────
def plot_overhead(data: dict, out_dir: Path) -> None:
    overhead = compute_overhead(data)
    methods  = [m for m in METHODS if m != "GT_10K" and m in data]
    n_m, n_ds = len(methods), len(DATASETS)
    width = 0.15
    x = np.arange(n_ds)

    fig, ax = plt.subplots(figsize=(12, 5))
    for i, m in enumerate(methods):
        ratios = [overhead.get(m, {}).get(ds, 0) for ds in DATASETS]
        offset = (i - n_m / 2 + 0.5) * width
        ax.bar(x + offset, ratios, width,
               label=METHOD_LABELS.get(m, m),
               color=COLORS[(i+1) % len(COLORS)],
               edgecolor="white")

    ax.axhline(1.0, color="black", linewidth=1.2, linestyle="--", label="GT baseline (1.0×)")
    ax.set_xticks(x)
    ax.set_xticklabels([DATASET_LABELS[d] for d in DATASETS])
    ax.set_xlabel("Dataset Difficulty")
    ax.set_ylabel("Overhead Ratio (vs. GT_10K)")
    ax.set_title("Exp 1 — Overhead Ratio: ProbSPARQL Methods vs. GT Baseline")
    ax.legend(ncol=3)
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    out = out_dir / "exp1_overhead_ratio.png"
    fig.savefig(out);  print(f"Saved: {out}")
    plt.close(fig)


# ─────────────────────────────────────────────────────────────────────────────
def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--results-csv",  default="benchmark/results/simjoin_results.csv")
    ap.add_argument("--accuracy-csv", default="benchmark/results/simjoin_accuracy_latency.csv")
    ap.add_argument("--output",       default="benchmark/results")
    args = ap.parse_args()

    out_dir = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)

    if not Path(args.results_csv).exists():
        print(f"ERROR: {args.results_csv} not found", file=sys.stderr)
        sys.exit(1)

    data = load_results(args.results_csv)
    print_overhead_table(data)
    plot_latency_lines(data, out_dir)
    plot_latency_bars(data, out_dir)
    plot_overhead(data, out_dir)
    print("Done.")


if __name__ == "__main__":
    main()
