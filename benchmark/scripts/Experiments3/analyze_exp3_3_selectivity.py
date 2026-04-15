#!/usr/bin/env python3
"""
Exp 3.3 Analysis: Selectivity Sensitivity
==========================================
Reads: benchmark/results/exp3_3_selectivity.csv

Produces:
  - Figure 1 : x = θ, y = median execution time (ms)  [one line per method]
  - Figure 2 : x = θ, y = result set size              [one line per method + dataset]
  - Figure 3 : x = θ, y = accuracy                     [one line per method]
  - Figure 4 : x = θ, y = F1 score                     [one line per method]

Also prints a summary table.

Usage:
  python3 benchmark/scripts/Experiments3/analyze_exp3_3_selectivity.py \
      --input   benchmark/results/exp3_3_selectivity.csv \
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
METHODS = ["V1_MC", "V2_STRATIFIED", "V3_SPRT", "V4_BOUNDS", "V5_ADAPTIVE"]
DATASETS = ["easy", "medium", "hard", "mixed"]

METHOD_LABELS = {
    "V1_MC":         "M1: Monte Carlo",
    "V2_STRATIFIED": "M2: Stratified",
    "V3_SPRT":       "M3: SPRT",
    "V4_BOUNDS":     "M4: Bounds",
    "V5_ADAPTIVE":   "M5: Adaptive",
}
DATASET_LABELS = {
    "easy": "Easy", "medium": "Medium", "hard": "Hard", "mixed": "Mixed",
}
COLORS = ["#3498db", "#2ecc71", "#f39c12", "#e74c3c", "#9b59b6"]
LINE_STYLES = ["-", "--", "-.", ":", "-"]
# ─────────────────────────────────────────────────────────────────────────────


def load_csv(path: str) -> dict:
    """Returns {method: {dataset: {theta: row_dict}}}"""
    data: dict = defaultdict(lambda: defaultdict(dict))
    with open(path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            m   = row["Method"]
            ds  = row["Dataset"]
            th  = float(row["Theta"])
            data[m][ds][th] = {
                "time":    float(row["Median_ms"]),
                "std":     float(row["StdDev_ms"]),
                "count":   int(row["ResultCount"]),
                "total":   int(row["PairsTotal"]),
                "acc":     float(row["Accuracy"]),
                "pre":     float(row["Precision"]),
                "rec":     float(row["Recall"]),
                "f1":      float(row["F1"]),
            }
    return data


def print_summary(data: dict) -> None:
    thetas = sorted({t for m in data.values() for ds in m.values() for t in ds})
    print(f"\n── Exp 3.3 Selectivity Summary (dataset=mixed) ──────────────────────")
    print("  [Latency ms]")
    print(f"  {'Method':<18} " + " ".join(f"θ={t:.2f}" for t in thetas))
    for m in METHODS:
        if m not in data: continue
        row = f"  {METHOD_LABELS.get(m,m):<18} "
        for t in thetas:
            v = data[m].get("mixed",{}).get(t, {}).get("time", float("nan"))
            row += f"{v:>8.2f}"
        print(row)

    print("\n  [Accuracy]")
    print(f"  {'Method':<18} " + " ".join(f"θ={t:.2f}" for t in thetas))
    for m in METHODS:
        if m not in data: continue
        row = f"  {METHOD_LABELS.get(m,m):<18} "
        for t in thetas:
            v = data[m].get("mixed",{}).get(t, {}).get("acc", float("nan"))
            row += f"{v:>8.3f}"
        print(row)
    print()


# ─────────────────────────────────────────────────────────────────────────────
# Figure 1 — Latency vs θ
# ─────────────────────────────────────────────────────────────────────────────
def plot_latency_vs_theta(data: dict, out_dir: Path, dataset: str = "mixed") -> None:
    fig, ax = plt.subplots(figsize=(10, 6))
    methods = [m for m in METHODS if m in data]
    for i, m in enumerate(methods):
        if dataset not in data[m]: continue
        thetas = sorted(data[m][dataset].keys())
        times  = [data[m][dataset][t]["time"] for t in thetas]
        stds   = [data[m][dataset][t]["std"]  for t in thetas]
        ax.plot(thetas, times, LINE_STYLES[i], marker="o",
                color=COLORS[i % len(COLORS)],
                label=METHOD_LABELS.get(m, m), linewidth=2, markersize=6)
        ax.fill_between(thetas,
                        [t-s for t,s in zip(times, stds)],
                        [t+s for t,s in zip(times, stds)],
                        color=COLORS[i % len(COLORS)], alpha=0.1)

    ax.set_xlabel("Similarity Threshold θ")
    ax.set_ylabel("Median Execution Time (ms)")
    ax.set_title(f"Exp 3.3 — Latency vs. θ  [{DATASET_LABELS.get(dataset,dataset)} dataset]")
    ax.legend()
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    out = out_dir / f"exp3_3_latency_theta_{dataset}.png"
    fig.savefig(out);  print(f"Saved: {out}")
    plt.close(fig)


# ─────────────────────────────────────────────────────────────────────────────
# Figure 2 — Result count vs θ (selectivity curve)
# ─────────────────────────────────────────────────────────────────────────────
def plot_result_count(data: dict, out_dir: Path) -> None:
    fig, axes = plt.subplots(2, 2, figsize=(14, 10), sharex=True)
    axes = axes.flatten()

    for ax_idx, ds in enumerate(DATASETS):
        ax = axes[ax_idx]
        methods = [m for m in METHODS if m in data and ds in data[m]]
        for i, m in enumerate(methods):
            thetas = sorted(data[m][ds].keys())
            total  = data[m][ds][thetas[0]]["total"] or 1
            counts = [data[m][ds][t]["count"] / total * 100 for t in thetas]
            ax.plot(thetas, counts, LINE_STYLES[i], marker="s",
                    color=COLORS[i % len(COLORS)],
                    label=METHOD_LABELS.get(m, m), linewidth=2, markersize=5)

        ax.set_title(DATASET_LABELS.get(ds, ds))
        ax.set_ylabel("% Pairs Returned")
        ax.set_xlabel("Threshold θ")
        ax.set_ylim(-2, 105)
        ax.grid(True, alpha=0.3)
        if ax_idx == 0:
            ax.legend(fontsize=9, ncol=2)

    fig.suptitle("Exp 3.3 — Selectivity: % Pairs Returned vs. θ", fontsize=14)
    fig.tight_layout()
    out = out_dir / "exp3_3_result_count.png"
    fig.savefig(out);  print(f"Saved: {out}")
    plt.close(fig)


# ─────────────────────────────────────────────────────────────────────────────
# Figure 3 — Accuracy vs θ
# ─────────────────────────────────────────────────────────────────────────────
def plot_accuracy_vs_theta(data: dict, out_dir: Path, dataset: str = "mixed") -> None:
    fig, ax = plt.subplots(figsize=(10, 6))
    methods = [m for m in METHODS if m in data]
    for i, m in enumerate(methods):
        if dataset not in data[m]: continue
        thetas = sorted(data[m][dataset].keys())
        accs   = [data[m][dataset][t]["acc"] for t in thetas]
        ax.plot(thetas, accs, LINE_STYLES[i], marker="o",
                color=COLORS[i % len(COLORS)],
                label=METHOD_LABELS.get(m, m), linewidth=2, markersize=6)

    ax.set_xlabel("Similarity Threshold θ")
    ax.set_ylabel("Accuracy")
    ax.set_ylim(0.0, 1.05)
    ax.set_title(f"Exp 3.3 — Classification Accuracy vs. θ  [{DATASET_LABELS.get(dataset,dataset)}]")
    ax.legend()
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    out = out_dir / f"exp3_3_accuracy_theta_{dataset}.png"
    fig.savefig(out);  print(f"Saved: {out}")
    plt.close(fig)


# ─────────────────────────────────────────────────────────────────────────────
# Figure 4 — F1 vs θ (per dataset, 4-panel)
# ─────────────────────────────────────────────────────────────────────────────
def plot_f1_vs_theta(data: dict, out_dir: Path) -> None:
    fig, axes = plt.subplots(2, 2, figsize=(14, 10), sharex=True, sharey=True)
    axes = axes.flatten()

    methods = [m for m in METHODS if m in data]
    for ax_idx, ds in enumerate(DATASETS):
        ax = axes[ax_idx]
        for i, m in enumerate(methods):
            if ds not in data[m]: continue
            thetas = sorted(data[m][ds].keys())
            f1s    = [data[m][ds][t]["f1"] for t in thetas]
            ax.plot(thetas, f1s, LINE_STYLES[i], marker="^",
                    color=COLORS[i % len(COLORS)],
                    label=METHOD_LABELS.get(m, m), linewidth=2, markersize=5)
        ax.set_title(DATASET_LABELS.get(ds, ds))
        ax.set_ylim(-0.05, 1.1)
        ax.set_ylabel("F1 Score")
        ax.set_xlabel("Threshold θ")
        ax.grid(True, alpha=0.3)
        if ax_idx == 0:
            ax.legend(fontsize=9)

    fig.suptitle("Exp 3.3 — F1 Score vs. θ by Dataset", fontsize=14)
    fig.tight_layout()
    out = out_dir / "exp3_3_f1_theta.png"
    fig.savefig(out);  print(f"Saved: {out}")
    plt.close(fig)


# ─────────────────────────────────────────────────────────────────────────────
def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input",   default="benchmark/results/exp3_3_selectivity.csv")
    ap.add_argument("--output",  default="benchmark/results")
    ap.add_argument("--dataset", default="mixed",
                    help="Primary dataset for single-panel plots (default: mixed)")
    args = ap.parse_args()

    csv_path = Path(args.input)
    out_dir  = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)

    if not csv_path.exists():
        print(f"ERROR: Input CSV not found: {csv_path}", file=sys.stderr)
        sys.exit(1)

    data = load_csv(str(csv_path))
    print_summary(data)
    plot_latency_vs_theta(data, out_dir, dataset=args.dataset)
    plot_result_count(data, out_dir)
    plot_accuracy_vs_theta(data, out_dir, dataset=args.dataset)
    plot_f1_vs_theta(data, out_dir)
    print("Done.")


if __name__ == "__main__":
    main()
