#!/usr/bin/env python3
"""
Plot 1: Grouped Bar Chart — SimilarityJoin Latency by Method × Dataset
=======================================================================
X-axis: Dataset (Easy / Medium / Hard / Mixed)
Y-axis: Execution Time (ms) — log scale
Bars:   GT_10K (grey baseline) + V1-V5

Reads: benchmark/results/simjoin_results.csv
"""

import argparse
import csv
import sys
from collections import defaultdict

import numpy as np
import matplotlib.pyplot as plt
import matplotlib

matplotlib.rcParams.update(
    {
        "font.size": 12,
        "axes.labelsize": 14,
        "axes.titlesize": 15,
        "legend.fontsize": 9,
        "xtick.labelsize": 12,
        "ytick.labelsize": 11,
        "figure.dpi": 150,
    }
)

METHODS = ["GT_10K", "V1_MC", "V2_STRATIFIED", "V3_SPRT", "V4_BOUNDS", "V5_ADAPTIVE"]
DATASETS = ["easy", "medium", "hard", "mixed"]

COLORS = {
    "GT_10K": "#95a5a6",  # Grey
    "V1_MC": "#3498db",  # Blue
    "V2_STRATIFIED": "#2ecc71",  # Green
    "V3_SPRT": "#f39c12",  # Orange
    "V4_BOUNDS": "#e74c3c",  # Red
    "V5_ADAPTIVE": "#9b59b6",  # Purple
}

LABELS = {
    "GT_10K": "GT (N=10k)",
    "V1_MC": "V1 (MC)",
    "V2_STRATIFIED": "V2 (Stratified)",
    "V3_SPRT": "V3 (SPRT)",
    "V4_BOUNDS": "V4 (Bounds)",
    "V5_ADAPTIVE": "V5 (Adaptive)",
}


def load_csv(path):
    """Load simjoin_results.csv → {method: {dataset: [times]}}."""
    data = defaultdict(lambda: defaultdict(list))
    with open(path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            data[row["Method"]][row["Dataset"]].append(float(row["Time_ms"]))
    return data


def plot(data, output):
    fig, ax = plt.subplots(figsize=(12, 6))

    n_methods = len(METHODS)
    n_datasets = len(DATASETS)
    width = 0.12
    x = np.arange(n_datasets)

    for i, method in enumerate(METHODS):
        means = []
        stds = []
        for ds in DATASETS:
            times = data.get(method, {}).get(ds, [0])
            means.append(np.mean(times))
            stds.append(np.std(times) if len(times) > 1 else 0)

        offset = (i - (n_methods - 1) / 2) * width
        bars = ax.bar(
            x + offset,
            means,
            width,
            yerr=stds,
            label=LABELS.get(method, method),
            color=COLORS.get(method, "#666"),
            edgecolor="black",
            linewidth=0.5,
            capsize=3,
            error_kw={"linewidth": 0.8},
        )

        # Value labels
        for bar, m in zip(bars, means):
            if m > 0:
                ax.text(
                    bar.get_x() + bar.get_width() / 2,
                    bar.get_height(),
                    f"{m:.0f}",
                    ha="center",
                    va="bottom",
                    fontsize=6.5,
                    fontweight="bold",
                )

    ax.set_yscale("log")
    ax.set_xlabel("Dataset Difficulty")
    ax.set_ylabel("Execution Time (ms) — Log Scale")
    ax.set_title(
        "SimilarityJoin Benchmark: Latency by Method × Dataset\n"
        "(100 Left × 100 Right = 10,000 pair comparisons, sample budget = 5000)",
        fontsize=13,
        fontweight="bold",
    )

    ax.set_xticks(x)
    ax.set_xticklabels([d.capitalize() for d in DATASETS])
    ax.legend(ncol=3, loc="upper left", framealpha=0.9)
    ax.grid(True, axis="y", alpha=0.3, which="both")
    ax.set_axisbelow(True)

    plt.tight_layout()
    plt.savefig(output, dpi=300, bbox_inches="tight")
    print(f"Saved: {output}")
    plt.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="benchmark/results/simjoin_results.csv")
    parser.add_argument(
        "--output", default="benchmark/results/plot_simjoin_grouped_bar.png"
    )
    args = parser.parse_args()

    data = load_csv(args.input)
    plot(data, args.output)


if __name__ == "__main__":
    main()
