#!/usr/bin/env python3
"""
Plot: Convergence Analysis - Stratified Sampling
================================================
X-axis: Number of samples (log scale)
Y-axis: MAE (Mean Absolute Error)
Shows how accuracy improves with more samples.

Reads: benchmark/results/simjoin_convergence.csv
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
        "legend.fontsize": 11,
        "xtick.labelsize": 11,
        "ytick.labelsize": 11,
        "figure.dpi": 150,
    }
)


def load_csv(path):
    """Load simjoin_convergence.csv"""
    data = defaultdict(list)
    with open(path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            samples = int(row["Samples"])
            mae = float(row["MAE"])
            rmse = float(row["RMSE"])
            time = float(row["Time_ms"])
            data[samples].append({"mae": mae, "rmse": rmse, "time": time})
    return data


def plot_convergence(data, output):
    fig, ax = plt.subplots(figsize=(10, 7))

    samples = sorted(data.keys())
    means = []
    stds = []

    for s in samples:
        maes = [d["mae"] for d in data[s]]
        means.append(np.mean(maes))
        stds.append(np.std(maes) if len(maes) > 1 else 0)

    means = np.array(means)
    stds = np.array(stds)

    ax.plot(
        samples,
        means,
        "o-",
        color="#2ecc71",
        linewidth=2.5,
        markersize=10,
        label="Stratified Sampling",
    )
    ax.fill_between(samples, means - stds, means + stds, alpha=0.2, color="#2ecc71")

    ax.set_xscale("log")
    ax.set_xlabel("Number of Samples (log scale)", fontsize=13)
    ax.set_ylabel("MAE (Mean Absolute Error) — Lower is Better", fontsize=13)
    ax.set_title(
        "Convergence Analysis: Stratified Sampling\n"
        "Accuracy improves with more samples (Dataset: Hard, N=10,000 pairs)",
        fontsize=14,
        fontweight="bold",
    )

    ax.grid(True, alpha=0.3, which="both")
    ax.set_axisbelow(True)

    for i, (s, m) in enumerate(zip(samples, means)):
        if i % 2 == 0:
            ax.annotate(
                f"{m:.4f}",
                (s, m),
                xytext=(5, 8),
                textcoords="offset points",
                fontsize=9,
            )

    ax.legend(loc="upper right", fontsize=11)

    plt.tight_layout()
    plt.savefig(output, dpi=300, bbox_inches="tight")
    print(f"Saved: {output}")
    plt.close()


def plot_convergence_with_theoretical(data, output):
    """Plot with theoretical O(1/sqrt(n)) convergence"""
    fig, ax = plt.subplots(figsize=(10, 7))

    samples = sorted(data.keys())
    means = []
    stds = []

    for s in samples:
        maes = [d["mae"] for d in data[s]]
        means.append(np.mean(maes))
        stds.append(np.std(maes) if len(maes) > 1 else 0)

    means = np.array(means)
    stds = np.array(stds)
    samples = np.array(samples)

    ax.plot(
        samples,
        means,
        "o-",
        color="#2ecc71",
        linewidth=2.5,
        markersize=10,
        label="Empirical MAE",
    )
    ax.fill_between(samples, means - stds, means + stds, alpha=0.2, color="#2ecc71")

    theoretical = means[0] * np.sqrt(samples[0]) / np.sqrt(samples)
    ax.plot(
        samples,
        theoretical,
        "--",
        color="#e74c3c",
        linewidth=2,
        label=r"Theoretical $O(1/\sqrt{n})$",
    )

    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlabel("Number of Samples (log scale)", fontsize=13)
    ax.set_ylabel("MAE (log scale) — Lower is Better", fontsize=13)
    ax.set_title(
        "Convergence Analysis: Stratified Sampling\n"
        "Empirical vs Theoretical Convergence Rate",
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
    parser.add_argument("--input", default="benchmark/results/simjoin_convergence.csv")
    parser.add_argument("--output-dir", default="benchmark/results")
    args = parser.parse_args()

    data = load_csv(args.input)

    plot_convergence(data, f"{args.output_dir}/plot_convergence.png")
    plot_convergence_with_theoretical(
        data, f"{args.output_dir}/plot_convergence_loglog.png"
    )


if __name__ == "__main__":
    main()
