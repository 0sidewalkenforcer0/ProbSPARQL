#!/usr/bin/env python3
"""
validate_exp2.py — Validation checks for Exp2 results

Checks:
  Check 1: All retained variants return the same result count
  Check 2: Calibration CSV sanity — multimodalPairs and theta ordering
  Check 3: Speedup ordering — DIVJOIN should generally beat InEngine_CF at high unimodalFrac
  Check 4: Pruning stats conservation / DIVJOIN invariants

Exit code: 0 = all checks passed, 1 = failures found.

Usage:
  python3 validate_exp2.py [--results-dir <dir>]
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
    a_cf_rows = load_csv(os.path.join(results_dir, "exp2_inengine_cheapfirst.csv"))
    a_jf_rows = load_csv(os.path.join(results_dir, "exp2_inengine_jsdfirst.csv"))
    c_rows    = load_csv(os.path.join(results_dir, "exp2_similarityjoin.csv"))
    ps_rows  = load_csv(os.path.join(results_dir, "exp2_pruning_stats.csv"))
    cal_rows = load_csv(os.path.join(results_dir, "exp2_calibration.csv"))

    if any(x is None for x in [a_cf_rows, a_jf_rows, c_rows]):
        print("[ERROR] Missing required CSV files.", file=sys.stderr)
        sys.exit(1)

    # Build lookups
    def key(r):
        return (nt(r["NPairs"]), r["UnimodalFrac"], r["Selectivity"])

    cnt_a_cf = {key(r): nt(r["ResultCount"]) for r in a_cf_rows}
    cnt_a_jf = {key(r): nt(r["ResultCount"]) for r in a_jf_rows}
    cnt_c    = {key(r): nt(r["ResultCount"]) for r in c_rows}
    tim_a_cf = {key(r): flt(r["Time_ms"]) for r in a_cf_rows}
    tim_c    = {key(r): flt(r["Time_ms"]) for r in c_rows}

    all_keys = sorted(set(cnt_a_cf) & set(cnt_a_jf) & set(cnt_c))
    print(f"\n{'='*60}")
    print(f"Exp2 Validation — {len(all_keys)} configurations")
    print(f"Results dir: {results_dir}")
    print(f"{'='*60}")

    # -----------------------------------------------------------------------
    # Check 1: retained variants agree on result count
    # -----------------------------------------------------------------------
    print("\n[Check 1] InEngine_CF, InEngine_JF, and DIVJOIN have identical result counts")
    check1_pass = True
    for k in all_keys:
        values = (cnt_a_cf[k], cnt_a_jf[k], cnt_c[k])
        if len(set(values)) != 1:
            msg = (f"  FAIL: count mismatch at nPairs={k[0]} uf={k[1]} sel={k[2]} :: "
                   f"InEngine_CF={values[0]} InEngine_JF={values[1]} DIVJOIN={values[2]}")
            failures.append(msg)
            check1_pass = False
    if check1_pass:
        print("  PASS: All retained variants agree for all configurations")
    else:
        print("  FAIL: mismatches found across variants")

    # -----------------------------------------------------------------------
    # Check 3: Calibration sanity — multimodalPairs > 0 when unimodalFrac < 1
    # -----------------------------------------------------------------------
    print("\n[Check 2] Calibration CSV — multimodal pair counts plausible")
    check2_pass = True
    if cal_rows is None:
        warnings.append("  [WARN] exp2_calibration.csv not found")
    else:
        for r in cal_rows:
            uf = flt(r["UnimodalFrac"])
            mm = nt(r["MultimodalPairs"])
            np_ = nt(r["NPairs"])
            theta10 = flt(r["Theta_10pct"])
            theta90 = flt(r["Theta_90pct"])
            if uf < 1.0 and mm == 0:
                failures.append(f"  FAIL: MultimodalPairs=0 at nPairs={np_} uf={uf}")
                check2_pass = False
            if theta10 > theta90:
                failures.append(
                    f"  FAIL: theta_10={theta10:.4f} > theta_90={theta90:.4f} "
                    f"at nPairs={np_} uf={uf}")
                check2_pass = False
            if theta10 < 0 or theta90 > 1.0:
                warnings.append(
                    f"  [WARN] theta out of [0,1]: theta_10={theta10:.4f} "
                    f"theta_90={theta90:.4f} at nPairs={np_} uf={uf}")
    if check2_pass:
        print("  PASS: Calibration thresholds look valid")

    # -----------------------------------------------------------------------
    # Check 4: Speedup ordering — C faster than A by large unimodalFrac
    # -----------------------------------------------------------------------
    print("\n[Check 3] Speedup ordering: DIVJOIN faster than InEngine_CF at high unimodalFrac (>=0.5)")
    check3_pass = True
    # Aggregate speedup by (unimodalFrac, selectivity)
    speedup_by_uf = defaultdict(list)
    for k in all_keys:
        npairs, uf, sel = k
        ta = tim_a_cf.get(k)
        tc = tim_c.get(k)
        if ta is not None and tc is not None and tc > 0:
            speedup_by_uf[uf].append(ta / tc)
    for uf in sorted(speedup_by_uf):
        speeds = speedup_by_uf[uf]
        avg = sum(speeds) / len(speeds) if speeds else float("nan")
        if flt(uf) >= 0.5 and avg < 1.0:
            msg = (f"  [NOTE] DIVJOIN slower than InEngine_CF at uf={uf} (avg speedup={avg:.2f}x)")
            warnings.append(msg)
            check3_pass = False
        status = "✓" if avg >= 1.0 else "✗"
        print(f"  uf={uf}: avg speedup DIVJOIN/InEngine_CF = {avg:.2f}x {status}")
    if check3_pass:
        print("  PASS: DIVJOIN is faster than InEngine_CF at high unimodalFrac")

    # -----------------------------------------------------------------------
    # Check 5: Pruning stats sanity
    # -----------------------------------------------------------------------
    print("\n[Check 4] Pruning stats — PrunedMean + PrunedVar + PrunedBounds + "
          "FullJSD + PrunedDim == TotalPairs")
    if not ps_rows:
        warnings.append("  [WARN] exp2_pruning_stats.csv not found or empty")
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
                failures.append(msg)
                check5_pass = False
        if check5_pass:
            print("  PASS: Pruning stat conservation holds")

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
    parser = argparse.ArgumentParser(description="Validate Exp2 results")
    parser.add_argument("--results-dir", default="benchmark/results/exp2",
                        help="Directory containing exp2_*.csv files")
    args = parser.parse_args()
    sys.exit(validate(args.results_dir))
