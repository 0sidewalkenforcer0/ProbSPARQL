#!/usr/bin/env python3
"""
Exp 2 Analysis: In-Engine vs External JSD Comparison
=====================================================
Reads ``exp2_inengine.csv`` and ``exp2_external.csv``, merges them on
(NPairs, Theta), and computes the speedup ratio IN_ENGINE / EXTERNAL.

Outputs:
  * exp2_summary_table.csv  – merged table with speedup column
  * exp2_speedup.png        – speedup vs. pair count for each θ value

Usage:
    python analyze_exp2.py
    python analyze_exp2.py --input benchmark/results --output benchmark/results
"""
import argparse
import os
import sys

try:
    import numpy as np
    import pandas as pd
except ImportError:
    print("ERROR: numpy and pandas are required.  pip install numpy pandas")
    sys.exit(1)

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    HAS_MPL = True
except ImportError:
    HAS_MPL = False


def load_and_merge(input_dir: str) -> pd.DataFrame:
    ie_path = os.path.join(input_dir, "exp2_inengine.csv")
    ex_path = os.path.join(input_dir, "exp2_external.csv")

    if not os.path.exists(ie_path):
        print(f"ERROR: {ie_path} not found — run InEngineVsExternalBenchmark first.")
        sys.exit(1)
    if not os.path.exists(ex_path):
        print(f"ERROR: {ex_path} not found — run exp2_external_baseline.py first.")
        sys.exit(1)

    ie = pd.read_csv(ie_path)
    ex = pd.read_csv(ex_path)

    # Filter to IN_ENGINE rows only; FETCH_ONLY is a separate analysis
    in_engine = ie[ie["Approach"] == "IN_ENGINE"][["NPairs", "Theta", "Median_ms", "ResultCount"]]
    external  = ex[ex["Approach"] == "EXTERNAL"][["NPairs", "Theta", "Median_ms"]]

    in_engine = in_engine.rename(columns={"Median_ms": "IE_ms", "ResultCount": "IE_count"})
    external  = external.rename(columns={"Median_ms": "EX_ms"})

    merged = pd.merge(in_engine, external, on=["NPairs", "Theta"], how="outer")
    merged["Speedup"] = merged["EX_ms"] / merged["IE_ms"]
    return merged.sort_values(["NPairs", "Theta"])


def print_table(df: pd.DataFrame) -> None:
    print("\n=== Exp 2: In-Engine vs External JSD Summary ===\n")
    print(f"{'NPairs':>8}  {'θ':>5}  {'IN-ENGINE (ms)':>15}  {'EXTERNAL (ms)':>14}  "
          f"{'Speedup':>8}  {'Results':>8}")
    print("-" * 72)
    for _, row in df.iterrows():
        print(f"{int(row['NPairs']):8d}  {row['Theta']:5.2f}  {row['IE_ms']:15.2f}  "
              f"{row['EX_ms']:14.2f}  {row['Speedup']:8.2f}x  {int(row.get('IE_count', 0)):8d}")


def plot_speedup(df: pd.DataFrame, output_dir: str) -> None:
    if not HAS_MPL:
        print("  (matplotlib not available — skipping plot)")
        return

    fig, ax = plt.subplots(figsize=(7, 4))
    for theta in sorted(df["Theta"].unique()):
        sub = df[df["Theta"] == theta].sort_values("NPairs")
        ax.plot(sub["NPairs"], sub["Speedup"], marker="o", label=f"θ={theta}")

    ax.axhline(1.0, color="gray", linestyle="--", linewidth=0.8, label="1× (parity)")
    ax.set_xlabel("Number of pairs")
    ax.set_ylabel("Speedup (External / In-Engine)")
    ax.set_title("Exp 2: In-Engine vs External JSD Speedup")
    ax.set_xscale("log")
    ax.legend()
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    out_path = os.path.join(output_dir, "exp2_speedup.png")
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  Plot saved: {out_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze Exp 2 results")
    parser.add_argument("--input",  default=None, help="Directory containing exp2_*.csv files")
    parser.add_argument("--output", default=None, help="Directory for output files")
    args = parser.parse_args()

    script_dir = os.path.dirname(os.path.realpath(__file__))
    default_io  = os.path.join(script_dir, "../results")
    input_dir  = os.path.realpath(args.input  or default_io)
    output_dir = os.path.realpath(args.output or input_dir)
    os.makedirs(output_dir, exist_ok=True)

    df = load_and_merge(input_dir)
    print_table(df)

    # Write merged CSV
    out_csv = os.path.join(output_dir, "exp2_summary_table.csv")
    df.to_csv(out_csv, index=False)
    print(f"\nSummary CSV: {out_csv}")

    plot_speedup(df, output_dir)


if __name__ == "__main__":
    main()
