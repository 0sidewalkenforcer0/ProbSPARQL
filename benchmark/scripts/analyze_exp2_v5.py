#!/usr/bin/env python3
"""
analyze_exp2_v5.py — Analysis and visualization for Exp2 v5 results

Produces:
  1. Summary table: speedup of C vs A and C vs B by unimodal fraction
  2. Speedup-vs-unimodalFrac line chart (key v5 chart)
  3. Pruning rate vs unimodal fraction
  4. Result-count consistency check (A==B==C)
  5. Per-selectivity breakdown bar charts

Usage:
  python3 analyze_exp2_v5.py [--results-dir <dir>] [--output-dir <dir>]
"""

import argparse
import os
import sys
import csv
from collections import defaultdict

try:
    import pandas as pd
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np
    HAS_PLOTTING = True
except ImportError:
    HAS_PLOTTING = False
    print("[WARN] pandas/matplotlib not available — summary tables only", file=sys.stderr)


# ---------------------------------------------------------------------------
# CSV loading
# ---------------------------------------------------------------------------

def load_csv(path):
    """Load CSV into list-of-dicts."""
    rows = []
    try:
        with open(path, newline="") as f:
            reader = csv.DictReader(f)
            for row in reader:
                rows.append(row)
    except FileNotFoundError:
        print(f"[WARN] File not found: {path}", file=sys.stderr)
    return rows


def to_float(v):
    try:
        return float(v)
    except (ValueError, TypeError):
        return float("nan")


def to_int(v):
    try:
        return int(v)
    except (ValueError, TypeError):
        return 0


# ---------------------------------------------------------------------------
# Analysis
# ---------------------------------------------------------------------------

def analyze(results_dir, output_dir):
    os.makedirs(output_dir, exist_ok=True)

    a_rows   = load_csv(os.path.join(results_dir, "exp2v5_a.csv"))
    b_rows   = load_csv(os.path.join(results_dir, "exp2v5_b_java.csv"))
    c_rows   = load_csv(os.path.join(results_dir, "exp2v5_c.csv"))
    ps_rows  = load_csv(os.path.join(results_dir, "exp2v5_pruning_stats.csv"))
    cal_rows = load_csv(os.path.join(results_dir, "exp2v5_calibration.csv"))

    if not (a_rows and b_rows and c_rows):
        print("[ERROR] Required CSV files missing or empty.", file=sys.stderr)
        sys.exit(1)

    # Build lookup: (nPairs, unimodalFrac, selectivity) -> time_ms
    def build_lookup(rows, time_col="Time_ms"):
        lut = {}
        for r in rows:
            key = (to_int(r["NPairs"]), r["UnimodalFrac"], r["Selectivity"])
            lut[key] = to_float(r[time_col])
        return lut

    lut_a = build_lookup(a_rows)
    lut_b = build_lookup(b_rows)
    lut_c = build_lookup(c_rows)

    # Build result-count lookup for consistency check
    def build_count_lookup(rows):
        lut = {}
        for r in rows:
            key = (to_int(r["NPairs"]), r["UnimodalFrac"], r["Selectivity"])
            lut[key] = to_int(r["ResultCount"])
        return lut

    cnt_a = build_count_lookup(a_rows)
    cnt_b = build_count_lookup(b_rows)
    cnt_c = build_count_lookup(c_rows)

    # Collect all configurations
    all_keys = sorted(set(lut_a.keys()) & set(lut_b.keys()) & set(lut_c.keys()))

    # -----------------------------------------------------------------------
    # 1. Result-count consistency
    # -----------------------------------------------------------------------
    print("\n=== Result Count Consistency (A==B==C) ===")
    consistency_ok = True
    for key in all_keys:
        a, b, c = cnt_a.get(key, -1), cnt_b.get(key, -1), cnt_c.get(key, -1)
        if a != b or a != c:
            consistency_ok = False
            recall_c = f"{c/a*100:.1f}%" if a > 0 else "N/A"
            print(f"  [MISMATCH] nPairs={key[0]} uf={key[1]} sel={key[2]}: "
                  f"A={a} B={b} C={c}  recall_C={recall_c}")
    if consistency_ok:
        print("  All A==B==C  ✓")
    else:
        print("  [WARN] Mismatches found — C pruning bound may not be a valid lower bound!")

    # -----------------------------------------------------------------------
    # 2. Speedup summary table
    # -----------------------------------------------------------------------
    print("\n=== Speedup Summary: C vs A and C vs B ===")

    # Group by (unimodalFrac, selectivity), average speedup across scales
    from collections import defaultdict
    speedup_ca_by_uf_sel = defaultdict(list)
    speedup_cb_by_uf_sel = defaultdict(list)

    for key in all_keys:
        npairs, uf, sel = key
        ta = lut_a[key]
        tb = lut_b[key]
        tc = lut_c[key]
        if tc > 0:
            speedup_ca_by_uf_sel[(uf, sel)].append(ta / tc)
            speedup_cb_by_uf_sel[(uf, sel)].append(tb / tc)

    # Print table
    ufs  = sorted(set(k[0] for k in speedup_ca_by_uf_sel))
    sels = sorted(set(k[1] for k in speedup_ca_by_uf_sel))

    header = f"{'UnimodalFrac':>14}  {'Selectivity':>11}"
    header += "  SpeedupC/A  SpeedupC/B"
    print(header)
    print("-" * 55)
    for uf in ufs:
        for sel in sels:
            vals_ca = speedup_ca_by_uf_sel.get((uf, sel), [])
            vals_cb = speedup_cb_by_uf_sel.get((uf, sel), [])
            avg_ca  = sum(vals_ca) / len(vals_ca) if vals_ca else float("nan")
            avg_cb  = sum(vals_cb) / len(vals_cb) if vals_cb else float("nan")
            print(f"  {uf:>12}  {sel:>11}  {avg_ca:>10.2f}x  {avg_cb:>10.2f}x")

    # -----------------------------------------------------------------------
    # 3. Write detailed summary CSV
    # -----------------------------------------------------------------------
    summary_path = os.path.join(output_dir, "exp2v5_summary.csv")
    with open(summary_path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["NPairs", "UnimodalFrac", "Selectivity", "Theta",
                    "A_ms", "B_ms", "C_ms",
                    "SpeedupC_A", "SpeedupC_B",
                    "A_results", "B_results", "C_results",
                    "C_Recall_vs_A"])
        for key in all_keys:
            npairs, uf, sel = key
            # Get theta from a_rows
            theta = next((to_float(r["Theta"]) for r in a_rows
                          if to_int(r["NPairs"]) == npairs
                          and r["UnimodalFrac"] == uf
                          and r["Selectivity"] == sel), float("nan"))
            ta = lut_a[key]
            tb = lut_b[key]
            tc = lut_c[key]
            sca = ta / tc if tc > 0 else float("nan")
            scb = tb / tc if tc > 0 else float("nan")
            ca = cnt_a.get(key, 0)
            cb = cnt_b.get(key, 0)
            cc = cnt_c.get(key, 0)
            recall = cc / ca if ca > 0 else (1.0 if cc == 0 else float("nan"))
            w.writerow([npairs, uf, sel, f"{theta:.6f}",
                        f"{ta:.3f}", f"{tb:.3f}", f"{tc:.3f}",
                        f"{sca:.4f}", f"{scb:.4f}",
                        ca, cb, cc, f"{recall:.4f}"])
    print(f"\nWrote: {summary_path}")

    # -----------------------------------------------------------------------
    # 4. Pruning rate table
    # -----------------------------------------------------------------------
    if ps_rows:
        print("\n=== Pruning Rates (Approach C) ===")
        print(f"  {'NPairs':>8}  {'UnimodalFrac':>12}  {'Sel':>6}  {'PruneRate%':>10}  "
              f"{'PrunedMean':>10}  {'FullJSD':>8}")
        print("  " + "-" * 60)
        for r in sorted(ps_rows, key=lambda x: (to_int(x["NPairs"]), x["UnimodalFrac"])):
            print(f"  {r['NPairs']:>8}  {r['UnimodalFrac']:>12}  {r['Selectivity']:>6}  "
                  f"  {to_float(r['PruningRate'])*100:>8.1f}%  "
                  f"{r['PrunedMean']:>10}  {r['FullJSD']:>8}")

    # -----------------------------------------------------------------------
    # 5. Plots (if matplotlib available)
    # -----------------------------------------------------------------------
    if not HAS_PLOTTING:
        print("\n[INFO] Install pandas + matplotlib to generate plots.")
        return

    # --- Figure 1: Speedup vs UnimodalFrac (key v5 chart) ---
    fig, axes = plt.subplots(1, 2, figsize=(12, 5))
    fig.suptitle("C (SIMILARITYJOIN) Speedup vs Unimodal Fraction", fontsize=13)

    # Use largest scale only for clarity
    largest_npairs = max(to_int(r["NPairs"]) for r in a_rows)

    for ax_idx, (ax, comp_label, lut_comp) in enumerate(
            zip(axes, ["A (SPARQL)", "B (Java loop)"], [lut_a, lut_b])):
        sel_colors = {"10pct": "blue", "50pct": "orange", "90pct": "green"}
        sel_styles = {"10pct": "o-", "50pct": "s--", "90pct": "^:"}
        for sel in sels:
            xs, ys = [], []
            for uf in ufs:
                key = (largest_npairs, uf, sel)
                ta = lut_comp.get(key)
                tc = lut_c.get(key)
                if ta is not None and tc is not None and tc > 0:
                    xs.append(float(uf))
                    ys.append(ta / tc)
            if xs:
                ax.plot(xs, ys, sel_styles[sel],
                        color=sel_colors[sel],
                        label=f"{sel}",
                        linewidth=2, markersize=8)
        ax.axhline(1.0, color="gray", linestyle="--", linewidth=1, label="breakeven")
        ax.set_xlabel("Unimodal Fraction", fontsize=11)
        ax.set_ylabel(f"Speedup C / {comp_label}", fontsize=11)
        ax.set_title(f"C vs {comp_label}  (N≈{largest_npairs} pairs)")
        ax.legend(title="Selectivity", fontsize=9)
        ax.set_xticks([float(u) for u in ufs])
        ax.grid(alpha=0.3)

    plt.tight_layout()
    chart1 = os.path.join(output_dir, "exp2v5_speedup_vs_uf.png")
    fig.savefig(chart1, dpi=150)
    plt.close(fig)
    print(f"Wrote: {chart1}")

    # --- Figure 2: Speedup vs N_PAIRS for each unimodalFrac ---
    fig, axes = plt.subplots(1, len(ufs), figsize=(5 * len(ufs), 5), sharey=True)
    if len(ufs) == 1:
        axes = [axes]
    fig.suptitle("C vs A Speedup — per Unimodal Fraction", fontsize=13)

    npairs_list = sorted(set(to_int(r["NPairs"]) for r in a_rows))
    for ax, uf in zip(axes, ufs):
        sel_colors = {"10pct": "blue", "50pct": "orange", "90pct": "green"}
        for sel in sels:
            xs, ys = [], []
            for np_ in npairs_list:
                key = (np_, uf, sel)
                ta = lut_a.get(key)
                tc = lut_c.get(key)
                if ta is not None and tc is not None and tc > 0:
                    xs.append(np_)
                    ys.append(ta / tc)
            if xs:
                ax.semilogx(xs, ys, "o-", color=sel_colors[sel],
                            label=sel, linewidth=2, markersize=7)
        ax.axhline(1.0, color="gray", linestyle="--", linewidth=1)
        ax.set_xlabel("Target Pair Count (log)", fontsize=10)
        ax.set_ylabel("Speedup C/A" if ax == axes[0] else "", fontsize=10)
        ax.set_title(f"UnimodalFrac={uf}")
        ax.legend(title="Sel", fontsize=8)
        ax.grid(alpha=0.3)

    plt.tight_layout()
    chart2 = os.path.join(output_dir, "exp2v5_speedup_vs_npairs.png")
    fig.savefig(chart2, dpi=150)
    plt.close(fig)
    print(f"Wrote: {chart2}")

    # --- Figure 3: Pruning rate heatmap ---
    if ps_rows:
        uf_vals   = sorted(set(r["UnimodalFrac"] for r in ps_rows))
        npairs_vals = sorted(set(to_int(r["NPairs"]) for r in ps_rows))
        # Use median selectivity (50pct) for heatmap
        sel_fixed = "50pct"
        grid = np.full((len(npairs_vals), len(uf_vals)), np.nan)
        for r in ps_rows:
            if r["Selectivity"] != sel_fixed:
                continue
            ri = npairs_vals.index(to_int(r["NPairs"]))
            ci = uf_vals.index(r["UnimodalFrac"])
            grid[ri, ci] = to_float(r["PruningRate"])

        fig, ax = plt.subplots(figsize=(7, 4))
        im = ax.imshow(grid, vmin=0, vmax=1, cmap="YlGn", aspect="auto")
        ax.set_xticks(range(len(uf_vals)))
        ax.set_xticklabels([f"{v}" for v in uf_vals])
        ax.set_yticks(range(len(npairs_vals)))
        ax.set_yticklabels([str(v) for v in npairs_vals])
        ax.set_xlabel("Unimodal Fraction")
        ax.set_ylabel("Target Pair Count")
        ax.set_title(f"Pruning Rate (C, sel={sel_fixed})")
        for ri in range(len(npairs_vals)):
            for ci in range(len(uf_vals)):
                val = grid[ri, ci]
                if not np.isnan(val):
                    ax.text(ci, ri, f"{val:.2f}", ha="center", va="center",
                            color="black" if val < 0.7 else "white", fontsize=9)
        plt.colorbar(im, ax=ax, label="Pruning Rate")
        plt.tight_layout()
        chart3 = os.path.join(output_dir, "exp2v5_pruning_heatmap.png")
        fig.savefig(chart3, dpi=150)
        plt.close(fig)
        print(f"Wrote: {chart3}")

    print("\n=== Analysis complete ===")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Analyze Exp2 v5 results")
    parser.add_argument("--results-dir", default="benchmark/results/exp2_v5",
                        help="Directory containing exp2v5_*.csv files")
    parser.add_argument("--output-dir", default=None,
                        help="Directory for output charts/summary (default: results-dir)")
    args = parser.parse_args()

    out = args.output_dir if args.output_dir else args.results_dir
    analyze(args.results_dir, out)
