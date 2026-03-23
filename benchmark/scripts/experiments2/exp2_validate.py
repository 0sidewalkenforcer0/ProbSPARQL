#!/usr/bin/env python3
"""
Exp2 Correctness Validator
==========================
Checks that the three approaches produce semantically equivalent results.

  Check 1: C result count ≤ A result count × 1.05  (all 15 configs)
           Verifies DEDUPLICATE is active (no n²-pair inflation from self-pairs
           or duplicate directions).  Pruning is approximate, so C ≤ A is
           expected; C >> A would signal a dedup failure.
           Also reports recall = C / A per config (informational).

  Check 2: |A - B| / A <= 0.05  (within 5%, all configs where A > 0)
           Verifies Python naive MC (jsd_naive_mc) matches Java GT_10K.

Usage:
    python exp2_validate.py
    python exp2_validate.py --results-dir benchmark/results/exp2_v3
"""

import argparse
import csv
import os
import sys


def read_csv(path: str) -> list:
    with open(path) as f:
        return list(csv.DictReader(f))


def get(rows: list, n: int, sel: str) -> dict:
    return next(r for r in rows if int(r["NPairs"]) == n and r["Selectivity"] == sel)


def validate(results_dir: str) -> bool:
    required = ["exp2_a.csv", "exp2_c.csv", "exp2_b_python.csv"]
    for fname in required:
        path = os.path.join(results_dir, fname)
        if not os.path.exists(path):
            print(f"ERROR: {path} not found — run the benchmark first.")
            sys.exit(1)

    a  = read_csv(os.path.join(results_dir, "exp2_a.csv"))
    c  = read_csv(os.path.join(results_dir, "exp2_c.csv"))
    bp = read_csv(os.path.join(results_dir, "exp2_b_python.csv"))

    scales = [100, 500, 1000, 5000, 10000]
    sels   = ["10pct", "50pct", "90pct"]

    # ── Check 1: C ≤ A × 1.05  (dedup sanity: no n²-inflation) ─────────────
    #
    # C is approximate (pruning causes recall < 100%), so C < A is EXPECTED.
    # If DEDUPLICATE failed, C would count n²-many pairs (incl. self-pairs),
    # making C >> A.  We flag configs where C > A*1.05 as a dedup failure.
    # We also report recall (C/A) for each config as an informational metric.
    print("═" * 60)
    print("Check 1: C ≤ A × 1.05  (dedup sanity — no n²-pair inflation)")
    print("         Recall = C / A  (informational — pruning causes C ≤ A)")
    print("─" * 60)
    check1_failures = []
    for n in scales:
        for sel in sels:
            ra = get(a, n, sel)
            rc = get(c, n, sel)
            ca = int(ra["ResultCount"])
            cc = int(rc["ResultCount"])
            recall = (cc / ca) if ca > 0 else float("nan")
            flag = "FAIL" if cc > ca * 1.05 else "ok  "
            if cc > ca * 1.05:
                check1_failures.append((n, sel, ca, cc))
            print(f"  {flag}  n={n:>6}  {sel:6}  A={ca:>6}  C={cc:>6}  "
                  f"recall={recall:5.1%}")

    print()
    if check1_failures:
        print(f"  FAIL — {len(check1_failures)} config(s) have C > A*1.05 "
              f"(possible dedup failure)")
    else:
        print("  PASS — all 15 configs have C ≤ A (no false-positive inflation)")

    # ── Check 2: |A − B| / A ≤ 5% ───────────────────────────────────────────
    print()
    print("═" * 60)
    print("Check 2: |A − B_python| / A  ≤  5%  (non-zero configs)")
    print("─" * 60)
    check2_failures = []
    check2_skipped  = 0
    for n in scales:
        for sel in sels:
            ra = get(a, n, sel)
            rb = get(bp, n, sel)
            ca = int(ra["ResultCount"])
            cb = int(rb["ResultCount"])
            if ca == 0:
                check2_skipped += 1
                continue
            diff = abs(ca - cb) / ca
            if diff > 0.05:
                check2_failures.append((n, sel, ca, cb, diff))
                print(f"  FAIL  n={n:>6}  {sel}  A={ca}  B={cb}  diff={diff:.1%}")

    if check2_failures:
        print(f"  {len(check2_failures)} FAILURE(S)  "
              f"(skipped {check2_skipped} zero-result configs)")
    else:
        checked = len(scales) * len(sels) - check2_skipped
        print(f"  PASS — {checked} configs within 5%  "
              f"(skipped {check2_skipped} zero-result configs)")

    # ── Summary ──────────────────────────────────────────────────────────────
    print()
    print("═" * 60)
    all_pass = not check1_failures and not check2_failures
    if all_pass:
        print("ALL CHECKS PASSED ✓")
    else:
        print("SOME CHECKS FAILED ✗")
    print("═" * 60)
    return all_pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="Exp2 correctness validator")
    ap.add_argument("--results-dir", default=None,
                    help="Directory containing exp2_a.csv, exp2_c.csv, exp2_b_python.csv "
                         "(default: benchmark/results/exp2 relative to project root)")
    args = ap.parse_args()

    script_dir  = os.path.dirname(os.path.realpath(__file__))
    default_dir = os.path.realpath(os.path.join(script_dir, "../../results/exp2"))
    results_dir = os.path.realpath(args.results_dir or default_dir)

    print(f"Validating results in: {results_dir}\n")
    ok = validate(results_dir)
    sys.exit(0 if ok else 1)
