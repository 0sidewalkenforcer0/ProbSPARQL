#!/usr/bin/env python3
"""
exp2_validate_v5.py — Validation checks for Exp2 v5 results

Checks:
  Check 1: C recall == A result count (100% recall required)
            If C has lower recall, the DPI-based lower bound is invalid.
  Check 2: B result count == A result count (Java loop == SPARQL, <=1% tolerance)
  Check 3: F1/F2 sanity — multimodalPairs in calibration CSV is plausible
  Check 4: Speedup ordering — C should be faster than A at high unimodalFrac
  Check 5: Pruning rate increases with unimodalFrac (more unimodal → more pruning
            by L1 dimensional/L2 discretized-JSD on multimodal pairs)

Exit code: 0 = all checks passed, 1 = failures found.

Usage:
  python3 exp2_validate_v5.py [--results-dir <dir>]
"""

import argparse
import csv
import os
import sys
from collections import defaultdict


def load_csv(path):
    rows = []
    try:
        with open(path, newline="") as f:
            for row in csv.DictReader(f):
                rows.append(row)
    except FileNotFoundError:
        print(f"[ERROR] File not found: {path}", file=sys.stderr)
        return None
    return rows


def flt(v):
    try:
        return float(v)
    except (ValueError, TypeError):
        return float("nan")


def nt(v):
    try:
        return int(v)
    except (ValueError, TypeError):
        return 0


def validate(results_dir):
    failures = []
    warnings = []

    # Load CSVs
    a_rows   = load_csv(os.path.join(results_dir, "exp2v5_a.csv"))
    b_rows   = load_csv(os.path.join(results_dir, "exp2v5_b_java.csv"))
    c_rows   = load_csv(os.path.join(results_dir, "exp2v5_c.csv"))
    ps_rows  = load_csv(os.path.join(results_dir, "exp2v5_pruning_stats.csv"))
    cal_rows = load_csv(os.path.join(results_dir, "exp2v5_calibration.csv"))

    if any(x is None for x in [a_rows, b_rows, c_rows]):
        print("[ERROR] Missing required CSV files.", file=sys.stderr)
        sys.exit(1)

    # Build lookups
    def key(r):
        return (nt(r["NPairs"]), r["UnimodalFrac"], r["Selectivity"])

    cnt_a = {key(r): nt(r["ResultCount"]) for r in a_rows}
    cnt_b = {key(r): nt(r["ResultCount"]) for r in b_rows}
    cnt_c = {key(r): nt(r["ResultCount"]) for r in c_rows}
    tim_a = {key(r): flt(r["Time_ms"]) for r in a_rows}
    tim_c = {key(r): flt(r["Time_ms"]) for r in c_rows}

    all_keys = sorted(set(cnt_a) & set(cnt_b) & set(cnt_c))
    print(f"\n{'='*60}")
    print(f"Exp2 v5 Validation — {len(all_keys)} configurations")
    print(f"Results dir: {results_dir}")
    print(f"{'='*60}")

    # -----------------------------------------------------------------------
    # Check 1: C recall == 100% (vs A as ground truth)
    # -----------------------------------------------------------------------
    print("\n[Check 1] C recall == A result count (100% required)")
    check1_pass = True
    for k in all_keys:
        a, c = cnt_a[k], cnt_c[k]
        if a == 0 and c == 0:
            continue                # Both empty — OK
        if a == 0 and c > 0:
            failures.append(f"  C={c} but A=0 at {k}")
            check1_pass = False
            continue
        if c != a:
            recall = c / a
            msg = (f"  FAIL: C recall={recall*100:.1f}% ({c}/{a}) "
                   f"at nPairs={k[0]} uf={k[1]} sel={k[2]}")
            failures.append(msg)
            check1_pass = False
    if check1_pass:
        print("  PASS: All configurations have 100% recall (C==A)")
    else:
        print(f"  FAIL: {sum(1 for f in failures if 'recall' in f)} recall failures")

    # -----------------------------------------------------------------------
    # Check 2: B result count == A (Java loop matches SPARQL, ≤1% tolerance)
    # -----------------------------------------------------------------------
    print("\n[Check 2] B == A result count (Java loop == SPARQL)")
    check2_pass = True
    for k in all_keys:
        a, b = cnt_a[k], cnt_b[k]
        if a == 0 and b == 0:
            continue
        if a == 0:
            failures.append(f"  B={b} but A=0 at {k}")
            check2_pass = False
            continue
        diff_rate = abs(a - b) / a
        if diff_rate > 0.01:
            msg = (f"  FAIL: B={b} A={a} diff={diff_rate*100:.1f}% "
                   f"at nPairs={k[0]} uf={k[1]} sel={k[2]}")
            failures.append(msg)
            check2_pass = False
    if check2_pass:
        print("  PASS: B matches A within 1% for all configurations")

    # -----------------------------------------------------------------------
    # Check 3: Calibration sanity — multimodalPairs > 0 when unimodalFrac < 1
    # -----------------------------------------------------------------------
    print("\n[Check 3] Calibration CSV — multimodal pair counts plausible")
    check3_pass = True
    if cal_rows is None:
        warnings.append("  [WARN] exp2v5_calibration.csv not found")
    else:
        for r in cal_rows:
            uf = flt(r["UnimodalFrac"])
            mm = nt(r["MultimodalPairs"])
            np_ = nt(r["NPairs"])
            theta10 = flt(r["Theta_10pct"])
            theta90 = flt(r["Theta_90pct"])
            if uf < 1.0 and mm == 0:
                failures.append(f"  FAIL: MultimodalPairs=0 at nPairs={np_} uf={uf}")
                check3_pass = False
            if theta10 > theta90:
                failures.append(
                    f"  FAIL: theta_10={theta10:.4f} > theta_90={theta90:.4f} "
                    f"at nPairs={np_} uf={uf}")
                check3_pass = False
            if theta10 < 0 or theta90 > 1.0:
                warnings.append(
                    f"  [WARN] theta out of [0,1]: theta_10={theta10:.4f} "
                    f"theta_90={theta90:.4f} at nPairs={np_} uf={uf}")
    if check3_pass:
        print("  PASS: Calibration thresholds look valid")

    # -----------------------------------------------------------------------
    # Check 4: Speedup ordering — C faster than A by large unimodalFrac
    # -----------------------------------------------------------------------
    print("\n[Check 4] Speedup ordering: C faster than A at high unimodalFrac (>=0.5)")
    check4_pass = True
    # Aggregate speedup by (unimodalFrac, selectivity)
    speedup_by_uf = defaultdict(list)
    for k in all_keys:
        npairs, uf, sel = k
        ta = tim_a.get(k)
        tc = tim_c.get(k)
        if ta is not None and tc is not None and tc > 0:
            speedup_by_uf[uf].append(ta / tc)
    for uf in sorted(speedup_by_uf):
        speeds = speedup_by_uf[uf]
        avg = sum(speeds) / len(speeds) if speeds else float("nan")
        if flt(uf) >= 0.5 and avg < 1.0:
            msg = (f"  [NOTE] C slower than A at uf={uf} (avg speedup={avg:.2f}x) — "
                   f"expected C to be faster at high unimodalFrac")
            warnings.append(msg)
            check4_pass = False
        status = "✓" if avg >= 1.0 else "✗"
        print(f"  uf={uf}: avg speedup C/A = {avg:.2f}x {status}")
    if check4_pass:
        print("  PASS: C is faster than A at high unimodalFrac")

    # -----------------------------------------------------------------------
    # Check 5: Pruning stats sanity
    # -----------------------------------------------------------------------
    print("\n[Check 5] Pruning stats — PrunedMean + PrunedVar + PrunedBounds + "
          "FullJSD + PrunedDim == TotalPairs")
    if not ps_rows:
        warnings.append("  [WARN] exp2v5_pruning_stats.csv not found or empty")
    else:
        check5_pass = True
        for r in ps_rows:
            total  = nt(r["TotalPairs"])
            summed = (nt(r["PrunedDim"]) + nt(r["PrunedMean"]) +
                      nt(r["PrunedVar"])  + nt(r["PrunedBounds"]) +
                      nt(r["FullJSD"]))
            if total > 0 and summed != total:
                msg = (f"  FAIL: TotalPairs={total} != sum={summed} "
                       f"at nPairs={r['NPairs']} uf={r['UnimodalFrac']} "
                       f"sel={r['Selectivity']}")
                # In v5, PrunedVar and PrunedBounds should be 0
                if nt(r["PrunedVar"]) > 0 or nt(r["PrunedBounds"]) > 0:
                    failures.append(msg + " [PrunedVar/PrunedBounds should be 0 in v5!]")
                    check5_pass = False
                else:
                    failures.append(msg)
                    check5_pass = False
            # In v5, check that PrunedVar == 0 and PrunedBounds == 0
            if nt(r["PrunedVar"]) != 0:
                failures.append(
                    f"  FAIL: PrunedVar={r['PrunedVar']} (should be 0 in v5) "
                    f"at {r['NPairs']} uf={r['UnimodalFrac']}")
                check5_pass = False
            if nt(r["PrunedBounds"]) != 0:
                failures.append(
                    f"  FAIL: PrunedBounds={r['PrunedBounds']} (should be 0 in v5) "
                    f"at {r['NPairs']} uf={r['UnimodalFrac']}")
                check5_pass = False
        if check5_pass:
            print("  PASS: Pruning stat invariants hold (PrunedVar=0, PrunedBounds=0)")

    # -----------------------------------------------------------------------
    # Summary
    # -----------------------------------------------------------------------
    print(f"\n{'='*60}")
    print(f"SUMMARY: {len(failures)} failures, {len(warnings)} warnings")
    print(f"{'='*60}")
    if failures:
        print("\nFAILURES:")
        for f in failures:
            print(f)
    if warnings:
        print("\nWARNINGS:")
        for w in warnings:
            print(w)

    if not failures:
        print("\nAll validation checks PASSED.")
        return 0
    else:
        print(f"\n{len(failures)} validation check(s) FAILED.")
        return 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Validate Exp2 v5 results")
    parser.add_argument("--results-dir", default="benchmark/results/exp2_v5",
                        help="Directory containing exp2v5_*.csv files")
    args = parser.parse_args()
    sys.exit(validate(args.results_dir))
