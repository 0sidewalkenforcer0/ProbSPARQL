#!/usr/bin/env python3
"""
Exp2 Analysis v2 — In-Engine vs External (3-way comparison)
============================================================
Merges outputs of Exp2Benchmark.java and exp2_external_v2.py,
computes speedups, prints summary tables, and generates 4 charts.

Reads (from --results-dir):
    exp2_a.csv             — Approach A (naive in-engine)
    exp2_b_fetch.csv       — Approach B fetch times
    exp2_b_python.csv      — Approach B Python compute times
    exp2_c.csv             — Approach C (pruned SimJoin)
    exp2_pruning_stats.csv — per-level pruning counts

Writes (to --output-dir, default = --results-dir):
    exp2_main_table.csv
    exp2_pruning_table.csv
    exp2_breakdown_table.csv
    exp2_chart1_threeway.png
    exp2_chart2_selectivity.png
    exp2_chart3_pruning.png
    exp2_chart4_speedup.png

Usage:
    python analyze_exp2_v2.py
    python analyze_exp2_v2.py --results-dir benchmark/results/exp2 \\
                               --output-dir  benchmark/results/exp2
"""

import argparse
import os
import sys

try:
    import numpy as np
    import pandas as pd
except ImportError:
    print("ERROR: numpy and pandas required.  pip install numpy pandas")
    sys.exit(1)

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    HAS_MPL = True
except ImportError:
    HAS_MPL = False

SEL_ORDER = ["10pct", "50pct", "90pct"]
SEL_LABELS_NICE = {"10pct": "10% (strict)", "50pct": "50% (moderate)", "90pct": "90% (lenient)"}


# ---------------------------------------------------------------------------
# Load and merge
# ---------------------------------------------------------------------------

def load(results_dir: str) -> dict:
    def p(name): return os.path.join(results_dir, name)

    missing = [n for n in [
        "exp2_a.csv", "exp2_b_fetch.csv", "exp2_b_python.csv",
        "exp2_c.csv", "exp2_pruning_stats.csv"
    ] if not os.path.exists(p(n))]
    if missing:
        print("Missing files:", missing)
        print("Run Exp2Benchmark.java and exp2_external_v2.py first.")
        sys.exit(1)

    a    = pd.read_csv(p("exp2_a.csv"))
    bf   = pd.read_csv(p("exp2_b_fetch.csv"))
    bpy  = pd.read_csv(p("exp2_b_python.csv"))
    c    = pd.read_csv(p("exp2_c.csv"))
    prn  = pd.read_csv(p("exp2_pruning_stats.csv"))
    return dict(a=a, bf=bf, bpy=bpy, c=c, prn=prn)


def build_main_table(d: dict) -> pd.DataFrame:
    a   = d["a"][["NPairs", "Selectivity", "Time_ms", "ResultCount"]].rename(
              columns={"Time_ms": "A_ms", "ResultCount": "A_cnt"})
    bf  = d["bf"][["NPairs", "Selectivity", "Fetch_ms"]]
    bpy = d["bpy"][["NPairs", "Selectivity", "Total_ms"]].rename(
              columns={"Total_ms": "BPy_ms"})
    c   = d["c"][["NPairs", "Selectivity", "Time_ms", "ResultCount"]].rename(
              columns={"Time_ms": "C_ms", "ResultCount": "C_cnt"})

    m = (a
         .merge(bf,  on=["NPairs", "Selectivity"], how="outer")
         .merge(bpy, on=["NPairs", "Selectivity"], how="outer")
         .merge(c,   on=["NPairs", "Selectivity"], how="outer"))

    m["B_ms"]     = m["Fetch_ms"] + m["BPy_ms"]
    m["Speedup_A_over_B"] = m["B_ms"] / m["A_ms"]
    m["Speedup_C_over_A"] = m["A_ms"] / m["C_ms"]
    m["Speedup_C_over_B"] = m["B_ms"] / m["C_ms"]
    m["Recall_C"] = m["C_cnt"] / m["A_cnt"].replace(0, float("nan"))

    cat = pd.CategoricalDtype(categories=SEL_ORDER, ordered=True)
    m["Selectivity"] = m["Selectivity"].astype(cat)
    return m.sort_values(["NPairs", "Selectivity"])


# ---------------------------------------------------------------------------
# Print tables
# ---------------------------------------------------------------------------

def print_main_table(m: pd.DataFrame):
    print("\n=== Table 1: Execution time (ms) — three approaches ===\n")
    fmt = "{:>7}  {:>10}  {:>10.1f}  {:>12.1f}  {:>10.1f}  {:>8.2f}  {:>8.2f}  {:>8.2f}  {:>8.1%}"
    hdr = "{:>7}  {:>10}  {:>10}  {:>12}  {:>10}  {:>8}  {:>8}  {:>8}  {:>8}"
    print(hdr.format("Pairs", "Selectivity", "A ms", "B ms", "C ms", "A/B", "C/A", "C/B", "Recall"))
    print("-" * 92)
    for _, r in m.iterrows():
        try:
            print(fmt.format(
                int(r["NPairs"]), r["Selectivity"],
                r["A_ms"], r["B_ms"], r["C_ms"],
                r["Speedup_A_over_B"], r["Speedup_C_over_A"], r["Speedup_C_over_B"],
                r["Recall_C"]))
        except Exception:
            pass


def print_pruning_table(prn: pd.DataFrame):
    print("\n=== Table 2: Pruning statistics (Approach C) ===\n")
    hdr = "{:>7}  {:>10}  {:>10}  {:>10}  {:>10}  {:>10}  {:>10}  {:>10}"
    fmt = "{:>7}  {:>10}  {:>10}  {:>10}  {:>10}  {:>10}  {:>10}  {:>10.1%}"
    print(hdr.format("Pairs", "Selectivity", "Total", "Dim", "Mean", "Var", "Bounds", "Rate"))
    print("-" * 88)
    for _, r in prn.iterrows():
        try:
            print(fmt.format(
                int(r["NPairs"]), r["Selectivity"],
                int(r["TotalPairs"]), int(r["PrunedDim"]), int(r["PrunedMean"]),
                int(r["PrunedVar"]), int(r["PrunedBounds"]), float(r["PruningRate"])))
        except Exception:
            pass


# ---------------------------------------------------------------------------
# Charts
# ---------------------------------------------------------------------------

def chart1_threeway(m: pd.DataFrame, output_dir: str):
    if not HAS_MPL: return
    sub = m[m["Selectivity"] == "50pct"].sort_values("NPairs")
    fig, ax = plt.subplots(figsize=(7, 4))
    ax.plot(sub["NPairs"], sub["A_ms"], marker="o", label="A: Naive in-engine")
    ax.plot(sub["NPairs"], sub["B_ms"], marker="s", label="B: External")
    ax.plot(sub["NPairs"], sub["C_ms"], marker="^", label="C: Pruned SimJoin")
    ax.set_xscale("log"); ax.set_yscale("log")
    ax.set_xlabel("Distribution pairs"); ax.set_ylabel("Execution time (ms)")
    ax.set_title("Exp2: Three-way comparison (50% selectivity)")
    ax.legend(); ax.grid(True, alpha=0.3)
    fig.tight_layout()
    out = os.path.join(output_dir, "exp2_chart1_threeway.png")
    fig.savefig(out, dpi=150); plt.close(fig)
    print(f"  Chart: {out}")


def chart2_selectivity(m: pd.DataFrame, output_dir: str):
    if not HAS_MPL: return
    # Pick the scale closest to 5000 pairs
    available = sorted(m["NPairs"].unique())
    target = min(available, key=lambda x: abs(x - 5000))
    sub = m[m["NPairs"] == target].copy()
    sub["Selectivity"] = pd.Categorical(sub["Selectivity"], categories=SEL_ORDER, ordered=True)
    sub = sub.sort_values("Selectivity")

    x = np.arange(len(sub))
    width = 0.25
    fig, ax = plt.subplots(figsize=(7, 4))
    ax.bar(x - width, sub["A_ms"], width, label="A: Naive in-engine")
    ax.bar(x,          sub["B_ms"], width, label="B: External")
    ax.bar(x + width,  sub["C_ms"], width, label="C: Pruned SimJoin")
    ax.set_xticks(x)
    ax.set_xticklabels([SEL_LABELS_NICE.get(s, s) for s in sub["Selectivity"]])
    ax.set_ylabel("Execution time (ms)")
    ax.set_title(f"Exp2: Selectivity effect (~{target} pairs)")
    ax.legend(); ax.grid(True, axis="y", alpha=0.3)
    fig.tight_layout()
    out = os.path.join(output_dir, "exp2_chart2_selectivity.png")
    fig.savefig(out, dpi=150); plt.close(fig)
    print(f"  Chart: {out}")


def chart3_pruning(prn: pd.DataFrame, output_dir: str):
    if not HAS_MPL: return
    available = sorted(prn["NPairs"].unique())
    target = min(available, key=lambda x: abs(x - 5000))
    sub = prn[prn["NPairs"] == target].copy()
    sub["Selectivity"] = pd.Categorical(sub["Selectivity"], categories=SEL_ORDER, ordered=True)
    sub = sub.sort_values("Selectivity")

    labels = [SEL_LABELS_NICE.get(s, s) for s in sub["Selectivity"]]
    fig, ax = plt.subplots(figsize=(7, 4))
    bottom = np.zeros(len(sub))
    for col, lbl in [("PrunedDim", "L1: Dim"), ("PrunedMean", "L2: Mean"),
                     ("PrunedVar", "L3: Var"), ("PrunedBounds", "L4: Bounds"),
                     ("FullJSD", "L5: Full JSD")]:
        vals = sub[col].fillna(0).values.astype(float)
        ax.bar(labels, vals, bottom=bottom, label=lbl)
        bottom += vals
    ax.set_ylabel("Number of pairs")
    ax.set_title(f"Exp2: Pruning breakdown (~{target} pairs)")
    ax.legend(loc="upper right"); ax.grid(True, axis="y", alpha=0.3)
    fig.tight_layout()
    out = os.path.join(output_dir, "exp2_chart3_pruning.png")
    fig.savefig(out, dpi=150); plt.close(fig)
    print(f"  Chart: {out}")


def chart4_speedup(m: pd.DataFrame, output_dir: str):
    if not HAS_MPL: return
    sub = m[m["Selectivity"] == "50pct"].sort_values("NPairs")
    fig, ax = plt.subplots(figsize=(7, 4))
    ax.plot(sub["NPairs"], sub["Speedup_C_over_B"], marker="o", label="C/B (total advantage)")
    ax.plot(sub["NPairs"], sub["Speedup_C_over_A"], marker="s", label="C/A (pruning advantage)")
    ax.axhline(1.0, color="gray", linestyle="--", linewidth=0.8, label="1× parity")
    ax.set_xscale("log")
    ax.set_xlabel("Distribution pairs"); ax.set_ylabel("Speedup factor")
    ax.set_title("Exp2: Speedup factors (50% selectivity)")
    ax.legend(); ax.grid(True, alpha=0.3)
    fig.tight_layout()
    out = os.path.join(output_dir, "exp2_chart4_speedup.png")
    fig.savefig(out, dpi=150); plt.close(fig)
    print(f"  Chart: {out}")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Analyze Exp2 results")
    parser.add_argument("--results-dir", default=None)
    parser.add_argument("--output-dir",  default=None)
    args = parser.parse_args()

    script_dir  = os.path.dirname(os.path.realpath(__file__))
    default_dir = os.path.join(script_dir, "../../results/exp2")
    results_dir = os.path.realpath(args.results_dir or default_dir)
    output_dir  = os.path.realpath(args.output_dir  or results_dir)
    os.makedirs(output_dir, exist_ok=True)

    print(f"Results dir : {results_dir}")
    print(f"Output  dir : {output_dir}")

    d = load(results_dir)
    m = build_main_table(d)

    print_main_table(m)
    print_pruning_table(d["prn"])

    # Save CSVs
    m.to_csv(os.path.join(output_dir, "exp2_main_table.csv"), index=False)
    d["prn"].to_csv(os.path.join(output_dir, "exp2_pruning_table.csv"), index=False)

    # Breakdown table (Approach B detail)
    breakdown = (d["bf"].merge(d["bpy"], on=["NPairs", "Selectivity"], how="outer")
                        .merge(d["a"][["NPairs", "Selectivity", "Time_ms"]].rename(
                               columns={"Time_ms": "A_ms"}), on=["NPairs", "Selectivity"])
                        .merge(d["c"][["NPairs", "Selectivity", "Time_ms"]].rename(
                               columns={"Time_ms": "C_ms"}), on=["NPairs", "Selectivity"]))
    breakdown.to_csv(os.path.join(output_dir, "exp2_breakdown_table.csv"), index=False)

    # Charts
    print("\nGenerating charts...")
    chart1_threeway(m, output_dir)
    chart2_selectivity(m, output_dir)
    chart3_pruning(d["prn"], output_dir)
    chart4_speedup(m, output_dir)

    if not HAS_MPL:
        print("  (matplotlib not installed — charts skipped)")


if __name__ == "__main__":
    main()
