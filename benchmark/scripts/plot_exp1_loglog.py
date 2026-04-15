#!/usr/bin/env python3
"""
Publication-quality log-log scalability plot for Experiment 1.
5 lines: DET/PROB-Q4 (blue), DET/PROB-Q1 (green), PROB-Q3 (red).
"""

import pandas as pd
import matplotlib as mpl
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np
import os

# ── Publication style ───────────────────────────────────────────────
mpl.rcParams.update({
    "font.family":       "serif",
    "font.size":         10,
    "axes.linewidth":    0.8,
    "xtick.major.width": 0.8,
    "ytick.major.width": 0.8,
    "xtick.minor.width": 0.5,
    "ytick.minor.width": 0.5,
    "xtick.direction":   "in",
    "ytick.direction":   "in",
    "axes.spines.top":   False,
    "axes.spines.right": False,
})

SCALE_MAP = {"E1": 10, "E2": 50, "E3": 100, "E4": 500,
             "E5": 1000, "E6": 5000, "E7": 10000}

RESULTS_DIR = os.path.join(os.path.dirname(__file__),
                            "..", "results", "exp1", "main")
CSV_PATH  = os.path.join(RESULTS_DIR, "exp1_summary.csv")
OUT_PNG   = os.path.join(RESULTS_DIR, "exp1_chart_loglog.png")
OUT_PDF   = os.path.join(RESULTS_DIR, "exp1_chart_loglog.pdf")


def load_series(df, k_val, query, qtype):
    mask = (df["K"] == k_val) & (df["QueryID"] == query) & (df["Type"] == qtype)
    sub  = df[mask].copy()
    sub["n"] = sub["Scale"].map(SCALE_MAP)
    return sub.sort_values("n")["n"].values, sub.sort_values("n")["Median_ms"].values


def pow10_formatter(val, _pos):
    """Label only exact powers of 10 in $10^k$ notation."""
    if val <= 0:
        return ""
    exp = np.log10(val)
    if abs(exp - round(exp)) < 1e-6:
        return r"$10^{%d}$" % int(round(exp))
    return ""


def main():
    df = pd.read_csv(CSV_PATH)

    fig, ax = plt.subplots(figsize=(6.5, 4.8))

    # ── Colour palette ──────────────────────────────────────────────
    BLUE  = "#2166ac"
    GREEN = "#1a7d3a"
    RED   = "#c0392b"

    # (k_val, query, qtype, label, color, linestyle, marker)
    series = [
        ("DET", "Q4", "DET",  "DET \u2014 Retrieval",          BLUE,  "-",  "o"),
        ("3",   "Q4", "PROB", "PROB \u2014 Retrieval",          BLUE,  "--", "s"),
        ("DET", "Q1", "DET",  "DET \u2014 Prob. Filtering",     GREEN, "-",  "^"),
        ("3",   "Q1", "PROB", "PROB \u2014 Prob. Filtering",    GREEN, "--", "D"),
        ("3",   "Q3", "PROB", "PROB \u2014 Distr. Comparison",  RED,   "--", "*"),
    ]

    for k_val, qid, qtype, label, color, ls, marker in series:
        ns, ms = load_series(df, k_val, qid, qtype)
        ax.plot(ns, ms,
                linestyle=ls, marker=marker, color=color,
                label=label, linewidth=1.5, markersize=4.5,
                markeredgewidth=0.7, zorder=3)

    # ── O(n) reference line (thin, light gray, solid) ───────────────
    xs = np.array([9, 16000])
    ref_anchor_n, ref_anchor_ms = 10.0, 0.018   # sits just below the blue bundle
    ys = ref_anchor_ms * (xs / ref_anchor_n)
    ax.plot(xs, ys, color="#bbbbbb", linestyle="-", linewidth=0.9, zorder=1)
    ax.text(8000, ref_anchor_ms * (8000 / ref_anchor_n) * 0.42,
            r"$O(n)$", color="#999999", fontsize=9,
            ha="center", va="top", style="italic")

    # ── Axes ───────────────────────────────────────────────────────
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlim(7, 22000)
    ax.set_ylim(0.01, 200000)

    ax.set_xlabel(r"Number of entities $n$", fontsize=11)
    ax.set_ylabel("Latency (ms)", fontsize=11)

    # Powers-of-10 ticks with $10^k$ labels
    ax.xaxis.set_major_locator(mticker.LogLocator(base=10, numticks=12))
    ax.xaxis.set_major_formatter(mticker.FuncFormatter(pow10_formatter))
    ax.xaxis.set_minor_locator(
        mticker.LogLocator(base=10, subs=np.arange(2, 10) * 0.1, numticks=50))
    ax.xaxis.set_minor_formatter(mticker.NullFormatter())

    ax.yaxis.set_major_locator(mticker.LogLocator(base=10, numticks=12))
    ax.yaxis.set_major_formatter(mticker.FuncFormatter(pow10_formatter))
    ax.yaxis.set_minor_locator(
        mticker.LogLocator(base=10, subs=np.arange(2, 10) * 0.1, numticks=50))
    ax.yaxis.set_minor_formatter(mticker.NullFormatter())

    # Minimal dotted light-gray grid on major ticks only
    ax.grid(True,  which="major", linestyle=":", linewidth=0.45, color="#cccccc")
    ax.grid(False, which="minor")

    # ── Legend ─────────────────────────────────────────────────────
    leg = ax.legend(
        fontsize=8.5, loc="upper left",
        framealpha=0.78, edgecolor="#aaaaaa",
        fancybox=False, borderpad=0.8,
        handlelength=2.2, handletextpad=0.6,
        labelspacing=0.4,
    )
    leg.get_frame().set_linewidth(0.6)

    fig.tight_layout(pad=0.4)

    # Save raster (preview) and vector (publication)
    plt.savefig(OUT_PNG, dpi=300, bbox_inches="tight", facecolor="white")
    plt.savefig(OUT_PDF, bbox_inches="tight", facecolor="white")
    print(f"Saved → {OUT_PNG}")
    print(f"Saved → {OUT_PDF}")


if __name__ == "__main__":
    main()
