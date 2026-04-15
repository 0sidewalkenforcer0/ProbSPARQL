#!/usr/bin/env python3
"""
Exp 3.2 Analysis: Convergence — All Sampling Methods
=====================================================
Reads: benchmark/results/exp3_2_convergence_multimethod.csv

Produces:
  - Figure 1 : x = sample count (log), y = mean estimated JSD,
               one shaded line per method; horizontal dashed = GT reference
  - Figure 2 : x = sample count (log), y = Mean Absolute Error
  - Figure 3 : x = sample count (log), y = mean execution time (ms)

Also prints a numerical summary table.

Usage:
  python3 benchmark/scripts/Experiments3/analyze_exp3_2_convergence.py \
      --input   benchmark/results/exp3_full/exp3_2/exp3_2_convergence_multimethod.csv \
      --output  benchmark/results/exp3_full/exp3_2/
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
METHOD_LABELS = {
    "V1_MC":         "M1: Monte Carlo",
    "V2_STRATIFIED": "M2: Stratified",
    "V3_SPRT":       "M3: SPRT",
    "V4_BOUNDS":     "M4: Bounds",
    "V5_ADAPTIVE":   "M5: Adaptive",
}
COLORS = ["#3498db", "#2ecc71", "#f39c12", "#e74c3c", "#9b59b6"]
# ─────────────────────────────────────────────────────────────────────────────


def load_csv(path: str) -> dict:
    """Returns {method: {samples: {'jsd': [...], 'err': [...], 'time': [...]}}}"""
    data: dict = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
    with open(path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            m  = row["Method"]
            n  = int(row["Samples"])
            data[m][n]["jsd"].append(float(row["EstJSD"]))
            data[m][n]["err"].append(float(row["AbsError"]))
            data[m][n]["time"].append(float(row["Time_ms"]))
    return data


def compute_stats(data: dict) -> dict:
    """Aggregate to method → samples → {mean_jsd, std_jsd, mean_err, std_err, mean_time}"""
    stats: dict = {}
    for m, by_n in data.items():
        stats[m] = {}
        for n, vals in sorted(by_n.items()):
            stats[m][n] = {
                "mean_jsd":  np.mean(vals["jsd"]),
                "std_jsd":   np.std(vals["jsd"]),
                "mean_err":  np.mean(vals["err"]),
                "std_err":   np.std(vals["err"]),
                "mean_time": np.mean(vals["time"]),
                "std_time":  np.std(vals["time"]),
            }
    return stats


def get_gt_reference(data: dict) -> float:
    """Estimate GT by finding largest sample count across all methods."""
    best_n, best_mean = 0, None
    for m, by_n in data.items():
        for n, vals in by_n.items():
            if n > best_n:
                best_n = n
                best_mean = np.mean(vals["jsd"])
    return best_mean


def print_summary(stats: dict, gt: float) -> None:
    print(f"\n── Convergence summary  (GT ≈ {gt:.6f}) ──────────────────────────────")
    methods = [m for m in METHODS if m in stats]
    sample_counts = sorted(next(iter(stats.values())).keys())
    print(f"{'Method':<18} " + " ".join(f"N={n:>7}" for n in sample_counts))
    print("─" * (18 + 10 * len(sample_counts) + 4))
    print("  [MAE]")
    for m in methods:
        row = f"{METHOD_LABELS.get(m,m):<18} "
        for n in sample_counts:
            err = stats[m].get(n, {}).get("mean_err", float("nan"))
            row += f"{err:>10.6f}"
        print(row)

    print("\n  [Time ms/call]")
    for m in methods:
        row = f"{METHOD_LABELS.get(m,m):<18} "
        for n in sample_counts:
            t = stats[m].get(n, {}).get("mean_time", float("nan"))
            row += f"{t:>10.2f}"
        print(row)
    print()


# ─────────────────────────────────────────────────────────────────────────────
def _plot_shared(stats: dict, gt: float, metric: str, ylabel: str,
                 title: str, out_path: Path) -> None:
    """Shared helper for JSD and MAE convergence plots."""
    methods = [m for m in METHODS if m in stats]
    fig, ax = plt.subplots(figsize=(10, 6))

    for i, m in enumerate(methods):
        ns    = sorted(stats[m].keys())
        means = [stats[m][n][f"mean_{metric}"] for n in ns]
        stds  = [stats[m][n][f"std_{metric}"]  for n in ns]
        means = np.array(means)
        stds  = np.array(stds)
        color = COLORS[i % len(COLORS)]
        ax.plot(ns, means, "-o", color=color, label=METHOD_LABELS.get(m, m),
                linewidth=2, markersize=5)
        ax.fill_between(ns, means - stds, means + stds,
                        color=color, alpha=0.12)

    if metric == "jsd" and gt is not None:
        ax.axhline(gt, color="black", linestyle="--", linewidth=1.2,
                   label=f"GT reference ({gt:.4f})")

    ax.set_xscale("log")
    ax.set_xlabel("Number of Samples (log scale)")
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.legend(loc="best")
    ax.grid(True, alpha=0.3)

    fig.tight_layout()
    fig.savefig(out_path)
    print(f"Saved: {out_path}")
    plt.close(fig)


def plot_jsd_convergence(stats: dict, gt: float, out_dir: Path) -> None:
    _plot_shared(stats, gt, "jsd", "Estimated JSD",
                 "Exp 3.2 — JSD Convergence by Sample Count",
                 out_dir / "exp3_2_jsd_convergence.png")


def plot_mae_convergence(stats: dict, gt: float, out_dir: Path) -> None:
    _plot_shared(stats, None, "err", "Absolute Error |est - GT|",
                 "Exp 3.2 — Convergence Error by Sample Count",
                 out_dir / "exp3_2_mae_convergence.png")


def plot_time(stats: dict, out_dir: Path) -> None:
    methods = [m for m in METHODS if m in stats]
    fig, ax = plt.subplots(figsize=(10, 5))
    for i, m in enumerate(methods):
        ns    = sorted(stats[m].keys())
        times = [stats[m][n]["mean_time"] for n in ns]
        ax.plot(ns, times, "-o", color=COLORS[i % len(COLORS)],
                label=METHOD_LABELS.get(m, m), linewidth=2, markersize=5)
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlabel("Number of Samples (log scale)")
    ax.set_ylabel("Execution Time (ms, log scale)")
    ax.set_title("Exp 3.2 — Computation Time vs. Sample Count")
    ax.legend()
    ax.grid(True, alpha=0.3, which="both")
    fig.tight_layout()
    out = out_dir / "exp3_2_time.png"
    fig.savefig(out)
    print(f"Saved: {out}")
    plt.close(fig)


# ─────────────────────────────────────────────────────────────────────────────
def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input",  default="benchmark/results/exp3_2_convergence_multimethod.csv")
    ap.add_argument("--output", default="benchmark/results")
    args = ap.parse_args()

    csv_path = Path(args.input)
    out_dir  = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)

    if not csv_path.exists():
        print(f"ERROR: Input CSV not found: {csv_path}", file=sys.stderr)
        sys.exit(1)

    data  = load_csv(str(csv_path))
    stats = compute_stats(data)
    gt    = get_gt_reference(data)

    print_summary(stats, gt)
    plot_jsd_convergence(stats, gt, out_dir)
    plot_mae_convergence(stats, gt, out_dir)
    plot_time(stats, out_dir)
    print("Done.")


if __name__ == "__main__":
    main()
