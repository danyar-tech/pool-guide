import math
import sys
import time

from detect import Prep, assign_kinds, classify, detect_stick2 as detect_stick, detect_v1, detect_v2, TARGET_R
from gen import make_scene

KIND = lambda bid: "cue" if bid == 0 else "eight" if bid == 8 else "solid" if bid < 8 else "stripe"


def match(found, truth, r):
    used = set(); tp = 0; errs = []
    for (tx, ty, _) in truth:
        best = None
        for i, f in enumerate(found):
            if i in used:
                continue
            d = math.hypot(f[0] - tx, f[1] - ty)
            if d < 0.5 * r and (best is None or d < best[1]):
                best = (i, d)
        if best:
            used.add(best[0]); tp += 1; errs.append(best[1] / r)
    return tp, len(found) - tp, len(truth) - tp, errs, used


def run(n=200, start=1000, radius_err=0.0):
    tot = {"v1": [0, 0, 0], "v2": [0, 0, 0]}
    cls_ok = cls_n = 0
    stick_err = []; stick_n = stick_found = 0
    errs2 = []; miss_by = {}
    t2 = 0
    for seed in range(start, start + n):
        img, truth, table, r, stick = make_scene(seed)
        rr = r * (1 + radius_err)
        f1 = detect_v1(img, table, rr)
        a = match(f1, truth, r)
        for k in range(3): tot["v1"][k] += a[k]
        t0 = time.time()
        balls, P = detect_v2(img, table, rr, return_debug=True)
        t2 += time.time() - t0
        kinds = assign_kinds(balls)
        f2 = [(b[0], b[1], k) for b, k in zip(balls, kinds)]
        tp, fp, fn, errs, used = match(f2, truth, r)
        tot["v2"][0] += tp; tot["v2"][1] += fp; tot["v2"][2] += fn; errs2 += errs
        # classification accuracy on matched balls
        for (tx, ty, bid) in truth:
            cand = [f for f in f2 if math.hypot(f[0] - tx, f[1] - ty) < 0.5 * r]
            if cand:
                cls_n += 1
                if cand[0][2] == KIND(bid): cls_ok += 1
                else: miss_by[(KIND(bid), cand[0][2])] = miss_by.get((KIND(bid), cand[0][2]), 0) + 1
            else:
                miss_by[(KIND(bid), "MISSED")] = miss_by.get((KIND(bid), "MISSED"), 0) + 1
        if stick is not None:
            stick_n += 1
            cue = [t for t in truth if t[2] == 0][0]
            res = detect_stick(P, cue, table)
            if res:
                stick_found += 1
                d = abs((res[0] - stick + math.pi) % (2 * math.pi) - math.pi)
                stick_err.append(math.degrees(d))
    for k, (tp, fp, fn) in tot.items():
        print(f"{k}: recall {tp / (tp + fn):.3f}  precision {tp / max(1, tp + fp):.3f}  (tp {tp} fp {fp} fn {fn})")
    errs2.sort()
    print(f"v2 centre error (x R): median {errs2[len(errs2)//2]:.3f}  p95 {errs2[int(len(errs2)*.95)]:.3f}")
    print(f"v2 classification accuracy {cls_ok / max(1, cls_n):.3f}  | {time.time() and t2 / n * 1000:.0f} ms/frame (python)")
    print("errors:", sorted(miss_by.items(), key=lambda x: -x[1])[:10])
    if stick_n:
        stick_err.sort()
        print(f"stick: found {stick_found}/{stick_n}; angle err median {stick_err[len(stick_err)//2]:.3f} deg, "
              f"p90 {stick_err[int(len(stick_err)*.9)]:.3f} deg, max {stick_err[-1]:.2f}")


if __name__ == "__main__":
    run(int(sys.argv[1]) if len(sys.argv) > 1 else 60, radius_err=float(sys.argv[2]) if len(sys.argv) > 2 else 0.0)
