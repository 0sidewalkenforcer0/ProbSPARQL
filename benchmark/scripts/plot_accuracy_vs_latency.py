#!/usr/bin/env python3
"""
Plot: Accuracy vs Latency for SimilarityJoin
=============================================
X-axis: Latency (ms) - log scale
Y-axis: MAE (Mean Absolute Error) - lower is better
Point size: represents correlation (higher = better)

Reads: benchmark/results/simjoin_accuracy_latency.csv
"""

import argparse
import csv
from collections import defaultdict

import numpy as np
import matplotlib.pyplot as plt
import matplotlib

matplotlib.rcParams.update(
    {
        "font.size": 12,
        "axes.labelsize": 14,
        "axes.titlesize": 15,
        "legend.fontsize": 10,
        "xtick.labelsize": 11,
        "ytick.labelsize": 11,
        "figure.dpi": 150,
    }
)

METHODS = ["GT_10K", "V1_MC", "V2_STRATIFIED", "V3_SPRT", "V4_BOUNDS", "V5_ADAPTIVE"]
DATASETS = ["easy", "medium", "hard", "mixed"]

COLORS = {
    "GT_10K": "#95a5a6",
    "V1_MC": "#3498db",
    "V2_STRATIFIED": "#2ecc71",
    "V3_SPRT": "#f39c12",
    "V4_BOUNDS": "#e74c3c",
    "V5_ADAPTIVE": "#9b59b6",
}

MARKERS = {
    "easy": "o",
    "medium": "s",
    "hard": "^",
    "mixed": "D",
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
    """Load simjoin_accuracy_latency.csv"""
    data = defaultdict(lambda: defaultdict(dict))
    with open(path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            method = row["Method"]
            dataset = row["Dataset"]
            latency = float(row["Latency_ms"])
            mae = float(row["MAE"])
            rmse = float(row["RMSE"])
            corr = float(row["Correlation"])
            data[method][dataset] = {
                "latency": latency,
                "mae": mae,
                "rmse": rmse,
                "corr": corr,
            }
    return data


def plot_all_datasets(data, output):
    """Plot all datasets on one figure"""
    fig, ax = plt.subplots(figsize=(10, 7))

    for method in METHODS:
        for dataset in DATASETS:
            if dataset not in data[method]:
                continue
            d = data[method][dataset]
            marker = MARKERS[dataset]

            if method == "GT_10K":
                ax.scatter(
                    d["latency"],
                    d["mae"],
                    c=COLORS[method],
                    marker=marker,
                    s=150,
                    edgecolors="black",
                    linewidths=1,
                    alpha=0.8,
                    label=f"{LABELS[method]} ({dataset})",
                )
            else:
                ax.scatter(
                    d["latency"],
                    d["mae"],
                    c=COLORS[method],
                    marker=marker,
                    s=150,
                    edgecolors="black",
                    linewidths=1,
                    alpha=0.8,
                    label=f"{LABELS[method]} ({dataset})",
                )

    ax.set_xscale("log")
    ax.set_xlabel("Latency (ms) — Log Scale", fontsize=13)
    ax.set_ylabel("MAE (Mean Absolute Error) — Lower is Better", fontsize=13)
    ax.set_title(
        "SimilarityJoin: Accuracy vs Latency Trade-off\n"
        "(100 Left × 100 Right = 10,000 pair comparisons)",
        fontsize=14,
        fontweight="bold",
    )

    ax.grid(True, alpha=0.3, which="both")
    ax.set_axisbelow(True)

    handles, labels = ax.get_legend_handles_labels()
    by_label = dict(zip(labels, handles))
    ax.legend(
        by_label.values(),
        by_label.keys(),
        loc="upper right",
        fontsize=8,
        ncol=1,
        framealpha=0.9,
    )

    plt.tight_layout()
    plt.savefig(output, dpi=300, bbox_inches="tight")
    print(f"Saved: {output}")
    plt.close()


def plot_by_dataset(data, output):
    """Plot each dataset separately"""
    fig, axes = plt.subplots(2, 2, figsize=(14, 12))
    axes = axes.flatten()

    for idx, dataset in enumerate(DATASETS):
        ax = axes[idx]

        for method in METHODS:
            if dataset not in data[method]:
                continue
            d = data[method][dataset]

            ax.scatter(
                d["latency"],
                d["mae"],
                c=COLORS[method],
                marker="o",
                s=200,
                edgecolors="black",
                linewidths=1.5,
                alpha=0.8,
                label=LABELS[method],
            )

            ax.annotate(
                LABELS[method],
                (d["latency"], d["mae"]),
                xytext=(5, 5),
                textcoords="offset points",
                fontsize=8,
            )

        ax.set_xscale("log")
        ax.set_xlabel("Latency (ms)")
        ax.set_ylabel("MAE")
        ax.set_title(f"Dataset: {dataset.capitalize()}", fontsize=12, fontweight="bold")
        ax.grid(True, alpha=0.3, which="both")
        ax.set_axisbelow(True)

    plt.suptitle(
        "Accuracy vs Latency by Dataset\n(MAE = Mean Absolute Error vs GT_10K)",
        fontsize=14,
        fontweight="bold",
        y=1.02,
    )
    plt.tight_layout()
    plt.savefig(output, dpi=300, bbox_inches="tight")
    print(f"Saved: {output}")
    plt.close()


def plot_summary(data, output):
    """Plot average across datasets"""
    fig, ax = plt.subplots(figsize=(10, 7))

    avg_data = defaultdict(lambda: {"latency": [], "mae": [], "corr": []})

    for method in METHODS:
        for dataset in DATASETS:
            if dataset not in data[method]:
                continue
            d = data[method][dataset]
            avg_data[method]["latency"].append(d["latency"])
            avg_data[method]["mae"].append(d["mae"])
            avg_data[method]["corr"].append(d["corr"])

    for method in METHODS:
        if not avg_data[method]["latency"]:
            continue

        latencies = avg_data[method]["latency"]
        maes = avg_data[method]["mae"]

        avg_latency = np.mean(latencies)
        avg_mae = np.mean(maes)

        ax.scatter(
            avg_latency,
            avg_mae,
            c=COLORS[method],
            marker="o",
            s=300,
            edgecolors="black",
            linewidths=2,
            alpha=0.9,
            label=LABELS[method],
        )

        ax.annotate(
            f"{avg_latency:.1f}ms\nMAE={avg_mae:.4f}",
            (avg_latency, avg_mae),
            xytext=(10, -10),
            textcoords="offset points",
            fontsize=9,
            bbox=dict(boxstyle="round,pad=0.3", facecolor="white", alpha=0.8),
        )

    ax.set_xscale("log")
    ax.set_xlabel("Average Latency (ms) — Log Scale", fontsize=13)
    ax.set_ylabel("Average MAE — Lower is Better", fontsize=13)
    ax.set_title(
        "SimilarityJoin: Accuracy vs Latency Summary\n(Averaged across Easy/Medium/Hard/Mixed)",
        fontsize=14,
        fontweight="bold",
    )

    ax.grid(True, alpha=0.3, which="both")
    ax.set_axisbelow(True)
    ax.legend(loc="upper right", fontsize=11)

    plt.tight_layout()
    plt.savefig(output, dpi=300, bbox_inches="tight")
    print(f"Saved: {output}")
    plt.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input", default="benchmark/results/simjoin_accuracy_latency.csv"
    )
    parser.add_argument("--output-dir", default="benchmark/results")
    args = parser.parse_args()

    data = load_csv(args.input)

    plot_all_datasets(data, f"{args.output_dir}/plot_accuracy_vs_latency_all.png")
    plot_by_dataset(data, f"{args.output_dir}/plot_accuracy_vs_latency_by_dataset.png")
    plot_summary(data, f"{args.output_dir}/plot_accuracy_vs_latency_summary.png")


if __name__ == "__main__":
    main()
