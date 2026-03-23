#!/usr/bin/env python3
"""
Exp 1 Analysis: System Overhead — ProbSPARQL vs Deterministic SPARQL
=====================================================================
Reads ``exp1_raw.csv`` produced by ``ScalabilityBenchmark`` and generates:

Tables
------
  exp1_table1_Q{1-4}.csv   — Absolute latency (ms): rows=scale, cols=DET/K values
  exp1_table2_overhead.csv — Overhead ratios at K=3: rows=scale, cols=Q1..Q4

Charts
------
  exp1_chart1_scalability.png — Latency vs scale, one subplot per query
  exp1_chart2_complexity.png  — Latency vs K, one subplot per query (fixed to E3)
  exp1_chart3_overhead.png    — Overhead ratio grouped-bar, x=query, bars=K

Usage
-----
  python analyze_exp1_proper.py
  python analyze_exp1_proper.py --input benchmark/results/exp1/exp1_raw.csv \\
                                --output benchmark/results/exp1
"""
import argparse
import os
import sys

try:
    import numpy as np
    import pandas as pd
except ImportError as e:
    print(f"ERROR: {e}.  pip install pandas numpy")
    sys.exit(1)

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.ticker as mticker
    HAS_MPL = True
except ImportError:
    HAS_MPL = False
    print("WARNING: matplotlib not found — charts will be skipped.")

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

SCALE_GEAR_MAP = {"E1": 10, "E2": 50, "E3": 100, "E4": 500, "E5": 1000, "E6": 5000, "E7": 10000}
SCALE_ORDER    = ["E1", "E2", "E3", "E4", "E5", "E6", "E7"]
K_VALUES       = [1, 3, 5, 10]
QUERY_IDS      = ["Q1", "Q2", "Q3", "Q4"]
QUERY_LABELS   = {
    "Q1": "Q1: CDF Filter",
    "Q2": "Q2: Multiply+Mean",
    "Q3": "Q3: JSD (prob only)",
    "Q4": "Q4: Pure Traversal",
}

# ---------------------------------------------------------------------------
# Loading & aggregation
# ---------------------------------------------------------------------------

def load(path: str) -> pd.DataFrame:
    if not os.path.exists(path):
        print(f"ERROR: {path} not found — run ScalabilityBenchmark first.")
        sys.exit(1)
    df = pd.read_csv(path)
    df["Time_ms"] = pd.to_numeric(df["Time_ms"], errors="coerce")
    return df


def aggregate(df: pd.DataFrame) -> pd.DataFrame:
    """Compute median and IQR per (Scale, K, QueryID, Type) group."""
    grp = df.groupby(["Scale", "K", "QueryID", "Type"])["Time_ms"]
    agg = pd.DataFrame({
        "Median_ms": grp.median(),
        "IQR_ms":    grp.apply(lambda x: np.percentile(x, 75) - np.percentile(x, 25)),
        "Count":     grp.count(),
    }).reset_index()
    return agg


def compute_overhead(agg: pd.DataFrame) -> pd.DataFrame:
    """Add OverheadRatio column: Median_PROB / Median_DET (same Scale+QueryID).
    Q3 has no DET pair — ratio is NaN."""
    det = agg[agg["Type"] == "DET"][["Scale", "QueryID", "Median_ms"]].copy()
    det.rename(columns={"Median_ms": "DET_ms"}, inplace=True)
    merged = agg.merge(det, on=["Scale", "QueryID"], how="left")
    merged["OverheadRatio"] = merged.apply(
        lambda r: r["Median_ms"] / r["DET_ms"]
                  if r["Type"] == "PROB" and pd.notna(r["DET_ms"]) and r["DET_ms"] > 0
                  else (1.0 if r["Type"] == "DET" else float("nan")),
        axis=1,
    )
    return merged


# ---------------------------------------------------------------------------
# Table 1: absolute times per query
# ---------------------------------------------------------------------------

def write_table1(agg: pd.DataFrame, output_dir: str) -> None:
    """One CSV per query: rows = scale, columns = DET, K=1, K=3, K=5, K=10."""
    for qid in QUERY_IDS:
        sub = agg[agg["QueryID"] == qid].copy()

        rows = []
        for scale in SCALE_ORDER:
            gears = SCALE_GEAR_MAP.get(scale, "?")
            row = {"Scale": scale, "Gears": gears}

            # DET column (only for Q1, Q2, Q4)
            det_row = sub[(sub["Scale"] == scale) & (sub["Type"] == "DET")]
            row["DET (ms)"] = f"{det_row['Median_ms'].values[0]:.3f}" if len(det_row) else "—"

            # K columns (prob only)
            for k in K_VALUES:
                prob_row = sub[(sub["Scale"] == scale) & (sub["K"] == str(k)) & (sub["Type"] == "PROB")]
                row[f"K={k} (ms)"] = f"{prob_row['Median_ms'].values[0]:.3f}" if len(prob_row) else "—"

            rows.append(row)

        t1 = pd.DataFrame(rows)
        path = os.path.join(output_dir, f"exp1_table1_{qid}.csv")
        t1.to_csv(path, index=False)
        print(f"  Wrote {path}")


# ---------------------------------------------------------------------------
# Table 2: overhead ratios at K=3
# ---------------------------------------------------------------------------

def write_table2(merged: pd.DataFrame, output_dir: str) -> None:
    """Rows = scale, cols = Q1..Q4 overhead ratio (at K=3).
    Q3: show absolute time (no ratio)."""
    k_ref = "3"
    rows = []
    for scale in SCALE_ORDER:
        gears = SCALE_GEAR_MAP.get(scale, "?")
        row = {"Scale": scale, "Gears": gears}
        for qid in QUERY_IDS:
            if qid == "Q3":
                # absolute time
                sub = merged[(merged["Scale"] == scale) & (merged["K"] == k_ref)
                             & (merged["QueryID"] == "Q3") & (merged["Type"] == "PROB")]
                row["Q3 abs (ms)"] = f"{sub['Median_ms'].values[0]:.3f}" if len(sub) else "—"
            else:
                sub = merged[(merged["Scale"] == scale) & (merged["K"] == k_ref)
                             & (merged["QueryID"] == qid) & (merged["Type"] == "PROB")]
                if len(sub) and pd.notna(sub["OverheadRatio"].values[0]):
                    row[f"{qid} ratio"] = f"{sub['OverheadRatio'].values[0]:.2f}×"
                else:
                    row[f"{qid} ratio"] = "—"
        rows.append(row)

    t2 = pd.DataFrame(rows)
    path = os.path.join(output_dir, "exp1_table2_overhead.csv")
    t2.to_csv(path, index=False)
    print(f"  Wrote {path}")


# ---------------------------------------------------------------------------
# Chart 1: Scalability (latency vs scale)
# ---------------------------------------------------------------------------

def plot_chart1(merged: pd.DataFrame, output_dir: str) -> None:
    if not HAS_MPL:
        return

    gear_counts = [SCALE_GEAR_MAP[s] for s in SCALE_ORDER if s in SCALE_GEAR_MAP]

    fig, axes = plt.subplots(2, 2, figsize=(12, 9), sharex=False)
    axes = axes.flatten()

    colors = {"DET": "#555555", 1: "#3498db", 3: "#2ecc71", 5: "#f39c12", 10: "#e74c3c"}
    markers = {"DET": "s", 1: "o", 3: "^", 5: "D", 10: "v"}

    for ax, qid in zip(axes, QUERY_IDS):
        # DET line (not for Q3)
        if qid != "Q3":
            det = merged[(merged["QueryID"] == qid) & (merged["Type"] == "DET")]
            ys = [det[det["Scale"] == s]["Median_ms"].values[0]
                  if len(det[det["Scale"] == s]) else float("nan")
                  for s in SCALE_ORDER]
            ax.plot(gear_counts, ys, marker=markers["DET"], color=colors["DET"],
                    linestyle="--", label="DET", linewidth=1.5)

        for k in K_VALUES:
            sub = merged[(merged["QueryID"] == qid) & (merged["Type"] == "PROB")
                         & (merged["K"] == str(k))]
            ys = [sub[sub["Scale"] == s]["Median_ms"].values[0]
                  if len(sub[sub["Scale"] == s]) else float("nan")
                  for s in SCALE_ORDER]
            ax.plot(gear_counts, ys, marker=markers[k], color=colors[k],
                    linestyle="-", label=f"K={k}", linewidth=1.5)

        ax.set_xscale("log")
        ax.set_xlabel("Number of gears (log scale)", fontsize=9)
        ax.set_ylabel("Median latency (ms)", fontsize=9)
        ax.set_title(QUERY_LABELS[qid], fontsize=10)
        ax.legend(fontsize=8)
        ax.xaxis.set_major_formatter(mticker.ScalarFormatter())
        ax.set_xticks(gear_counts)
        ax.tick_params(axis="both", labelsize=8)
        ax.grid(True, which="both", alpha=0.3)

    fig.suptitle("Exp 1 — Chart 1: Scalability (latency vs graph size)", fontsize=12)
    fig.tight_layout()
    path = os.path.join(output_dir, "exp1_chart1_scalability.png")
    fig.savefig(path, dpi=150)
    plt.close(fig)
    print(f"  Wrote {path}")


# ---------------------------------------------------------------------------
# Chart 2: K complexity (latency vs K, fixed scale=E3)
# ---------------------------------------------------------------------------

def plot_chart2(merged: pd.DataFrame, output_dir: str) -> None:
    if not HAS_MPL:
        return

    fixed_scale = "E3"
    colors_s = {"E1": "#1abc9c", "E2": "#3498db", "E3": "#9b59b6",
                "E4": "#f39c12", "E5": "#e74c3c"}

    fig, axes = plt.subplots(2, 2, figsize=(12, 9), sharex=True)
    axes = axes.flatten()

    for ax, qid in zip(axes, QUERY_IDS):
        sub = merged[(merged["QueryID"] == qid) & (merged["Type"] == "PROB")
                     & (merged["Scale"] == fixed_scale)]
        ks  = [str(k) for k in K_VALUES]
        ys  = [sub[sub["K"] == k]["Median_ms"].values[0]
               if len(sub[sub["K"] == k]) else float("nan")
               for k in ks]

        ax.plot(K_VALUES, ys, marker="o", color=colors_s[fixed_scale],
                linestyle="-", linewidth=1.8, label=fixed_scale)

        if qid != "Q3":
            # Also show DET as horizontal dashed reference
            det_sub = merged[(merged["QueryID"] == qid) & (merged["Type"] == "DET")
                             & (merged["Scale"] == fixed_scale)]
            if len(det_sub):
                det_ms = det_sub["Median_ms"].values[0]
                ax.axhline(det_ms, color="#555555", linestyle="--",
                           linewidth=1.2, label="DET")

        ax.set_xticks(K_VALUES)
        ax.set_xlabel("K (GMM components)", fontsize=9)
        ax.set_ylabel("Median latency (ms)", fontsize=9)
        ax.set_title(f"{QUERY_LABELS[qid]}  [scale={fixed_scale}]", fontsize=10)
        ax.legend(fontsize=8)
        ax.tick_params(axis="both", labelsize=8)
        ax.grid(True, alpha=0.3)

    fig.suptitle("Exp 1 — Chart 2: Distribution complexity (latency vs K)", fontsize=12)
    fig.tight_layout()
    path = os.path.join(output_dir, "exp1_chart2_complexity.png")
    fig.savefig(path, dpi=150)
    plt.close(fig)
    print(f"  Wrote {path}")


# ---------------------------------------------------------------------------
# Chart 3: Overhead ratio grouped bar (fixed scale=E3)
# ---------------------------------------------------------------------------

def plot_chart3(merged: pd.DataFrame, output_dir: str) -> None:
    if not HAS_MPL:
        return

    fixed_scale = "E3"
    bar_colors  = {1: "#3498db", 3: "#2ecc71", 5: "#f39c12", 10: "#e74c3c"}
    n_queries   = len(QUERY_IDS)
    n_k         = len(K_VALUES)
    width       = 0.18
    x           = np.arange(n_queries)

    fig, ax = plt.subplots(figsize=(10, 5))

    for ki, k in enumerate(K_VALUES):
        ratios = []
        for qid in QUERY_IDS:
            if qid == "Q3":
                ratios.append(float("nan"))
                continue
            sub = merged[(merged["Scale"] == fixed_scale) & (merged["K"] == str(k))
                         & (merged["QueryID"] == qid) & (merged["Type"] == "PROB")]
            ratios.append(sub["OverheadRatio"].values[0] if len(sub) else float("nan"))

        offsets = (ki - (n_k - 1) / 2) * width
        bars = ax.bar(x + offsets, ratios, width=width, label=f"K={k}",
                      color=bar_colors[k], edgecolor="white", linewidth=0.5)
        for bar, ratio in zip(bars, ratios):
            if not (ratio != ratio):  # not NaN
                ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.02,
                        f"{ratio:.2f}×", ha="center", va="bottom", fontsize=7)

    # Reference line at 1× overhead
    ax.axhline(1.0, color="black", linestyle="--", linewidth=0.9, label="1× (no overhead)")

    ax.set_xticks(x)
    ax.set_xticklabels([QUERY_LABELS[q] for q in QUERY_IDS], fontsize=9)
    ax.set_ylabel("Overhead ratio (PROB / DET)", fontsize=10)
    ax.set_title(f"Exp 1 — Chart 3: Overhead ratio by query and K  [scale={fixed_scale}]",
                 fontsize=11)
    ax.legend(fontsize=9)
    ax.set_ylim(bottom=0)
    ax.grid(True, axis="y", alpha=0.3)
    # Annotate Q3 column as "n/a"
    q3_idx = QUERY_IDS.index("Q3")
    ax.text(q3_idx, 0.1, "n/a\n(prob only)", ha="center", va="bottom",
            fontsize=9, color="grey", style="italic")

    fig.tight_layout()
    path = os.path.join(output_dir, "exp1_chart3_overhead.png")
    fig.savefig(path, dpi=150)
    plt.close(fig)
    print(f"  Wrote {path}")


# ---------------------------------------------------------------------------
# Console summary
# ---------------------------------------------------------------------------

def print_summary(merged: pd.DataFrame) -> None:
    print("\n=== Exp 1 Summary (K=3, all scales) ===\n")
    k_ref = "3"
    header = f"{'Scale':>6}  {'Gears':>6}  {'Q1 DET':>8}  {'Q1 K=3':>8}  " \
             f"{'Q2 DET':>8}  {'Q2 K=3':>8}  {'Q3 K=3':>8}  {'Q4 DET':>8}  {'Q4 K=3':>8}"
    print(header)
    print("  " + "─" * (len(header) - 2))

    for scale in SCALE_ORDER:
        gears = SCALE_GEAR_MAP.get(scale, "?")
        vals = {}
        for qid in ["Q1", "Q2", "Q3", "Q4"]:
            if qid != "Q3":
                det_r = merged[(merged["Scale"] == scale) & (merged["QueryID"] == qid)
                               & (merged["Type"] == "DET")]
                vals[f"{qid}_det"] = f"{det_r['Median_ms'].values[0]:8.3f}" if len(det_r) else "       —"
            prob_r = merged[(merged["Scale"] == scale) & (merged["K"] == k_ref)
                             & (merged["QueryID"] == qid) & (merged["Type"] == "PROB")]
            vals[f"{qid}_k3"] = f"{prob_r['Median_ms'].values[0]:8.3f}" if len(prob_r) else "       —"

        print(f"  {scale:>4}  {gears:>6}  "
              f"{vals.get('Q1_det','       —')}  {vals.get('Q1_k3','       —')}  "
              f"{vals.get('Q2_det','       —')}  {vals.get('Q2_k3','       —')}  "
              f"{vals.get('Q3_k3','       —')}  "
              f"{vals.get('Q4_det','       —')}  {vals.get('Q4_k3','       —')}")
    print()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="Exp 1 Analysis: System Overhead")
    parser.add_argument("--input",  default="benchmark/results/exp1/exp1_raw.csv",
                        help="Path to exp1_raw.csv")
    parser.add_argument("--output", default="benchmark/results/exp1",
                        help="Output directory for tables and charts")
    args = parser.parse_args()

    os.makedirs(args.output, exist_ok=True)

    print(f"Loading: {args.input}")
    df   = load(args.input)
    agg  = aggregate(df)
    mrg  = compute_overhead(agg)

    print("\nGenerating tables...")
    write_table1(agg, args.output)
    write_table2(mrg, args.output)

    print("\nGenerating charts...")
    plot_chart1(mrg, args.output)
    plot_chart2(mrg, args.output)
    plot_chart3(mrg, args.output)

    print_summary(mrg)
    print(f"All outputs written to: {args.output}")


if __name__ == "__main__":
    main()

