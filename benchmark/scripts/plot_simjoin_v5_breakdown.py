#!/usr/bin/env python3
"""
Plot 2: V5 Adaptive Breakdown — Stacked Bar by Dataset
=======================================================
Shows how V5 dispatches pairs across its 3 stages:
  - Bounds filter (O(1) analytical rejection)
  - SPRT early termination
  - Full stratified sampling

X-axis: Dataset (Easy / Medium / Hard / Mixed)
Y-axis: Percentage of GMM pairs handled by each stage

Reads: benchmark/results/simjoin_v5_breakdown.csv
"""

import argparse
import csv
import sys

import numpy as np
import matplotlib.pyplot as plt
import matplotlib

matplotlib.rcParams.update(
    {
        "font.size": 12,
        "axes.labelsize": 14,
        "axes.titlesize": 15,
        "legend.fontsize": 11,
        "xtick.labelsize": 12,
        "ytick.labelsize": 11,
        "figure.dpi": 150,
    }
)

DATASETS = ["easy", "medium", "hard", "mixed"]

STAGE_COLORS = {
    "Bounds": "#2ecc71",  # Green
    "SPRT": "#f1c40f",  # Yellow
    "Full": "#e74c3c",  # Red
}


def load_csv(path):
    """Load simjoin_v5_breakdown.csv → list of dicts."""
    rows = []
    with open(path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(row)
    return rows


def plot(rows, output):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 6))

    # --- Left: Pair counts (stacked bar) ---
    datasets_found = []
    bounds_counts = []
    sprt_counts = []
    full_counts = []

    for ds in DATASETS:
        row = next((r for r in rows if r["Dataset"] == ds), None)
        if row is None:
            continue
        datasets_found.append(ds.capitalize())
        bounds_counts.append(int(row["BoundsFiltered"]))
        sprt_counts.append(int(row["SPRTEarly"]))
        full_counts.append(int(row["FullSampling"]))

    if not datasets_found:
        print("No data found in CSV!")
        return

    x = np.arange(len(datasets_found))
    width = 0.5

    totals = np.array(bounds_counts) + np.array(sprt_counts) + np.array(full_counts)
    bounds_pct = 100.0 * np.array(bounds_counts) / totals
    sprt_pct = 100.0 * np.array(sprt_counts) / totals
    full_pct = 100.0 * np.array(full_counts) / totals

    ax1.bar(
        x,
        bounds_pct,
        width,
        label="Bounds Filter (O(1))",
        color=STAGE_COLORS["Bounds"],
        edgecolor="black",
        linewidth=0.5,
    )
    ax1.bar(
        x,
        sprt_pct,
        width,
        bottom=bounds_pct,
        label="SPRT Early Stop",
        color=STAGE_COLORS["SPRT"],
        edgecolor="black",
        linewidth=0.5,
    )
    ax1.bar(
        x,
        full_pct,
        width,
        bottom=bounds_pct + sprt_pct,
        label="Full Stratified Sampling",
        color=STAGE_COLORS["Full"],
        edgecolor="black",
        linewidth=0.5,
    )

    # Percentage labels
    for i in range(len(datasets_found)):
        if bounds_pct[i] > 5:
            ax1.text(
                x[i],
                bounds_pct[i] / 2,
                f"{bounds_pct[i]:.0f}%",
                ha="center",
                va="center",
                fontsize=10,
                fontweight="bold",
            )
        if sprt_pct[i] > 5:
            ax1.text(
                x[i],
                bounds_pct[i] + sprt_pct[i] / 2,
                f"{sprt_pct[i]:.0f}%",
                ha="center",
                va="center",
                fontsize=10,
                fontweight="bold",
            )
        if full_pct[i] > 5:
            ax1.text(
                x[i],
                bounds_pct[i] + sprt_pct[i] + full_pct[i] / 2,
                f"{full_pct[i]:.0f}%",
                ha="center",
                va="center",
                fontsize=10,
                fontweight="bold",
            )

    ax1.set_ylabel("Percentage of Pairs (%)")
    ax1.set_xlabel("Dataset Difficulty")
    ax1.set_title("V5 Dispatch Distribution", fontsize=13, fontweight="bold")
    ax1.set_xticks(x)
    ax1.set_xticklabels(datasets_found)
    ax1.set_ylim(0, 105)
    ax1.legend(loc="upper right", fontsize=9)
    ax1.grid(True, axis="y", alpha=0.3)
    ax1.set_axisbelow(True)

    # --- Right: Absolute counts (stacked bar) ---
    ax2.bar(
        x,
        bounds_counts,
        width,
        label="Bounds Filter",
        color=STAGE_COLORS["Bounds"],
        edgecolor="black",
        linewidth=0.5,
    )
    ax2.bar(
        x,
        sprt_counts,
        width,
        bottom=bounds_counts,
        label="SPRT Early Stop",
        color=STAGE_COLORS["SPRT"],
        edgecolor="black",
        linewidth=0.5,
    )
    ax2.bar(
        x,
        full_counts,
        width,
        bottom=np.array(bounds_counts) + np.array(sprt_counts),
        label="Full Stratified",
        color=STAGE_COLORS["Full"],
        edgecolor="black",
        linewidth=0.5,
    )

    # Total labels
    for i in range(len(datasets_found)):
        ax2.text(
            x[i],
            totals[i],
            f"{totals[i]:,}",
            ha="center",
            va="bottom",
            fontsize=9,
            fontweight="bold",
        )

    ax2.set_ylabel("Number of Pair Comparisons")
    ax2.set_xlabel("Dataset Difficulty")
    ax2.set_title("V5 Pair Counts by Stage", fontsize=13, fontweight="bold")
    ax2.set_xticks(x)
    ax2.set_xticklabels(datasets_found)
    ax2.legend(loc="upper right", fontsize=9)
    ax2.grid(True, axis="y", alpha=0.3)
    ax2.set_axisbelow(True)

    fig.suptitle(
        "V5 Adaptive Sampler — Internal Dispatch Analysis\n"
        "(How each GMM pair is handled: O(1) bounds → SPRT → Full sampling)",
        fontsize=14,
        fontweight="bold",
        y=1.02,
    )

    plt.tight_layout()
    plt.savefig(output, dpi=300, bbox_inches="tight")
    print(f"Saved: {output}")
    plt.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="benchmark/results/simjoin_v5_breakdown.csv")
    parser.add_argument(
        "--output", default="benchmark/results/plot_simjoin_v5_breakdown.png"
    )
    args = parser.parse_args()

    rows = load_csv(args.input)
    plot(rows, args.output)


if __name__ == "__main__":
    main()
