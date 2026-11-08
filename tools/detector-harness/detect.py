"""Ball detectors. v1 = port of the shipped Kotlin logic (baseline). v2 = new design, ported to Kotlin afterwards.
Only uses OpenCV ops that exist in the Android Java API."""
import math

import cv2
import numpy as np

# ----------------------------------------------------------------------------- v1 (baseline)


def detect_v1(rgb, table, r_hint):
    l, t, rr, b = table["left"], table["top"], table["right"], table["bottom"]
    crop = rgb[t:b, l:rr]
    s = min(1.0, 960 / max(crop.shape[:2]))
    small = cv2.resize(crop, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
    hsv = cv2.cvtColor(small, cv2.COLOR_RGB2HSV)
    H, S, V = hsv[..., 0].astype(int), hsv[..., 1].astype(int), hsv[..., 2].astype(int)
    m = (S > 50) & (V > 40)
    hist = np.bincount(H[m], minlength=180)
    sm = np.array([sum(hist[(h + k) % 180] for k in range(-3, 4)) for h in range(180)])
    h0 = int(np.argmax(sm))
    hd = np.minimum(np.abs(H - h0), 180 - np.abs(H - h0))
    near = (hd <= 6) & m
    s0, v0 = np.median(S[near]), np.median(V[near])
    felt = (hd <= 12) & (S >= max(30, s0 * 0.45)) & (V >= max(25, v0 * 0.35))
    nf = np.where(felt, 0, 255).astype(np.uint8)
    nf = cv2.morphologyEx(nf, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3)))
    dist = cv2.distanceTransform(nf, cv2.DIST_L2, 5)
    r = r_hint * s
    cands = []
    gray = cv2.medianBlur(cv2.cvtColor(small, cv2.COLOR_RGB2GRAY), 5)
    circles = cv2.HoughCircles(gray, cv2.HOUGH_GRADIENT_ALT, 1.5, r * 1.5, param1=300, param2=0.75,
                               minRadius=max(3, int(r * 0.75)), maxRadius=int(math.ceil(r * 1.3)))

    def nff(cx, cy, rad):
        yy, xx = np.ogrid[:nf.shape[0], :nf.shape[1]]
        d = (xx - cx) ** 2 + (yy - cy) ** 2 <= rad * rad
        return (nf[d] > 0).mean() if d.any() else 0

    if circles is not None:
        for c in circles[0]:
            f = nff(c[0], c[1], r * 0.8)
            if f >= 0.7:
                cands.append((c[0], c[1], 2 + f))
    k = int(round(r * 1.6)) | 1
    dil = cv2.dilate(dist, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    ys, xs = np.where((dist >= r * 0.72) & (dist >= dil - 1e-3))
    peaks = sorted([(x, y, dist[y, x] / r) for x, y in zip(xs, ys)], key=lambda p: -p[2])
    for p in peaks:
        if all((c[0] - p[0]) ** 2 + (c[1] - p[1]) ** 2 >= (r * 1.3) ** 2 for c in cands):
            cands.append(p)
    cands.sort(key=lambda c: -c[2])
    kept = []
    h, w = nf.shape
    for c in cands:
        if c[0] < r * .55 or c[1] < r * .55 or c[0] > w - r * .55 or c[1] > h - r * .55:
            continue
        if any((c[0] - k_[0]) ** 2 + (c[1] - k_[1]) ** 2 < (r * 1.4) ** 2 for k_ in kept):
            continue
        kept.append(c)
    return [(l + c[0] / s, t + c[1] / s, None) for c in kept[:22]]


# ----------------------------------------------------------------------------- v2

TARGET_R = 12.0  # work at a scale where the ball radius is 12 px


def disk_kernel(rad):
    k = int(math.ceil(rad))
    yy, xx = np.mgrid[-k:k + 1, -k:k + 1]
    m = ((xx * xx + yy * yy) <= rad * rad).astype(np.float32)
    return m / m.sum()


def ring_kernel(rad, width=1.5):
    k = int(math.ceil(rad + width))
    yy, xx = np.mgrid[-k:k + 1, -k:k + 1]
    d = np.sqrt(xx * xx + yy * yy)
    m = (np.abs(d - rad) <= width).astype(np.float32)
    return m / m.sum()


class Prep:
    """Everything shared by ball and stick detection for one frame."""

    def __init__(self, rgb, table, radius):
        self.l, self.t = int(table["left"]), int(table["top"])
        crop = rgb[self.t:int(table["bottom"]), self.l:int(table["right"])]
        self.s = TARGET_R / radius
        small = cv2.resize(crop, None, fx=self.s, fy=self.s, interpolation=cv2.INTER_AREA)
        self.h, self.w = small.shape[:2]
        lab = cv2.cvtColor(small, cv2.COLOR_RGB2Lab).astype(np.float32)
        self.L, self.A, self.B = lab[..., 0], lab[..., 1] - 128, lab[..., 2] - 128
        # felt chroma = mode of the (a,b) histogram
        qa = np.clip(((self.A + 128) / 4).astype(int), 0, 63)
        qb = np.clip(((self.B + 128) / 4).astype(int), 0, 63)
        hist = np.bincount((qa * 64 + qb).ravel(), minlength=64 * 64).astype(np.float32).reshape(64, 64)
        hist = cv2.GaussianBlur(hist, (5, 5), 1)
        ia, ib = np.unravel_index(np.argmax(hist), hist.shape)
        a0, b0 = ia * 4 - 128 + 2, ib * 4 - 128 + 2
        chroma_d = np.sqrt((self.A - a0) ** 2 + (self.B - b0) ** 2)
        feltish = (chroma_d < 10).astype(np.float32)
        fa, fb = float(np.median(self.A[feltish > 0])), float(np.median(self.B[feltish > 0]))
        self.felt_ab = (fa, fb)
        # felt brightness background (handles vignette): normalized blur of L over felt-like pixels
        k = int(TARGET_R * 8) | 1
        num = cv2.blur(self.L * feltish, (k, k))
        den = cv2.blur(feltish, (k, k))
        self.Lbg = np.where(den > 1e-3, num / np.maximum(den, 1e-3), float(np.median(self.L)))
        dab = np.sqrt((self.A - fa) ** 2 + (self.B - fb) ** 2)
        dl = np.abs(self.L - self.Lbg)
        # difference from felt: chroma counts fully, brightness less (shadows/vignette are brightness only)
        self.D = dab + 0.55 * np.maximum(dl - 6, 0)
        gx = cv2.Sobel(self.L, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(self.L, cv2.CV_32F, 0, 1, ksize=3)
        ax = cv2.Sobel(self.A, cv2.CV_32F, 1, 0, ksize=3)
        ay = cv2.Sobel(self.A, cv2.CV_32F, 0, 1, ksize=3)
        bx = cv2.Sobel(self.B, cv2.CV_32F, 1, 0, ksize=3)
        by = cv2.Sobel(self.B, cv2.CV_32F, 0, 1, ksize=3)
        self.gx, self.gy = gx + ax + bx, gy + ay + by
        # Silhouette map: saturated "not felt" in 0..1 (flat inside a ball, so ball texture adds no edges).
        self.F = np.minimum(self.D, 40.0) / 40.0 * 100.0
        fx_ = cv2.Sobel(self.F, cv2.CV_32F, 1, 0, ksize=3)
        fy_ = cv2.Sobel(self.F, cv2.CV_32F, 0, 1, ksize=3)
        wl = 0.15  # a little raw colour edge so touching balls stay separable
        # The game's own guide lines: thin, bright and LONG (stripe caps and highlights are short).
        chroma = np.sqrt(self.A ** 2 + self.B ** 2)
        bright = ((self.L > 150) & (chroma < 30)).astype(np.uint8)
        k5 = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
        thin = bright & (1 - cv2.morphologyEx(bright, cv2.MORPH_OPEN, k5))
        n, lab, stats, _ = cv2.connectedComponentsWithStats(thin, connectivity=8)
        keep = np.zeros(n, bool)
        for i in range(1, n):
            if max(stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT]) >= 2.2 * TARGET_R:
                keep[i] = True
        guide = keep[lab].astype(np.uint8)
        self.guide = cv2.dilate(guide, k5).astype(bool)
        self.gxx = fx_ * fx_ + wl * (gx * gx + ax * ax + bx * bx)
        self.gyy = fy_ * fy_ + wl * (gy * gy + ay * ay + by * by)
        self.gxy = fx_ * fy_ + wl * (gx * gy + ax * ay + bx * by)
        self.G = np.sqrt(gx * gx + gy * gy + ax * ax + ay * ay + bx * bx + by * by)


def radial_kernels(rad, width=1.5):
    k = int(math.ceil(rad + width))
    yy, xx = np.mgrid[-k:k + 1, -k:k + 1].astype(np.float32)
    d = np.sqrt(xx * xx + yy * yy)
    ring = (np.abs(d - rad) <= width).astype(np.float32)
    ring /= ring.sum()
    dn = np.maximum(d, 1e-6)
    c, s = xx / dn, yy / dn
    return ring * c * c, ring * 2 * c * s, ring * s * s, ring


def radial_score(P, R):
    """Radial minus tangential gradient energy on a ring of radius R around every pixel (all linear filters)."""
    kxx, kxy, kyy, ring = radial_kernels(R)
    rad = cv2.filter2D(P.gxx, -1, kxx) + cv2.filter2D(P.gxy, -1, kxy) + cv2.filter2D(P.gyy, -1, kyy)
    tot = cv2.filter2D(P.gxx + P.gyy, -1, ring)
    tan = tot - rad
    return rad - tan, rad, tot


def ring_coverage(P, cx, cy, rad, n=36, thr=40.0):
    """Fraction of points on the circle with a strong, roughly radial gradient."""
    hits = 0
    for i in range(n):
        an = 2 * math.pi * i / n
        ux, uy = math.cos(an), math.sin(an)
        best = 0.0
        for dr in (-1.5, -0.75, 0.0, 0.75, 1.5):
            x, y = cx + ux * (rad + dr), cy + uy * (rad + dr)
            xi, yi = int(round(x)), int(round(y))
            if 0 <= xi < P.w and 0 <= yi < P.h:
                g = abs(P.gx[yi, xi] * ux + P.gy[yi, xi] * uy)
                best = max(best, g)
        if best > thr:
            hits += 1
    return hits / n


def detect_v2(rgb, table, radius, return_debug=False):
    P = Prep(rgb, table, radius)
    R = TARGET_R
    Dm = cv2.filter2D(P.D, -1, disk_kernel(R * 0.7))
    Dc = cv2.filter2D(P.D, -1, disk_kernel(R * 0.45))
    RS, RAD, TOT = radial_score(P, R)
    E = np.sqrt(np.maximum(RS, 0))
    S = E / 60.0
    k = int(R * 1.2) * 2 + 1
    dil = cv2.dilate(S, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    ys, xs = np.where((S >= dil - 1e-6) & (S > 0.5))
    cands = sorted([(float(S[y, x]), x, y) for x, y in zip(xs, ys)], reverse=True)
    pockets = [((px - P.l) * P.s, (py - P.t) * P.s) for (px, py) in table["pockets"]]
    pr = table["pr"] * P.s
    kept = []
    for sc, x, y in cands:
        # refine centre: best ring coverage in a small neighbourhood
        best = (-1, x, y)
        for dy in (-2, -1, 0, 1, 2):
            for dx in (-2, -1, 0, 1, 2):
                xx, yy = x + dx, y + dy
                if 0 <= xx < P.w and 0 <= yy < P.h:
                    v = E[yy, xx]
                    if v > best[0]:
                        best = (v, xx, yy)
        x, y = best[1], best[2]
        # sub-pixel: centroid of E around the peak
        x0, x1, y0, y1 = max(0, x - 2), min(P.w, x + 3), max(0, y - 2), min(P.h, y + 3)
        win = E[y0:y1, x0:x1] - E[y0:y1, x0:x1].min()
        if win.sum() > 0:
            yy, xx = np.mgrid[y0:y1, x0:x1]
            fx, fy = float((xx * win).sum() / win.sum()), float((yy * win).sum() / win.sum())
        else:
            fx, fy = float(x), float(y)
        if fx < R * 0.6 or fy < R * 0.6 or fx > P.w - R * 0.6 or fy > P.h - R * 0.6:
            continue
        if any(math.hypot(fx - px, fy - py) < pr + R * 0.3 for px, py in pockets):
            continue
        if any(math.hypot(fx - kx, fy - ky) < R * 1.55 for (_, kx, ky, _) in kept):
            continue
        cov = ring_coverage(P, fx, fy, R)
        dm = float(Dm[int(round(fy)), int(round(fx))])
        dc = float(Dc[int(round(fy)), int(round(fx))])
        # full circular edge + the inside differs from the felt (rejects the game's hollow ghost-ball circle)
        ok = cov >= 0.6 and dm >= 22 and dc >= 15
        if ok and is_fake(P, fx, fy, R):
            ok = False
        if ok:
            kept.append((sc, fx, fy, cov))
        if len(kept) >= 22:
            break
    balls = []
    for sc, fx, fy, cov in kept:
        kind = classify(P, fx, fy, R)
        balls.append((P.l + fx / P.s, P.t + fy / P.s, kind))
    # only one cue ball: keep the whitest, demote the rest
    return (balls, P) if return_debug else balls


def is_fake(P, cx, cy, R):
    """Rejects the game's own guideline graphics and felt logos: see-through ghost balls are flat and
    felt-coloured, outlines/logo rings are hollow and low-contrast. Real balls are shaded and solid."""
    x0, x1 = max(0, int(cx - R)), min(P.w, int(cx + R) + 1)
    y0, y1 = max(0, int(cy - R)), min(P.h, int(cy + R) + 1)
    yy, xx = np.mgrid[y0:y1, x0:x1]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / R
    D = P.D[y0:y1, x0:x1]; L = P.L[y0:y1, x0:x1]
    disk = d <= 0.85; inner = d <= 0.55
    # Game ghost ball: a bright white outline at ball radius with plain felt showing inside it.
    A = P.A[y0:y1, x0:x1]; Bc = P.B[y0:y1, x0:x1]
    felt_in = float((D[d <= 0.7] < 10).mean())
    xs0, xs1 = max(0, int(cx - 1.2 * R)), min(P.w, int(cx + 1.2 * R) + 1)
    ys0, ys1 = max(0, int(cy - 1.2 * R)), min(P.h, int(cy + 1.2 * R) + 1)
    yy2, xx2 = np.mgrid[ys0:ys1, xs0:xs1]
    d2 = np.sqrt((xx2 - cx) ** 2 + (yy2 - cy) ** 2) / R
    ring = (d2 > 0.8) & (d2 <= 1.15)
    Lr = P.L[ys0:ys1, xs0:xs1][ring]; Cr = np.sqrt(P.A[ys0:ys1, xs0:xs1][ring] ** 2 + P.B[ys0:ys1, xs0:xs1][ring] ** 2)
    white_ring = float(((Lr > 190) & (Cr < 30)).mean()) if Lr.size else 0.0
    inner6 = d <= 0.6
    Ci = np.sqrt(A[inner6] ** 2 + Bc[inner6] ** 2)
    white_in = float(((L[inner6] > 190) & (Ci < 30)).mean())
    if felt_in > 0.12 and white_ring > 0.25:
        return True
    if white_ring > 0.3 and white_in < 0.3:   # white outline with a non-white inside (cue ball is white inside)
        return True
    solid_in = float((D[inner] > 18).mean())
    dmed = float(np.median(D[disk]))
    lstd = float(np.std(L[disk]))
    if lstd < 26 and dmed < 60:
        return True
    if solid_in < 0.7 and lstd < 32:
        return True
    if solid_in < 0.5 and dmed < 14:
        return True
    return False


def classify(P, cx, cy, R):
    """Pixel shares inside the ball, ignoring the game's thin guide lines that may cross it."""
    x0, x1 = int(cx - R), int(cx + R) + 1
    y0, y1 = int(cy - R), int(cy + R) + 1
    x0c, x1c, y0c, y1c = max(0, x0), min(P.w, x1), max(0, y0), min(P.h, y1)
    yy, xx = np.mgrid[y0c:y1c, x0c:x1c]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / R
    L = P.L[y0c:y1c, x0c:x1c]; A = P.A[y0c:y1c, x0c:x1c]; B = P.B[y0c:y1c, x0c:x1c]
    chroma = np.sqrt(A * A + B * B)
    thin = P.guide[y0c:y1c, x0c:x1c]
    disk = (d <= 0.8) & ~thin
    outer = (d > 0.5) & (d <= 0.85) & ~thin
    if disk.sum() < 10:
        disk = d <= 0.8; outer = (d > 0.5) & (d <= 0.85)
    white = (L > 195) & (chroma < 22)
    black = (L < 70) & (chroma < 25)
    colour = (chroma >= 25) & (L > 35)
    wt = white[disk].mean(); wo = white[outer].mean() if outer.any() else 0.0
    bo = black[outer].mean() if outer.any() else 0.0; co = colour[disk].mean()
    # Stripes show white on two OPPOSITE sides of the rim; a number disk or the game's line cap is one-sided.
    light = (L > 150) & (chroma < 30)
    ang = ((np.arctan2(yy - cy, xx - cx) + math.pi) / (2 * math.pi) * 16).astype(int) % 16
    ann = (d > 0.5) & (d <= 0.85) & ~thin
    ws = []
    for k in range(16):
        m = ann & (ang == k)
        ws.append(bool(m.sum() > 0 and light[m].mean() >= 0.4))
    opposite = any(ws[k] and (ws[(k + 7) % 16] or ws[(k + 8) % 16] or ws[(k + 9) % 16]) for k in range(16))
    lt = light[disk].mean()
    return dict(white=float(wt), white_outer=float(wo), black_outer=float(bo), colour=float(co),
                light=float(lt), opposite=bool(opposite))


def assign_kinds(balls):
    """balls: list of (x, y, stats). Returns list of kinds."""
    kinds = ["unknown"] * len(balls)
    stats = [b[2] for b in balls]
    cue = [i for i, s in enumerate(stats) if s["white"] > 0.6 and s["colour"] < 0.12]
    if cue:
        ci = max(cue, key=lambda i: stats[i]["white"] - stats[i]["colour"])
        kinds[ci] = "cue"
    eight = [i for i, s in enumerate(stats) if kinds[i] == "unknown" and s["black_outer"] > 0.45]
    if eight:
        kinds[max(eight, key=lambda i: stats[i]["black_outer"])] = "eight"
    for i, s in enumerate(stats):
        if kinds[i] != "unknown":
            continue
        if s["colour"] >= 0.12 and (s["light"] > 0.6 or (s["opposite"] and s["white_outer"] >= 0.08)):
            kinds[i] = "stripe"
        elif s["colour"] >= 0.25:
            kinds[i] = "solid"
    return kinds


def detect_stick(P, cue_xy_full, table):
    """Shot direction (radians, screen coords) from the cue stick or the game's aim line, or None."""
    cx, cy = (cue_xy_full[0] - P.l) * P.s, (cue_xy_full[1] - P.t) * P.s
    R = TARGET_R
    Db = cv2.GaussianBlur(P.D, (5, 5), 1.0)
    # brightness difference catches the white aim line even on light felts
    Lw = cv2.GaussianBlur(np.maximum(P.L - P.Lbg, 0), (3, 3), 0.8)

    def sample(img, x, y):
        xi, yi = int(x), int(y)
        if xi < 0 or yi < 0 or xi >= P.w - 1 or yi >= P.h - 1:
            return 0.0
        fx, fy = x - xi, y - yi
        return float(img[yi, xi] * (1 - fx) * (1 - fy) + img[yi, xi + 1] * fx * (1 - fy) +
                     img[yi + 1, xi] * (1 - fx) * fy + img[yi + 1, xi + 1] * fx * fy)

    def ray(img, ang, r0, r1, n=40):
        ux, uy = math.cos(ang), math.sin(ang)
        vals = []
        for i in range(n):
            d = r0 + (r1 - r0) * i / (n - 1)
            x, y = cx + ux * d, cy + uy * d
            if not (0 <= x < P.w and 0 <= y < P.h):
                break
            vals.append(sample(img, x, y))
        if len(vals) < n // 3:
            return 0.0
        vals.sort()
        return vals[len(vals) // 4]  # lower quartile: needs the WHOLE ray to be stick/line, not one ball

    best = (0.0, 0.0, None)
    for i in range(720):
        ang = 2 * math.pi * i / 720
        st = ray(Db, ang, R * 1.6, R * 9)
        ln = ray(Lw, ang, R * 1.4, R * 7)
        if st > best[0]:
            best = (st, ang, "stick")
        if ln * 1.2 > best[0]:
            best = (ln * 1.2, ang, "line")
    score, ang, kind = best
    if kind is None or score < 12:
        return None
    # Thick bar = cue stick (shot goes the other way); thin = the game's aim line (shot goes along it).
    ux, uy = math.cos(ang), math.sin(ang)
    widths = []
    for dist in np.linspace(2.2 * R, 9 * R, 10):
        px, py = cx + ux * dist, cy + uy * dist
        if not (0 <= px < P.w and 0 <= py < P.h):
            continue
        prof = [sample(P.D, px - uy * o * R, py + ux * o * R) for o in np.arange(-1.0, 1.0001, 0.04)]
        mx = max(prof)
        if mx > 0:
            widths.append(sum(1 for v in prof if v > mx * 0.5) * 0.04)
    if widths:
        kind = "stick" if sorted(widths)[len(widths) // 2] > 0.25 else "line"
    # Sub-pixel refinement: centroid of the cross profile at many distances, then a robust line fit.
    img = P.D if kind == "stick" else Lw
    r0, r1 = (R * 2.0, R * 12) if kind == "stick" else (R * 1.6, R * 9)
    pts = []
    for i in range(40):
        dist = r0 + (r1 - r0) * i / 39
        px, py = cx + ux * dist, cy + uy * dist
        if not (R <= px < P.w - R and R <= py < P.h - R):
            break
        offs = np.arange(-0.6, 0.6001, 0.03) * R
        prof = np.array([sample(img, px - uy * o, py + ux * o) for o in offs])
        mx = prof.max()
        if mx <= 8:
            continue
        wgt = np.maximum(prof - mx * 0.5, 0)
        if wgt.sum() > 0:
            pts.append((dist, float((offs * wgt).sum() / wgt.sum())))
    if len(pts) >= 6:
        d_ = np.array([p[0] for p in pts]); o_ = np.array([p[1] for p in pts])
        for _ in range(2):
            b, a = np.polyfit(d_, o_, 1)
            res = np.abs(o_ - (a + b * d_))
            keep = res <= np.percentile(res, 80) + 1e-6
            d_, o_ = d_[keep], o_[keep]
        b, a = np.polyfit(d_, o_, 1)
        ang = ang + math.atan(b)
    return (ang + math.pi) % (2 * math.pi) if kind == "stick" else ang % (2 * math.pi), kind


def estimate_radius(rgb, table):
    """Try a range of ball sizes; keep the one whose detections look most like full circles."""
    L = max(table["right"] - table["left"], table["bottom"] - table["top"])
    best = None
    for div in (100, 85, 72, 62, 54, 47, 41, 36):
        R = L / div
        balls, P = detect_v2(rgb, table, R, return_debug=True)
        if not balls:
            continue
        # score: number of balls weighted by their mean ring coverage at that size
        covs = [ring_coverage(P, (x - P.l) * P.s, (y - P.t) * P.s, TARGET_R) for x, y, _ in balls]
        sc = sum(c ** 4 for c in covs)
        if best is None or sc > best[0]:
            best = (sc, R)
    if best is None:
        return None
    # refine around the best with +-6% steps
    R0 = best[1]
    for f in (0.88, 0.94, 1.06, 1.12):
        R = R0 * f
        balls, P = detect_v2(rgb, table, R, return_debug=True)
        covs = [ring_coverage(P, (x - P.l) * P.s, (y - P.t) * P.s, TARGET_R) for x, y, _ in balls]
        sc = sum(c ** 4 for c in covs)
        if sc > best[0]:
            best = (sc, R)
    return best[1]


def _bil(img, x, y):
    h, w = img.shape
    xi, yi = int(math.floor(x)), int(math.floor(y))
    if xi < 0 or yi < 0 or xi >= w - 1 or yi >= h - 1:
        return None
    fx, fy = x - xi, y - yi
    return float(img[yi, xi] * (1 - fx) * (1 - fy) + img[yi, xi + 1] * fx * (1 - fy) +
                 img[yi + 1, xi] * (1 - fx) * fy + img[yi + 1, xi + 1] * fx * fy)


def _run(img, cx, cy, ang, thr, r0, r1, step, max_gap, max_start):
    """Longest run of samples above thr along a ray (gaps up to max_gap samples allowed).
    Returns (length in px, mean value in run, start dist, end dist)."""
    ux, uy = math.cos(ang), math.sin(ang)
    n = int((r1 - r0) / step) + 1
    best = (0.0, 0.0, 0.0, 0.0)
    cur_s = None; gap = 0; acc = []; last_hi = None
    for i in range(n):
        d = r0 + i * step
        v = _bil(img, cx + ux * d, cy + uy * d)
        if v is None:
            break
        if v > thr:
            if cur_s is None:
                if d > max_start:
                    break
                cur_s = d; acc = []
            acc.append(v); last_hi = d; gap = 0
        elif cur_s is not None:
            gap += 1
            if gap > max_gap:
                L = last_hi - cur_s
                if L > best[0]:
                    best = (L, sum(acc) / len(acc), cur_s, last_hi)
                cur_s = None
                if r0 + i * step > max_start:
                    break
    if cur_s is not None and last_hi is not None:
        L = last_hi - cur_s
        if L > best[0]:
            best = (L, sum(acc) / len(acc), cur_s, last_hi)
    return best


def _bar_profile(P, img, cx, cy, ang, a, b, R):
    """Width (in R) and centre colour of a bar along a ray between distances a and b."""
    ux, uy = math.cos(ang), math.sin(ang)
    widths, Ls, Cs = [], [], []
    for d in np.linspace(a, b, 12):
        px, py = cx + ux * d, cy + uy * d
        offs = np.arange(-1.0, 1.0001, 0.02) * R
        prof = [_bil(img, px - uy * o, py + ux * o) for o in offs]
        if any(v is None for v in prof):
            continue
        prof = np.array(prof); mx = prof.max()
        if mx <= 0:
            continue
        widths.append((prof > mx * 0.5).sum() * 0.02)
        i = int(np.argmax(prof)); o = offs[i]
        l = _bil(P.L, px - uy * o, py + ux * o); A = _bil(P.A, px - uy * o, py + ux * o); B = _bil(P.B, px - uy * o, py + ux * o)
        if l is not None:
            Ls.append(l); Cs.append(math.hypot(A, B))
    return widths, Ls, Cs


def detect_stick2(P, cue_xy_full, table):
    """Shot direction from the cue stick (thick bar behind the cue ball) and/or the game's aim line
    (thin line in front), scored together so the direction cannot flip."""
    R = TARGET_R
    cx, cy = (cue_xy_full[0] - P.l) * P.s, (cue_xy_full[1] - P.t) * P.s
    k3 = cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3))
    thick = cv2.morphologyEx(P.D.astype(np.float32), cv2.MORPH_OPEN, k3)       # bars survive, thin lines vanish
    Lp = np.maximum(P.L - P.Lbg, 0).astype(np.float32)
    thin = np.maximum(Lp - cv2.morphologyEx(Lp, cv2.MORPH_OPEN, k3), 0)         # top-hat: thin bright lines only
    thin = cv2.GaussianBlur(thin, (3, 3), 0.6)
    step = R * 0.25
    cands = []
    for i in range(720):
        phi = 2 * math.pi * i / 720
        sL, sM, s0, s1 = _run(thick, cx, cy, phi + math.pi, 14, R * 1.15, R * 18, step, 1, R * 7)
        lL, lM, l0, l1 = _run(thin, cx, cy, phi, 8, R * 1.0, R * 18, step, 3, R * 2.2)
        cands.append((phi, sL, sM, s0, s1, lL, lM, l0, l1))

    def score(c, use_stick=True, use_line=True):
        phi, sL, sM, s0, s1, lL, lM, l0, l1 = c
        ss = sL / R if (use_stick and sL >= 3 * R) else 0.0
        ls = lL / R if (use_line and lL >= 2.5 * R) else 0.0
        return ss * min(sM / 40, 1.5) + 1.3 * ls * min(lM / 25, 1.5), ss > 0, ls > 0

    order = sorted(cands, key=lambda c: -score(c)[0])
    tried = []
    result = None
    for c in order:
        if len(tried) >= 8 or score(c)[0] <= 0:
            break
        phi = c[0]
        if any(abs((phi - t + math.pi) % (2 * math.pi) - math.pi) < math.radians(3) for t in tried):
            continue
        tried.append(phi)
        sc, has_s, has_l = score(c)
        srange = (c[3], c[4]) if has_s else None
        lrange = (c[7], c[8]) if has_l else None
        reinterpret = False
        if srange:
            widths, Ls, Cs = _bar_profile(P, P.D, cx, cy, phi + math.pi, srange[0], srange[1], R)
            if not widths:
                srange = None
            else:
                w30, w50, w70 = np.percentile(widths, [30, 50, 70])
                whitish = Ls and np.median(Ls) > 185 and np.median(Cs) < 30
                if w50 > 0.95 or (w70 - w30) > 0.35:
                    srange = None                          # a row of balls, not a stick
                elif w30 < 0.42 and whitish and not lrange:
                    reinterpret = True                     # thin white line: the game's aim line
                elif w30 < 0.2:
                    reinterpret = not lrange
        if reinterpret:
            phi = (phi + math.pi) % (2 * math.pi)
            lrange, srange = srange, None
        if not srange and not lrange:
            continue
        result = (phi, srange, lrange, reinterpret)
        break
    if result is None:
        return None
    phi, srange, lrange, reinterpret = result
    # joint sub-pixel line fit through stick (behind, negative distances) and line (in front)
    pts = []
    ux, uy = math.cos(phi), math.sin(phi)
    def collect(img, a, b, sign):
        for d in np.arange(a, b, R * 0.25):
            px, py = cx + ux * d * sign, cy + uy * d * sign
            offs = np.arange(-0.6, 0.6001, 0.03) * R
            prof = [_bil(img, px - uy * o, py + ux * o) for o in offs]
            if any(v is None for v in prof):
                continue
            prof = np.array(prof); mx = prof.max()
            if mx <= 6:
                continue
            w_ = np.maximum(prof - mx * 0.5, 0)
            if w_.sum() > 0:
                pts.append((d * sign, float((offs * w_).sum() / w_.sum())))
    if srange:
        collect(thick, srange[0], srange[1], -1)
    if lrange:
        collect(P.D if reinterpret else thin, lrange[0], lrange[1], 1)
    if len(pts) >= 6:
        d_ = np.array([p[0] for p in pts]); o_ = np.array([p[1] for p in pts])
        for _ in range(2):
            b, a = np.polyfit(d_, o_, 1)
            res = np.abs(o_ - (a + b * d_))
            keep = res <= np.percentile(res, 80) + 1e-6
            d_, o_ = d_[keep], o_[keep]
        b, a = np.polyfit(d_, o_, 1)
        phi += math.atan(b)
    return phi % (2 * math.pi), ("stick+line" if srange and lrange else "stick" if srange else "line")
