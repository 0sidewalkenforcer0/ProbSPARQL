#!/usr/bin/env python3
"""
Exp 4 Analysis: Generalization to Histogram and Dirichlet Distributions
=========================================================================
Reads the five CSVs produced by the Exp4 Java benchmark classes and generates:

  Console summary tables:
    - Dispatch verification grid (function × dist-type)
    - Per-operation micro-benchmark latencies
    - Cross-type JSD accuracy (Pearson r, MAE)
    - End-to-end speedup table (Hist vs GMM)
    - Dirichlet demo results

  Charts:
    - exp4_micro_barplot.png     – grouped bar: per-op latency by dist-type
    - exp4_crosstype_scatter.png – scatter: same-type vs cross-type JSD
    - exp4_endtoend_bar.png      – grouped bar: end-to-end latency by scale
    - exp4_dispatch_heatmap.png  – heatmap: function × type result count

Also retains support for the old exp4_overhead.csv / exp4_accuracy.csv from
HistogramBenchmark (legacy).

Usage:
    python analyze_exp4.py
    python analyze_exp4.py --input benchmark/results/exp4_full --output benchmark/results/exp4_full
"""
import argparse
import os
import sys

try:
    import pandas as pd
except ImportError:
    print("ERROR: pandas is required.  pip install pandas")
    sys.exit(1)

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.colors as mcolors
    HAS_MPL = True
except ImportError:
    HAS_MPL = False

try:
    import numpy as np
    HAS_NP = True
except ImportError:
    HAS_NP = False


# ---------------------------------------------------------------------------
# Generic loader
# ---------------------------------------------------------------------------

def load_csv(path: str) -> pd.DataFrame:
    if not os.path.exists(path):
        return pd.DataFrame()
    try:
        return pd.read_csv(path)
    except Exception as e:
        print(f"  WARNING: could not read {path}: {e}")
        return pd.DataFrame()


# ---------------------------------------------------------------------------
# 4.1 Dispatch verification
# ---------------------------------------------------------------------------

def analyze_dispatch(df: pd.DataFrame, output_dir: str) -> None:
    if df.empty:
        print("  SKIP dispatch analysis (exp4_dispatch.csv not found)")
        return
    print("\n=== Exp 4.1: Polymorphic Dispatch Verification ===\n")
    funcs = df["Function"].unique()
    types = df["DistType"].unique()

    header = f"  {'Function':<14}" + "".join(f"  {t:<14}" for t in types)
    print(header)
    print("  " + "─" * (14 + 16 * len(types)))
    for fn in funcs:
        row_str = f"  {fn:<14}"
        for t in types:
            sub = df[(df["Function"] == fn) & (df["DistType"] == t)]
            if sub.empty:
                row_str += "  " + "─" * 14
            else:
                status = sub.iloc[0].get("Status", "?")
                n = sub.iloc[0].get("ResultCount", "?")
                mark = "✓" if status == "OK" else "✗"
                row_str += f"  {mark} n={n:<12}"
        print(row_str)

    # Heatmap
    if HAS_MPL and HAS_NP:
        pivot = df.pivot_table(index="Function", columns="DistType",
                               values="ResultCount", aggfunc="first")
        fig, ax = plt.subplots(figsize=(max(6, len(types) * 2), max(4, len(funcs) * 0.8)))
        data = pivot.values.astype(float)
        im = ax.imshow(data, cmap="YlGn", aspect="auto")
        ax.set_xticks(range(len(pivot.columns)))
        ax.set_yticks(range(len(pivot.index)))
        ax.set_xticklabels(pivot.columns, rotation=30, ha="right")
        ax.set_yticklabels(pivot.index)
        for (i, j), val in np.ndenumerate(data):
            ax.text(j, i, f"{int(val)}" if not np.isnan(val) else "–",
                    ha="center", va="center", fontsize=9)
        plt.colorbar(im, ax=ax, label="Result count")
        ax.set_title("Exp 4.1: Dispatch — result count per (function, type)")
        fig.tight_layout()
        out = os.path.join(output_dir, "exp4_dispatch_heatmap.png")
        fig.savefig(out, dpi=150)
        plt.close(fig)
        print(f"\n  Chart: {out}")


# ---------------------------------------------------------------------------
# 4.2 Micro-benchmark
# ---------------------------------------------------------------------------

def analyze_micro(df: pd.DataFrame, output_dir: str) -> None:
    if df.empty:
        print("  SKIP micro-benchmark analysis (exp4_micro.csv not found)")
        return
    df["MedianUs"] = pd.to_numeric(df.get("MedianUs", pd.Series(dtype=float)), errors="coerce")
    df["IQRUs"]    = pd.to_numeric(df.get("IQRUs",    pd.Series(dtype=float)), errors="coerce")

    print("\n=== Exp 4.2: Per-Operation Latency (µs per call) ===\n")
    print(f"  {'Function':<14}  {'Type':<6}  {'Param':<8}  {'Median µs':>10}  {'IQR µs':>8}")
    print("  " + "─" * 54)
    for _, row in df.iterrows():
        print(f"  {row['Function']:<14}  {row['DistType']:<6}  {row['Param']:<8}  "
              f"{row['MedianUs']:10.2f}  {row['IQRUs']:8.2f}")

    # Only plot JSD rows for the main 3 types + first param variant each
    if HAS_MPL:
        # Bar chart: prob:jsd per dist-type and param
        jsd = df[df["Function"] == "prob:jsd"].copy()
        if not jsd.empty:
            fig, ax = plt.subplots(figsize=(8, 4))
            labels = jsd["DistType"] + "\n" + jsd["Param"]
            ax.bar(range(len(jsd)), jsd["MedianUs"], color="#4C72B0", alpha=0.8)
            ax.set_xticks(range(len(jsd)))
            ax.set_xticklabels(labels, fontsize=8)
            ax.set_ylabel("Median latency (µs per call)")
            ax.set_title("Exp 4.2: prob:jsd per-call latency by distribution type")
            ax.set_yscale("log")
            ax.grid(True, alpha=0.3, axis="y")
            fig.tight_layout()
            out = os.path.join(output_dir, "exp4_micro_jsd_bar.png")
            fig.savefig(out, dpi=150)
            plt.close(fig)
            print(f"\n  Chart: {out}")

        # Grouped bar for all functions
        funcs = df["Function"].unique()
        types = df[["DistType", "Param"]].apply(lambda r: r["DistType"] + "/" + r["Param"], axis=1).unique()
        fig, ax = plt.subplots(figsize=(12, 5))
        n_fns = len(funcs)
        width = 0.8 / n_fns
        for idx, fn in enumerate(funcs):
            sub = df[df["Function"] == fn]
            labels = list(sub["DistType"] + "/" + sub["Param"])
            x = range(len(sub))
            ax.bar([xi + idx * width for xi in x], sub["MedianUs"].values,
                   width=width, label=fn, alpha=0.8)
        ax.set_xticks([xi + (n_fns / 2 - 0.5) * width
                       for xi in range(len(df["DistType"].unique() * 3))])
        ax.set_ylabel("Median latency (µs)")
        ax.set_title("Exp 4.2: Per-operation latency by distribution type and function")
        ax.set_yscale("log")
        ax.legend(fontsize=7, ncol=3)
        ax.grid(True, alpha=0.3, axis="y")
        fig.tight_layout()
        out = os.path.join(output_dir, "exp4_micro_barplot.png")
        fig.savefig(out, dpi=150)
        plt.close(fig)
        print(f"  Chart: {out}")


# ---------------------------------------------------------------------------
# 4.3 Cross-type JSD accuracy
# ---------------------------------------------------------------------------

def analyze_crosstype(df: pd.DataFrame, output_dir: str) -> None:
    if df.empty:
        print("  SKIP cross-type analysis (exp4_crosstype.csv not found)")
        return
    for col in ["SameTypeJSD", "CrossTypeJSD", "AbsError", "TimeSameNs", "TimeCrossNs"]:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    print("\n=== Exp 4.3: Cross-Type JSD Accuracy ===\n")
    print(f"  {'PairType':<15}  {'N':>5}  {'MAE':>9}  {'Pearson r':>10}  "
          f"{'Speedup':>9}")
    print("  " + "─" * 55)
    for pt in df["PairType"].unique():
        sub = df[df["PairType"] == pt]
        n   = len(sub)
        mae = sub["AbsError"].mean()
        # speedup: sameType is usually reference (baseline 1×)
        speedup = ""
        if "TimeSameNs" in df.columns and "TimeCrossNs" in df.columns:
            t_same  = sub["TimeSameNs"].median()
            t_cross = sub["TimeCrossNs"].median()
            if t_cross > 0:
                speedup = f"{t_same / t_cross:.2f}×"
        r_str = ""
        if HAS_NP and not sub["SameTypeJSD"].isna().all() and not sub["CrossTypeJSD"].isna().all():
            x = sub["SameTypeJSD"].dropna()
            y = sub["CrossTypeJSD"].loc[x.index].dropna()
            if len(x) > 2:
                r = float(np.corrcoef(x.values, y.values)[0, 1])
                r_str = f"{r:.4f}"
        print(f"  {pt:<15}  {n:>5}  {mae:>9.6f}  {r_str:>10}  {speedup:>9}")

    if HAS_MPL:
        cross_types = [t for t in df["PairType"].unique()
                       if "↔" in t and df[df["PairType"] == t]["AbsError"].mean() > 1e-8]
        same_types  = [t for t in df["PairType"].unique()
                       if t not in cross_types]
        fig, axes = plt.subplots(1, max(1, len(cross_types)), figsize=(5 * max(1, len(cross_types)), 4))
        if len(cross_types) == 1:
            axes = [axes]
        for ax, pt in zip(axes, cross_types):
            sub = df[df["PairType"] == pt]
            ax.scatter(sub["SameTypeJSD"], sub["CrossTypeJSD"], s=10, alpha=0.6)
            lo, hi = 0, max(sub[["SameTypeJSD", "CrossTypeJSD"]].max().max(), 0.01)
            ax.plot([lo, hi], [lo, hi], "r--", linewidth=0.8, label="y=x")
            ax.set_xlabel("Same-type JSD")
            ax.set_ylabel("Cross-type JSD (fallback)")
            ax.set_title(pt)
            ax.legend(fontsize=8)
            ax.grid(True, alpha=0.3)
        fig.suptitle("Exp 4.3: Cross-type JSD accuracy")
        fig.tight_layout()
        out = os.path.join(output_dir, "exp4_crosstype_scatter.png")
        fig.savefig(out, dpi=150)
        plt.close(fig)
        print(f"\n  Chart: {out}")


# ---------------------------------------------------------------------------
# 4.4 End-to-end performance
# ---------------------------------------------------------------------------

def analyze_endtoend(df: pd.DataFrame, output_dir: str) -> None:
    if df.empty:
        print("  SKIP end-to-end analysis (exp4_endtoend.csv not found)")
        return
    for col in ["MedianMs", "IQRMs"]:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    print("\n=== Exp 4.4: End-to-End Query Performance ===\n")
    print(f"  {'Query':<8}  {'Scale':<5}  {'Type':<5}  {'Param':<8}  "
          f"{'Median ms':>10}  {'Speedup vs GMM':>14}")
    print("  " + "─" * 58)

    # Compute speedup relative to GMM per (query, scale)
    gmm_ref = (df[df["DistType"] == "GMM"]
               .set_index(["Query", "Scale"])["MedianMs"]
               .to_dict())

    for _, row in df.sort_values(["Query", "Scale", "DistType"]).iterrows():
        ref = gmm_ref.get((row["Query"], row["Scale"]))
        speedup = f"{ref / row['MedianMs']:.1f}×" if ref and row["MedianMs"] > 0 else ""
        print(f"  {row['Query']:<8}  {row['Scale']:<5}  {row['DistType']:<5}  "
              f"{str(row.get('Param','')):<8}  {row['MedianMs']:10.1f}  {speedup:>14}")

    if HAS_MPL:
        for qid in df["Query"].unique():
            sub = df[df["Query"] == qid].copy()
            sub["Label"] = sub["DistType"] + "/" + sub.get("Param", "").astype(str)
            scales = sub["Scale"].unique()
            labels = sub["Label"].unique()
            x = range(len(scales))
            width = 0.8 / len(labels)
            fig, ax = plt.subplots(figsize=(8, 4))
            for idx, lbl in enumerate(labels):
                vals = [sub[(sub["Scale"] == s) & (sub["Label"] == lbl)]["MedianMs"].values
                        for s in scales]
                vals = [v[0] if len(v) > 0 else float("nan") for v in vals]
                ax.bar([xi + idx * width for xi in x], vals, width=width, label=lbl, alpha=0.8)
            ax.set_xticks([xi + (len(labels) / 2 - 0.5) * width for xi in x])
            ax.set_xticklabels(scales)
            ax.set_ylabel("Median latency (ms)")
            ax.set_title(f"Exp 4.4: {qid} — end-to-end latency")
            ax.set_yscale("log")
            ax.legend(fontsize=8)
            ax.grid(True, alpha=0.3, axis="y")
            fig.tight_layout()
            out = os.path.join(output_dir, f"exp4_endtoend_{qid.replace('/', '_').replace(' ','_')}_bar.png")
            fig.savefig(out, dpi=150)
            plt.close(fig)
            print(f"\n  Chart: {out}")


# ---------------------------------------------------------------------------
# 4.5 Dirichlet demo
# ---------------------------------------------------------------------------

def analyze_dirichlet_demo(df: pd.DataFrame) -> None:
    if df.empty:
        print("  SKIP Dirichlet demo analysis (exp4_dirichlet_demo.csv not found)")
        return
    print("\n=== Exp 4.5: Dirichlet Distribution Demonstration ===\n")
    print(f"  {'Query':<32}  {'Source':<12}  {'Results':>8}  {'Status'}")
    print("  " + "─" * 65)
    for _, row in df.iterrows():
        n = row.get("ResultCount", row.get("ResultCount", "?"))
        status = row.get("Status", "?")
        src    = row.get("Source", "?")
        qry    = str(row.get("Query", "?"))
        print(f"  {qry:<32}  {src:<12}  {str(n):>8}  {status}")


# ---------------------------------------------------------------------------
# Legacy support for old HistogramBenchmark CSVs
# ---------------------------------------------------------------------------

def load_overhead(path: str) -> pd.DataFrame:
    if not os.path.exists(path):
        return pd.DataFrame()
    df = pd.read_csv(path)
    for col in ["N", "Median_ms", "IQR_ms", "OverheadRatio"]:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    return df


def load_accuracy(path: str) -> pd.DataFrame:
    if not os.path.exists(path):
        return pd.DataFrame()
    df = pd.read_csv(path)
    for col in ["B", "Pair", "PredJSD", "Correct_30pct"]:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    return df


def print_overhead_table(df: pd.DataFrame) -> None:
    if df.empty:
        return
    print("\n=== [Legacy] Exp 4: Overhead Comparison ===\n")
    print(f"  {'Repr':<12}  {'Query':<8}  {'Median_ms':>10}  {'Ratio':>8}")
    print("  " + "─" * 42)
    for _, row in df.iterrows():
        print(f"  {row.get('Repr',''):<12}  {row.get('QueryID',''):<8}  "
              f"{row.get('Median_ms', float('nan')):10.2f}  "
              f"{row.get('OverheadRatio', float('nan')):8.2f}x")


def print_accuracy_table(df: pd.DataFrame) -> None:
    if df.empty:
        return
    print("\n=== [Legacy] Exp 4: Histogram JSD Accuracy ===\n")
    for b in sorted(df["B"].dropna().unique()):
        sub = df[df["B"] == b]
        n_total = len(sub)
        n_plaus = int(sub["Correct_30pct"].sum())
        mean_jsd = sub["PredJSD"].mean()
        std_jsd  = sub["PredJSD"].std()
        print(f"  B={int(b):3d}  pairs={n_total:4d}  plausible={n_plaus:4d} "
              f"({100*n_plaus/n_total:.1f}%)  JSD mean={mean_jsd:.4f} ± {std_jsd:.4f}")


def plot_overhead_bar(df: pd.DataFrame, output_dir: str) -> None:
    if not HAS_MPL or df.empty:
        return
    query_ids = df["QueryID"].unique()
    reprs     = df["Repr"].unique()
    x = range(len(reprs))

    fig, axes = plt.subplots(1, len(query_ids), figsize=(5 * len(query_ids), 4), sharey=True)
    if len(query_ids) == 1:
        axes = [axes]
    for ax, qid in zip(axes, query_ids):
        sub = df[df["QueryID"] == qid]
        vals = [sub[sub["Repr"] == r]["Median_ms"].values[0]
                if not sub[sub["Repr"] == r].empty else 0
                for r in reprs]
        ax.bar(list(x), vals, width=0.6, color=["#4C72B0", "#DD8452", "#55A868"])
        ax.set_xticks(list(x)); ax.set_xticklabels(reprs, rotation=15, ha="right")
        ax.set_ylabel("Median latency (ms)"); ax.set_title(qid)
        ax.grid(True, alpha=0.3, axis="y")
    fig.suptitle("Exp 4 [Legacy]: Histogram vs GMM Latency", fontsize=12)
    fig.tight_layout()
    out = os.path.join(output_dir, "exp4_overhead_barplot.png")
    fig.savefig(out, dpi=150); plt.close(fig)
    print(f"  Plot: {out}")


def plot_jsd_scatter(df: pd.DataFrame, output_dir: str) -> None:
    if not HAS_MPL or df.empty:
        return
    b_vals = sorted(df["B"].dropna().unique())
    fig, ax = plt.subplots(figsize=(7, 4))
    for b in b_vals:
        sub = df[df["B"] == b].sort_values("Pair")
        ax.scatter(sub["Pair"], sub["PredJSD"], s=4, alpha=0.6, label=f"B={int(b)}")
    ax.axhline(0.3, color="red", linestyle="--", linewidth=0.8, label="θ=0.3")
    ax.set_xlabel("Pair index"); ax.set_ylabel("Predicted JSD")
    ax.set_title("Exp 4 [Legacy]: Histogram JSD Distribution")
    ax.legend(); ax.grid(True, alpha=0.3)
    fig.tight_layout()
    out = os.path.join(output_dir, "exp4_accuracy_scatter.png")
    fig.savefig(out, dpi=150); plt.close(fig)
    print(f"  Plot: {out}")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze Exp 4 generalization results")
    parser.add_argument("--input",  default=None, help="Directory containing exp4_*.csv files")
    parser.add_argument("--output", default=None, help="Output directory for plots/tables")
    args = parser.parse_args()

    script_dir     = os.path.dirname(os.path.realpath(__file__))
    default_results = os.path.join(script_dir, "../results/exp4_full")
    if not os.path.isdir(default_results):
        default_results = os.path.join(script_dir, "../results")
    input_dir  = os.path.realpath(args.input  or default_results)
    output_dir = os.path.realpath(args.output or input_dir)
    os.makedirs(output_dir, exist_ok=True)

    print(f"=== Exp 4 Analysis ===")
    print(f"  Input : {input_dir}")
    print(f"  Output: {output_dir}")

    # New-format CSVs
    analyze_dispatch(load_csv(os.path.join(input_dir, "exp4_dispatch.csv")),    output_dir)
    analyze_micro(   load_csv(os.path.join(input_dir, "exp4_micro.csv")),       output_dir)
    analyze_crosstype(load_csv(os.path.join(input_dir, "exp4_crosstype.csv")), output_dir)
    analyze_endtoend(load_csv(os.path.join(input_dir, "exp4_endtoend.csv")),   output_dir)
    analyze_dirichlet_demo(load_csv(os.path.join(input_dir, "exp4_dirichlet_demo.csv")))

    # Legacy CSVs
    df_oh  = load_overhead(os.path.join(input_dir, "exp4_overhead.csv"))
    df_acc = load_accuracy(os.path.join(input_dir, "exp4_accuracy.csv"))
    if not df_oh.empty or not df_acc.empty:
        print("\n  (Legacy HistogramBenchmark CSVs found — running legacy analysis)")
        print_overhead_table(df_oh)
        print_accuracy_table(df_acc)
        plot_overhead_bar(df_oh,  output_dir)
        plot_jsd_scatter(df_acc, output_dir)

    print("\nAnalysis complete.")


if __name__ == "__main__":
    main()
