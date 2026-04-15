#!/usr/bin/env python3
"""
Exp 1 — Publication-quality figures and LaTeX table
====================================================
Generates:
  exp1_fig1_scalability.png  — log-linear scalability, all queries (2×2)
  exp1_fig2_overhead.png     — overhead ratio vs scale + Q3 log-log (1×2)
  exp1_fig3_k_sensitivity.png— latency vs K across all scales (2×2 per query)
  exp1_table_latex.tex       — complete LaTeX table of median latencies

Usage
-----
  python benchmark/scripts/plot_exp1_paper.py
  python benchmark/scripts/plot_exp1_paper.py \
      --input  benchmark/results/exp1/main/exp1_raw.csv \
      --output benchmark/results/exp1/main
"""
import argparse
import os
import sys

try:
    import numpy as np
    import pandas as pd
except ImportError as e:
    print(f"ERROR: {e}\npip install pandas numpy matplotlib")
    sys.exit(1)

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.ticker as mticker
    from matplotlib.gridspec import GridSpec
except ImportError:
    print("ERROR: matplotlib not found — pip install matplotlib")
    sys.exit(1)

# ── colour palette (colorblind-safe IBM palette) ──────────────────────────────
C = {
    "DET": "#648FFF",   # blue
    1:     "#785EF0",   # purple
    3:     "#DC267F",   # magenta
    5:     "#FE6100",   # orange
    10:    "#FFB000",   # gold
}
MARKERS = {"DET": "s", 1: "o", 3: "^", 5: "D", 10: "v"}
LINE_W  = 1.8

# ── scales ────────────────────────────────────────────────────────────────────
SCALE_GEAR = {"E1": 10, "E2": 50, "E3": 100, "E4": 500,
              "E5": 1000, "E6": 5000, "E7": 10000}
SCALE_ORDER = ["E1", "E2", "E3", "E4", "E5", "E6", "E7"]
GEAR_TICKS  = [SCALE_GEAR[s] for s in SCALE_ORDER]
K_VALUES    = [1, 3, 5, 10]
QUERIES     = ["Q1", "Q2", "Q3", "Q4"]
QLABELS     = {
    "Q1": "Q1: Retrieval",
    "Q2": "Q2: CDF + Scalar Filter",
    "Q3": "Q3: Distribution Arithmetic",
    "Q4": "Q4: JS-Divergence Comparison",
}

# ── matplotlib global style ───────────────────────────────────────────────────
plt.rcParams.update({
    "font.family":        "DejaVu Sans",
    "font.size":          9,
    "axes.titlesize":     10,
    "axes.labelsize":     9,
    "legend.fontsize":    8,
    "xtick.labelsize":    8,
    "ytick.labelsize":    8,
    "axes.spines.top":    False,
    "axes.spines.right":  False,
    "axes.grid":          True,
    "grid.alpha":         0.25,
    "grid.linestyle":     "--",
    "figure.dpi":         150,
    "savefig.bbox":       "tight",
    "savefig.dpi":        200,
})


# ── data loading ──────────────────────────────────────────────────────────────

def load(path: str) -> pd.DataFrame:
    if not os.path.exists(path):
        print(f"ERROR: {path!r} not found — run ScalabilityBenchmark first.")
        sys.exit(1)
    df = pd.read_csv(path)
    df["Time_ms"] = pd.to_numeric(df["Time_ms"], errors="coerce")
    return df


def aggregate(df: pd.DataFrame) -> pd.DataFrame:
    grp = df.groupby(["Scale", "K", "QueryID", "Type"])["Time_ms"]
    n   = grp.count().values[0] if len(grp) else 1
    agg = pd.DataFrame({
        "Median_ms": grp.median(),
        "IQR_ms":    grp.apply(lambda x: np.percentile(x, 75) - np.percentile(x, 25))
                       if n > 1 else grp.apply(lambda x: 0.0),
        "Count":     grp.count(),
    }).reset_index()
    return agg


def get_det(agg: pd.DataFrame, scale: str, qid: str) -> float:
    r = agg[(agg["Scale"] == scale) & (agg["QueryID"] == qid) & (agg["Type"] == "DET")]
    return float(r["Median_ms"].values[0]) if len(r) else float("nan")


def get_prob(agg: pd.DataFrame, scale: str, qid: str, k: int) -> float:
    r = agg[(agg["Scale"] == scale) & (agg["QueryID"] == qid)
            & (agg["Type"] == "PROB") & (agg["K"] == str(k))]
    return float(r["Median_ms"].values[0]) if len(r) else float("nan")


# ── Figure 1: Scalability (2×2, log x-axis) ──────────────────────────────────

def fig1_scalability(agg: pd.DataFrame, out: str) -> None:
    fig, axes = plt.subplots(2, 2, figsize=(13, 9), sharex=False)
    axes = axes.flatten()

    for ax, qid in zip(axes, QUERIES):
        # DET baseline (not applicable to Q4)
        if qid != "Q4":
            ys = [get_det(agg, s, qid) for s in SCALE_ORDER]
            ax.plot(GEAR_TICKS, ys, marker=MARKERS["DET"], color=C["DET"],
                    linestyle="--", linewidth=LINE_W, label="DET (no prob)", zorder=5)

        # PROB lines for each K
        for k in K_VALUES:
            ys = [get_prob(agg, s, qid, k) for s in SCALE_ORDER]
            ax.plot(GEAR_TICKS, ys, marker=MARKERS[k], color=C[k],
                    linestyle="-", linewidth=LINE_W, label=f"K={k}", zorder=4)

        ax.set_xscale("log")
        ax.set_xticks(GEAR_TICKS)
        ax.xaxis.set_major_formatter(mticker.ScalarFormatter())
        ax.set_xlabel("Number of gears (log scale)", fontsize=9)
        ax.set_ylabel("Median latency (ms)", fontsize=9)
        ax.set_title(QLABELS[qid], fontweight="bold")
        ax.legend(loc="upper left", framealpha=0.7)

        # annotate E6/E7 with gear count
        for s in ["E6", "E7"]:
            g = SCALE_GEAR[s]
            for k in K_VALUES:
                v = get_prob(agg, s, qid, k)
                if not np.isnan(v):
                    ax.annotate(f"{v/1000:.1f}s" if v >= 1000 else f"{v:.0f}ms",
                                xy=(g, v), xytext=(3, 3),
                                textcoords="offset points", fontsize=6.5, color=C[k],
                                ha="left")

    fig.suptitle("Experiment 1 — System Overhead: Scalability across E1–E7",
                 fontsize=12, fontweight="bold", y=1.01)
    fig.tight_layout()
    p = os.path.join(out, "exp1_fig1_scalability.png")
    fig.savefig(p)
    plt.close(fig)
    print(f"  Wrote {p}")


# ── Figure 2: Overhead ratio + Q3 log-log ────────────────────────────────────

def fig2_overhead(agg: pd.DataFrame, out: str) -> None:
    fig = plt.figure(figsize=(14, 5.5))
    gs  = GridSpec(1, 2, figure=fig, wspace=0.32)

    # ---- left: overhead ratio vs scale for Q1, Q2, Q3 at K=3 ---------------
    ax_l = fig.add_subplot(gs[0, 0])
    q_colors = {"Q1": "#E41A1C", "Q2": "#377EB8", "Q3": "#4DAF4A"}
    for qid in ["Q1", "Q2", "Q3"]:
        ratios = []
        for s in SCALE_ORDER:
            det  = get_det(agg, s, qid)
            prob = get_prob(agg, s, qid, 3)
            ratios.append(prob / det if det > 0 else float("nan"))
        ax_l.plot(GEAR_TICKS, ratios, marker="o", color=q_colors[qid],
                  linewidth=LINE_W, label=QLABELS[qid])

    ax_l.axhline(1.0, color="black", linestyle="--", linewidth=1.0,
                 label="1× (no overhead)")
    ax_l.set_xscale("log")
    ax_l.set_xticks(GEAR_TICKS)
    ax_l.xaxis.set_major_formatter(mticker.ScalarFormatter())
    ax_l.set_xlabel("Number of gears (log scale)")
    ax_l.set_ylabel("Overhead ratio  PROB / DET  [K = 3]")
    ax_l.set_title("Overhead Ratio vs. Graph Size  (K=3)", fontweight="bold")
    ax_l.legend(loc="upper right", framealpha=0.7)
    ax_l.set_ylim(bottom=0)

    # ---- right: Q4 JSD absolute latency on log-log ---------------------------
    ax_r = fig.add_subplot(gs[0, 1])
    for k in K_VALUES:
        ys = [get_prob(agg, s, "Q4", k) for s in SCALE_ORDER]
        ax_r.plot(GEAR_TICKS, ys, marker=MARKERS[k], color=C[k],
                  linewidth=LINE_W, label=f"K={k}")

    ax_r.set_xscale("log")
    ax_r.set_yscale("log")
    ax_r.set_xticks(GEAR_TICKS)
    ax_r.xaxis.set_major_formatter(mticker.ScalarFormatter())
    ax_r.yaxis.set_minor_formatter(mticker.NullFormatter())
    ax_r.set_xlabel("Number of gears (log scale)")
    ax_r.set_ylabel("Absolute latency (ms, log scale)")
    ax_r.set_title("Q4: JS-Divergence — log-log Scaling", fontweight="bold")
    ax_r.legend(loc="upper left", framealpha=0.7)

    # fit power-law annotation for K=1 (reference line)
    xs = np.array(GEAR_TICKS, dtype=float)
    ys = np.array([get_prob(agg, s, "Q4", 1) for s in SCALE_ORDER])
    valid = ~np.isnan(ys) & (ys > 0)
    if valid.sum() >= 3:
        coeffs = np.polyfit(np.log10(xs[valid]), np.log10(ys[valid]), 1)
        slope  = coeffs[0]
        fit_ys = 10 ** np.polyval(coeffs, np.log10(xs))
        ax_r.plot(xs, fit_ys, linestyle=":", color=C[1], linewidth=1.0, alpha=0.7)
        ax_r.text(0.05, 0.95, f"K=1 slope ≈ {slope:.2f}",
                  transform=ax_r.transAxes, fontsize=8, va="top",
                  color=C[1], bbox=dict(fc="white", ec="none", alpha=0.7))

    fig.suptitle("Experiment 1 — Overhead Analysis", fontsize=12,
                 fontweight="bold", y=1.02)
    fig.tight_layout()
    p = os.path.join(out, "exp1_fig2_overhead.png")
    fig.savefig(p)
    plt.close(fig)
    print(f"  Wrote {p}")


# ── Figure 3: K-sensitivity across all 7 scales ──────────────────────────────

def fig3_k_sensitivity(agg: pd.DataFrame, out: str) -> None:
    # Focus on Q2 and Q4 (filtering and comparison)
    focus_qids = ["Q2", "Q4"]
    scale_colors = {
        "E1": "#a6cee3", "E2": "#1f78b4", "E3": "#b2df8a",
        "E4": "#33a02c", "E5": "#fb9a99", "E6": "#e31a1c", "E7": "#6a3d9a",
    }

    fig, axes = plt.subplots(1, 2, figsize=(13, 5), sharey=False)

    for ax, qid in zip(axes, focus_qids):
        for s in SCALE_ORDER:
            ys = [get_prob(agg, s, qid, k) for k in K_VALUES]
            if any(not np.isnan(v) for v in ys):
                ax.plot(K_VALUES, ys, marker="o", color=scale_colors[s],
                        linewidth=LINE_W - 0.3,
                        label=f"{s} ({SCALE_GEAR[s]:,} gears)")
        ax.set_xticks(K_VALUES)
        ax.set_xlabel("K (GMM components)")
        ax.set_ylabel("Median latency (ms)")
        ax.set_title(QLABELS[qid], fontweight="bold")
        ax.legend(loc="upper left", fontsize=7.5, framealpha=0.7)
        if qid == "Q3":
            ax.set_yscale("log")
            ax.yaxis.set_minor_formatter(mticker.NullFormatter())

    fig.suptitle("Experiment 1 — K-Sensitivity (latency vs GMM components)",
                 fontsize=12, fontweight="bold", y=1.02)
    fig.tight_layout()
    p = os.path.join(out, "exp1_fig3_k_sensitivity.png")
    fig.savefig(p)
    plt.close(fig)
    print(f"  Wrote {p}")


# ── LaTeX table ───────────────────────────────────────────────────────────────

def write_latex_table(agg: pd.DataFrame, out: str) -> None:
    lines = []
    lines.append(r"\begin{table}[t]")
    lines.append(r"\centering")
    lines.append(r"\small")
    lines.append(r"\caption{Experiment 1 — Median query latency (ms) across scale "
                 r"and GMM complexity $K$. "
                 r"Q1--Q3 are measured on both deterministic (DET) and probabilistic (PROB) graphs; "
                 r"Q4 is probabilistic only (no DET baseline). "
                 r"Values $\geq$1\,s are formatted as \textbf{bold}.}")
    lines.append(r"\label{tab:exp1_latency}")
    lines.append(r"\setlength{\tabcolsep}{4pt}")

    # header
    lines.append(r"\begin{tabular}{lr|rrrr|rrrr|rrrr|r}")
    lines.append(r"\toprule")
    lines.append(r" & & \multicolumn{4}{c|}{Q1: Retrieval} "
                 r"& \multicolumn{4}{c|}{Q2: CDF + Filter} "
                 r"& \multicolumn{4}{c|}{Q3: Distrib.\ Arith.} "
                 r"& Q4: JSD \\")
    lines.append(r"\cmidrule(lr){3-6}\cmidrule(lr){7-10}\cmidrule(lr){11-14}\cmidrule(lr){15-15}")
    lines.append(r"Scale & Gears & DET & K=1 & K=3 & K=10 "
                 r"& DET & K=1 & K=3 & K=10 "
                 r"& DET & K=1 & K=3 & K=10 "
                 r"& K=3 \\")
    lines.append(r"\midrule")

    def fmt(v: float) -> str:
        if np.isnan(v):
            return "—"
        if v >= 1000:
            return r"\textbf{" + f"{v/1000:.1f}s" + r"}"
        return f"{v:.1f}"

    for s in SCALE_ORDER:
        cols = [s, f"{SCALE_GEAR[s]:,}"]
        for qid in ["Q1"]:
            cols.append(fmt(get_det(agg, s, qid)))
            for k in [1, 3, 10]:
                cols.append(fmt(get_prob(agg, s, qid, k)))
        for qid in ["Q2"]:
            cols.append(fmt(get_det(agg, s, qid)))
            for k in [1, 3, 10]:
                cols.append(fmt(get_prob(agg, s, qid, k)))
        for qid in ["Q3"]:
            cols.append(fmt(get_det(agg, s, qid)))
            for k in [1, 3, 10]:
                cols.append(fmt(get_prob(agg, s, qid, k)))
        # Q4 K=3 only
        cols.append(fmt(get_prob(agg, s, "Q4", 3)))
        lines.append(" & ".join(cols) + r" \\")

    lines.append(r"\bottomrule")
    lines.append(r"\end{tabular}")
    lines.append(r"\end{table}")

    p = os.path.join(out, "exp1_table_latex.tex")
    with open(p, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"  Wrote {p}")


# ── Figure 4: Summary table image ────────────────────────────────────────────

def fig4_table_image(agg: pd.DataFrame, out: str) -> None:
    col_labels = ["Scale", "Gears",
                  "Q1 DET", "Q1 K=1", "Q1 K=3", "Q1 K=5", "Q1 K=10",
                  "Q2 DET", "Q2 K=1", "Q2 K=3", "Q2 K=5", "Q2 K=10",
                  "Q3 DET", "Q3 K=1", "Q3 K=3", "Q3 K=5", "Q3 K=10",
                  "Q4 K=1", "Q4 K=3", "Q4 K=5", "Q4 K=10"]

    rows = []
    for s in SCALE_ORDER:
        g = SCALE_GEAR[s]
        row = [s, f"{g:,}"]
        for qid in ["Q1", "Q2"]:
            v = get_det(agg, s, qid)
            row.append(f"{v:.1f}" if not np.isnan(v) else "—")
            for k in K_VALUES:
                v = get_prob(agg, s, qid, k)
                row.append(f"{v:.1f}" if not np.isnan(v) else "—")
        for qid in ["Q3"]:
            v = get_det(agg, s, qid)
            row.append(f"{v:.1f}" if not np.isnan(v) else "—")
            for k in K_VALUES:
                v = get_prob(agg, s, qid, k)
                row.append(f"{v:.1f}" if not np.isnan(v) else "—")
        # Q4 — no DET
        for k in K_VALUES:
            v = get_prob(agg, s, "Q4", k)
            row.append(f"{v:.1f}" if not np.isnan(v) else "—")
        rows.append(row)

    # split into two half-tables to keep it readable
    fig, axes = plt.subplots(2, 1, figsize=(18, 5.5))

    splits = [
        (col_labels[:12], [r[:12] for r in rows], "Q1 & Q2 Latency (ms)"),
        (col_labels[:2] + col_labels[12:], [r[:2] + r[12:] for r in rows], "Q3 & Q4 Latency (ms)"),
    ]

    for ax, (cols, data, title) in zip(axes, splits):
        ax.axis("off")
        tbl = ax.table(cellText=data, colLabels=cols,
                       loc="center", cellLoc="center")
        tbl.auto_set_font_size(False)
        tbl.set_fontsize(7.5)
        tbl.scale(1, 1.4)

        # style header
        for j in range(len(cols)):
            tbl[(0, j)].set_facecolor("#2c3e50")
            tbl[(0, j)].set_text_props(color="white", fontweight="bold")

        # alternating row shading + highlight large values
        for i, row in enumerate(data, start=1):
            bg = "#f2f2f2" if i % 2 == 0 else "white"
            for j, val in enumerate(row):
                cell = tbl[(i, j)]
                cell.set_facecolor(bg)
                # orange highlight for ≥1s values
                try:
                    if float(val) >= 1000:
                        cell.set_facecolor("#ffe0b2")
                        cell.set_text_props(fontweight="bold")
                except Exception:
                    pass

        ax.set_title(title, fontweight="bold", pad=4, fontsize=9)

    fig.suptitle("Experiment 1 — Complete Latency Table (median ms, warmup+benchmark run)",
                 fontsize=10, fontweight="bold")
    fig.tight_layout()
    p = os.path.join(out, "exp1_fig4_table.png")
    fig.savefig(p)
    plt.close(fig)
    print(f"  Wrote {p}")


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    p = argparse.ArgumentParser(description="Exp 1 — Publication figures")
    p.add_argument("--input",  default="benchmark/results/exp1/main/exp1_raw.csv")
    p.add_argument("--output", default="benchmark/results/exp1/main")
    args = p.parse_args()

    os.makedirs(args.output, exist_ok=True)

    print(f"Loading: {args.input}")
    df  = load(args.input)
    agg = aggregate(df)

    print("\nGenerating publication figures...")
    fig1_scalability(agg, args.output)
    fig2_overhead(agg, args.output)
    fig3_k_sensitivity(agg, args.output)

    print("\nGenerating table image...")
    fig4_table_image(agg, args.output)

    print("\nGenerating LaTeX table...")
    write_latex_table(agg, args.output)

    print(f"\nAll outputs written to: {args.output}")
    print("Files:")
    for f in sorted(os.listdir(args.output)):
        fp = os.path.join(args.output, f)
        sz = os.path.getsize(fp)
        print(f"  {f:<40}  {sz/1024:6.1f} KB")


if __name__ == "__main__":
    main()
