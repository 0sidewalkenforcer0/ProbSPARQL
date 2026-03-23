#!/usr/bin/env python3
"""
Exp 3.1 Analysis: SimJoin Classification Accuracy
==================================================
Reads: benchmark/results/exp3_1_classification.csv
           benchmark/results/exp3_1_per_pair.csv (optional)

Produces:
  - Table  : accuracy / precision / recall / F1  per (method × difficulty)
  - Figure : grouped bar chart  (x=difficulty, y=accuracy, grouped by method)
  - Figure : heatmap             (method × difficulty, cell = F1 score)

Usage:
  python3 benchmark/scripts/analyze_exp3_1_accuracy.py \
      --input   benchmark/results/exp3_1_classification.csv \
      --output  benchmark/results
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
METHODS = ["GT_10K", "V1_MC", "V2_STRATIFIED", "V3_SPRT", "V4_BOUNDS", "V5_ADAPTIVE"]
DATASETS = ["easy", "medium", "hard", "mixed"]

METHOD_LABELS = {
    "GT_10K":        "GT (N=10k)",
    "V1_MC":         "M1: Monte Carlo",
    "V2_STRATIFIED": "M2: Stratified",
    "V3_SPRT":       "M3: SPRT",
    "V4_BOUNDS":     "M4: Bounds",
    "V5_ADAPTIVE":   "M5: Adaptive",
}

DATASET_LABELS = {
    "easy":   "Easy",
    "medium": "Medium",
    "hard":   "Hard",
    "mixed":  "Mixed",
}

COLORS = ["#95a5a6", "#3498db", "#2ecc71", "#f39c12", "#e74c3c", "#9b59b6"]
# ─────────────────────────────────────────────────────────────────────────────


def load_csv(path: str) -> dict:
    """Returns {method: {dataset: {metric: value}}}"""
    data: dict = defaultdict(lambda: defaultdict(dict))
    with open(path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            m  = row["Method"]
            ds = row["Dataset"]
            data[m][ds] = {
                "accuracy":  float(row["Accuracy"]),
                "precision": float(row["Precision"]),
                "recall":    float(row["Recall"]),
                "f1":        float(row["F1"]),
                "mae":       float(row["MAE"]),
                "rmse":      float(row["RMSE"]),
                "latency":   float(row["Latency_ms"]),
            }
    return data


def print_table(data: dict) -> None:
    """Print accuracy table to stdout."""
    col_w = 14
    header = f"{'Method':<18}" + "".join(f"{DATASET_LABELS.get(d,d):>{col_w}}" for d in DATASETS)
    print("\n── Accuracy (%) ──────────────────────────────────────────")
    print(header)
    print("─" * len(header))
    for m in METHODS:
        row = f"{METHOD_LABELS.get(m,m):<18}"
        for ds in DATASETS:
            acc = data.get(m, {}).get(ds, {}).get("accuracy", float("nan"))
            row += f"{acc*100:>{col_w}.1f}"
        print(row)

    print("\n── F1 Score ──────────────────────────────────────────────")
    print(header)
    print("─" * len(header))
    for m in METHODS:
        row = f"{METHOD_LABELS.get(m,m):<18}"
        for ds in DATASETS:
            f1 = data.get(m, {}).get(ds, {}).get("f1", float("nan"))
            row += f"{f1:>{col_w}.4f}"
        print(row)
    print()


# ─────────────────────────────────────────────────────────────────────────────
# Figure 1 — Grouped bar chart (accuracy per dataset, grouped by method)
# ─────────────────────────────────────────────────────────────────────────────
def plot_grouped_bar(data: dict, out_dir: Path) -> None:
    methods  = [m for m in METHODS if m in data]
    n_m      = len(methods)
    n_ds     = len(DATASETS)
    width    = 0.13
    x        = np.arange(n_ds)

    fig, ax = plt.subplots(figsize=(13, 6))
    for i, m in enumerate(methods):
        accs = [data[m].get(ds, {}).get("accuracy", 0) * 100 for ds in DATASETS]
        offset = (i - n_m / 2 + 0.5) * width
        bars = ax.bar(x + offset, accs, width,
                      label=METHOD_LABELS.get(m, m),
                      color=COLORS[i % len(COLORS)],
                      edgecolor="white", linewidth=0.5)

    ax.set_xlabel("Dataset Difficulty")
    ax.set_ylabel("Accuracy (%)")
    ax.set_title("Exp 3.1 — SimJoin Classification Accuracy by Method × Difficulty")
    ax.set_xticks(x)
    ax.set_xticklabels([DATASET_LABELS[d] for d in DATASETS])
    ax.set_ylim(0, 110)
    ax.axhline(100, color="gray", linestyle="--", linewidth=0.8, alpha=0.5)
    ax.legend(loc="lower left", ncol=3)
    ax.grid(axis="y", alpha=0.3)

    out = out_dir / "exp3_1_grouped_bar.png"
    fig.tight_layout()
    fig.savefig(out)
    print(f"Saved: {out}")
    plt.close(fig)


# ─────────────────────────────────────────────────────────────────────────────
# Figure 2 — F1 heatmap
# ─────────────────────────────────────────────────────────────────────────────
def plot_f1_heatmap(data: dict, out_dir: Path) -> None:
    methods  = [m for m in METHODS if m in data]
    matrix   = np.array([
        [data[m].get(ds, {}).get("f1", 0) for ds in DATASETS]
        for m in methods
    ])

    fig, ax = plt.subplots(figsize=(8, len(methods) * 0.7 + 1.5))
    im = ax.imshow(matrix, cmap="RdYlGn", vmin=0.0, vmax=1.0, aspect="auto")

    ax.set_xticks(range(len(DATASETS)))
    ax.set_xticklabels([DATASET_LABELS[d] for d in DATASETS])
    ax.set_yticks(range(len(methods)))
    ax.set_yticklabels([METHOD_LABELS.get(m, m) for m in methods])
    ax.set_title("Exp 3.1 — F1 Score Heatmap (Method × Difficulty)")

    for i in range(len(methods)):
        for j in range(len(DATASETS)):
            ax.text(j, i, f"{matrix[i,j]:.3f}", ha="center", va="center",
                    fontsize=10, color="black" if 0.3 < matrix[i,j] < 0.8 else "white")

    plt.colorbar(im, ax=ax, label="F1 Score")
    fig.tight_layout()
    out = out_dir / "exp3_1_f1_heatmap.png"
    fig.savefig(out)
    print(f"Saved: {out}")
    plt.close(fig)


# ─────────────────────────────────────────────────────────────────────────────
# Figure 3 — Latency comparison (bar chart)
# ─────────────────────────────────────────────────────────────────────────────
def plot_latency(data: dict, out_dir: Path) -> None:
    methods  = [m for m in METHODS if m != "GT_10K" and m in data]
    n_m      = len(methods)
    x        = np.arange(len(DATASETS))
    width    = 0.15

    fig, ax = plt.subplots(figsize=(12, 5))
    offset_base = -(n_m - 1) / 2 * width
    for i, m in enumerate(methods):
        lats = [data[m].get(ds, {}).get("latency", 0) for ds in DATASETS]
        ax.bar(x + offset_base + i * width, lats, width,
               label=METHOD_LABELS.get(m, m),
               color=COLORS[(i+1) % len(COLORS)],
               edgecolor="white")

    ax.set_xlabel("Dataset Difficulty")
    ax.set_ylabel("Latency per Pair (ms)")
    ax.set_title("Exp 3.1 — Per-Pair Latency by Method × Difficulty")
    ax.set_xticks(x)
    ax.set_xticklabels([DATASET_LABELS[d] for d in DATASETS])
    ax.legend(ncol=2)
    ax.grid(axis="y", alpha=0.3)

    out = out_dir / "exp3_1_latency.png"
    fig.tight_layout()
    fig.savefig(out)
    print(f"Saved: {out}")
    plt.close(fig)


# ─────────────────────────────────────────────────────────────────────────────
def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input",  default="benchmark/results/exp3_1_classification.csv")
    ap.add_argument("--output", default="benchmark/results")
    args = ap.parse_args()

    csv_path = Path(args.input)
    out_dir  = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)

    if not csv_path.exists():
        print(f"ERROR: Input CSV not found: {csv_path}", file=sys.stderr)
        sys.exit(1)

    data = load_csv(str(csv_path))
    print_table(data)
    plot_grouped_bar(data, out_dir)
    plot_f1_heatmap(data, out_dir)
    plot_latency(data, out_dir)
    print("\nDone.")


if __name__ == "__main__":
    main()
