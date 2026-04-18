#!/usr/bin/env python3
"""
Plot: Ablation Bar Chart
=======================
Creates a grouped bar chart showing execution time for different query patterns
(Strict, Medium, Loose) across different sampling strategies (V1-V5).

Usage:
    python plot_ablation_bar.py [--input benchmark_results.json] [--output ablation_bar.png]
"""

import argparse
import json
import numpy as np
import matplotlib.pyplot as plt
import matplotlib

matplotlib.rcParams.update(
    {
        "font.size": 12,
        "axes.labelsize": 14,
        "axes.titlesize": 16,
        "legend.fontsize": 10,
        "xtick.labelsize": 11,
        "ytick.labelsize": 11,
        "figure.figsize": (10, 6),
        "figure.dpi": 150,
    }
)


def load_results(json_path):
    """Load benchmark results from JSON file."""
    with open(json_path, "r") as f:
        return json.load(f)


def plot_ablation_bar(results, output_path):
    """Create grouped bar chart for ablation study."""

    fig, ax = plt.subplots(figsize=(11, 6))

    patterns = ["Strict\n(5%)", "Medium\n(25%)", "Loose\n(60%)"]
    strategies = ["V1_MC", "V2_STRATIFIED", "V3_SPRT", "V4_BOUNDS", "V5_ADAPTIVE"]

    colors = {
        "V1_MC": "#3498db",  # Blue
        "V2_STRATIFIED": "#2ecc71",  # Green
        "V3_SPRT": "#f39c12",  # Orange
        "V4_BOUNDS": "#e74c3c",  # Red
        "V5_BOUNDS": "#e74c3c",  # Red (for stacked component)
        "V5_SPRT": "#f1c40f",  # Yellow (for stacked component)
        "V5_SAMPLE": "#2ecc71",  # Green (for stacked component)
    }

    x = np.arange(len(patterns))
    width = 0.14

    # Sample data if no real data
    v1_times = [150, 250, 400]
    v2_times = [120, 200, 350]
    v3_times = [60, 150, 300]
    v4_times = [25, 120, 280]

    # V5 stacked components
    v5_bounds = [15, 10, 5]
    v5_sprt = [10, 25, 15]
    v5_sample = [5, 35, 80]

    # Try to load real data if available
    try:
        v1_data = results.get("V1_MC", {})
        v1_times = [
            v1_data.get(p.lower(), {}).get("time", v1_times[i])
            for i, p in enumerate(["strict", "medium", "loose"])
        ]
    except:
        pass

    try:
        v2_data = results.get("V2_STRATIFIED", {})
        v2_times = [
            v2_data.get(p.lower(), {}).get("time", v2_times[i])
            for i, p in enumerate(["strict", "medium", "loose"])
        ]
    except:
        pass

    try:
        v3_data = results.get("V3_SPRT", {})
        v3_times = [
            v3_data.get(p.lower(), {}).get("time", v3_times[i])
            for i, p in enumerate(["strict", "medium", "loose"])
        ]
    except:
        pass

    try:
        v4_data = results.get("V4_BOUNDS", {})
        v4_times = [
            v4_data.get(p.lower(), {}).get("time", v4_times[i])
            for i, p in enumerate(["strict", "medium", "loose"])
        ]
    except:
        pass

    try:
        v5_data = results.get("V5_STACKED", {})
        v5_bounds = [
            v5_data.get(p, {}).get("bounds", v5_bounds[i])
            for i, p in enumerate(["Strict", "Medium", "Loose"])
        ]
        v5_sprt = [
            v5_data.get(p, {}).get("sprt", v5_sprt[i])
            for i, p in enumerate(["Strict", "Medium", "Loose"])
        ]
        v5_sample = [
            v5_data.get(p, {}).get("sample", v5_sample[i])
            for i, p in enumerate(["Strict", "Medium", "Loose"])
        ]
    except:
        pass

    # Plot V1-V4 as regular bars
    bars_v1 = ax.bar(
        x - 2 * width,
        v1_times,
        width,
        label="V1 (Monte Carlo)",
        color=colors["V1_MC"],
        edgecolor="black",
        linewidth=0.5,
    )
    bars_v2 = ax.bar(
        x - width,
        v2_times,
        width,
        label="V2 (Stratified)",
        color=colors["V2_STRATIFIED"],
        edgecolor="black",
        linewidth=0.5,
    )
    bars_v3 = ax.bar(
        x,
        v3_times,
        width,
        label="V3 (SPRT)",
        color=colors["V3_SPRT"],
        edgecolor="black",
        linewidth=0.5,
    )
    bars_v4 = ax.bar(
        x + width,
        v4_times,
        width,
        label="V4 (Bounds)",
        color=colors["V4_BOUNDS"],
        edgecolor="black",
        linewidth=0.5,
    )

    # Plot V5 as stacked bar
    bars_v5_bounds = ax.bar(
        x + 2 * width,
        v5_bounds,
        width,
        label="V5: Bounds Filter",
        color=colors["V5_BOUNDS"],
        edgecolor="black",
        linewidth=0.5,
    )
    bars_v5_sprt = ax.bar(
        x + 2 * width,
        v5_sprt,
        width,
        bottom=v5_bounds,
        label="V5: SPRT",
        color=colors["V5_SPRT"],
        edgecolor="black",
        linewidth=0.5,
    )
    bars_v5_sample = ax.bar(
        x + 2 * width,
        v5_sample,
        width,
        bottom=np.array(v5_bounds) + np.array(v5_sprt),
        label="V5: Full Sampling",
        color=colors["V5_SAMPLE"],
        edgecolor="black",
        linewidth=0.5,
    )

    # Add value labels on bars
    def add_labels(bars, offset=0):
        for bar in bars:
            height = bar.get_height()
            if height > 0:
                ax.annotate(
                    f"{int(height)}",
                    xy=(bar.get_x() + bar.get_width() / 2, height),
                    xytext=(0, 3),
                    textcoords="offset points",
                    ha="center",
                    va="bottom",
                    fontsize=8,
                    fontweight="bold",
                )

    # Only add labels for top-level bars
    for i, (b1, b2, b3, b4) in enumerate(zip(bars_v1, bars_v2, bars_v3, bars_v4)):
        ax.annotate(
            f"{int(b1.get_height())}",
            xy=(b1.get_x() + b1.get_width() / 2, b1.get_height()),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=7,
        )
        ax.annotate(
            f"{int(b2.get_height())}",
            xy=(b2.get_x() + b2.get_width() / 2, b2.get_height()),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=7,
        )
        ax.annotate(
            f"{int(b3.get_height())}",
            xy=(b3.get_x() + b3.get_width() / 2, b3.get_height()),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=7,
        )
        ax.annotate(
            f"{int(b4.get_height())}",
            xy=(b4.get_x() + b4.get_width() / 2, b4.get_height()),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=7,
        )

    # Add V5 total labels
    v5_totals = np.array(v5_bounds) + np.array(v5_sprt) + np.array(v5_sample)
    for i, t in enumerate(v5_totals):
        ax.annotate(
            f"{int(t)}",
            xy=(x[i] + 2 * width + width / 2, t),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=8,
            fontweight="bold",
            color="purple",
        )

    # Labels
    ax.set_xlabel("Query Pattern (Selectivity)", fontsize=13)
    ax.set_ylabel("Execution Time (ms)", fontsize=13)
    ax.set_title(
        "Ablation Study: V1-V5 Performance by Query Selectivity",
        fontsize=14,
        fontweight="bold",
    )

    ax.set_xticks(x + 0.5 * width)
    ax.set_xticklabels(patterns)

    # Legend - combine V5 components
    handles, labels = ax.get_legend_handles_labels()
    # Merge V5 labels
    new_labels = []
    new_handles = []
    skip_next = False
    for i, label in enumerate(labels):
        if "V5:" in label:
            continue
        new_labels.append(label)
        new_handles.append(handles[i])

    ax.legend(
        new_handles, new_labels, loc="upper left", ncol=3, framealpha=0.9, fontsize=9
    )

    # Grid
    ax.grid(True, axis="y", alpha=0.3, linestyle="-")
    ax.set_axisbelow(True)
    ax.set_ylim(0, 500)

    plt.tight_layout()
    plt.savefig(output_path, dpi=300, bbox_inches="tight")
    print(f"Saved: {output_path}")
    plt.close()


def generate_sample_data():
    """Generate sample data for demonstration."""
    return {
        "V1_MC": {
            "strict": {"time": 150, "results": 40},
            "medium": {"time": 250, "results": 200},
            "loose": {"time": 400, "results": 480},
        },
        "V2_STRATIFIED": {
            "strict": {"time": 120, "results": 40},
            "medium": {"time": 200, "results": 200},
            "loose": {"time": 350, "results": 480},
        },
        "V3_SPRT": {
            "strict": {"time": 60, "results": 40},
            "medium": {"time": 150, "results": 200},
            "loose": {"time": 300, "results": 480},
        },
        "V4_BOUNDS": {
            "strict": {"time": 25, "results": 40},
            "medium": {"time": 120, "results": 200},
            "loose": {"time": 280, "results": 480},
        },
        "V5_STACKED": {
            "Strict": {"bounds": 15, "sprt": 10, "sample": 5, "total": 30},
            "Medium": {"bounds": 10, "sprt": 25, "sample": 35, "total": 70},
            "Loose": {"bounds": 5, "sprt": 15, "sample": 80, "total": 100},
        },
    }


def main():
    parser = argparse.ArgumentParser(description="Plot ablation bar chart")
    parser.add_argument(
        "--input", default="benchmark_results.json", help="Input JSON file with results"
    )
    parser.add_argument("--output", default="ablation_bar.png", help="Output PNG file")
    args = parser.parse_args()

    try:
        results = load_results(args.input)
    except FileNotFoundError:
        print(f"Input file not found, generating sample data...")
        results = generate_sample_data()

    plot_ablation_bar(results, args.output)


if __name__ == "__main__":
    main()
