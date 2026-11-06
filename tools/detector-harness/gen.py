"""Synthetic top-down pool screenshots with ground truth, styled like popular mobile 8-ball games."""
import math
import random

import cv2
import numpy as np

FELTS = [  # RGB
    (34, 120, 64), (28, 100, 170), (20, 120, 130), (150, 40, 50), (90, 50, 140),
    (60, 90, 120), (40, 140, 90), (25, 70, 140),
]
BALL_COLORS = [  # 1..7 (RGB): yellow, blue, red, purple, orange, green, maroon
    (250, 200, 30), (30, 70, 200), (210, 30, 35), (110, 40, 150), (245, 120, 20), (20, 130, 60), (130, 30, 30),
]


def shade_disk(img, cx, cy, r, base, stripe=False, number=None, angle=0.0, outline=True):
    h, w = img.shape[:2]
    x0, x1 = max(0, int(cx - r - 2)), min(w, int(cx + r + 3))
    y0, y1 = max(0, int(cy - r - 2)), min(h, int(cy + r + 3))
    ys, xs = np.mgrid[y0:y1, x0:x1].astype(np.float32)
    dx, dy = xs - cx, ys - cy
    d = np.sqrt(dx * dx + dy * dy)
    inside = d <= r
    alpha = np.clip(r + 0.5 - d, 0, 1)  # anti-aliased edge
    col = np.zeros((y1 - y0, x1 - x0, 3), np.float32)
    col[:] = base
    if stripe:
        # white ball with a coloured band (band direction = angle)
        ca, sa = math.cos(angle), math.sin(angle)
        along = -dx * sa + dy * ca
        white = np.abs(along) > r * 0.52
        col[white] = (245, 245, 240)
    if number is not None:
        ndist = np.sqrt((dx + r * 0.05) ** 2 + (dy + r * 0.05) ** 2)
        col[ndist < r * 0.42] = (250, 250, 245)
    # 3D shading: darker towards the rim, highlight top-left
    rim = np.clip(d / r, 0, 1)
    shade = 1.0 - 0.45 * rim ** 2.2
    hl = np.exp(-(((dx + r * 0.35) ** 2 + (dy + r * 0.35) ** 2) / (2 * (r * 0.22) ** 2)))
    col = col * shade[..., None] + 255 * 0.55 * hl[..., None]
    if outline:
        col[(d > r - 1.3) & inside] *= 0.35
    col = np.clip(col, 0, 255)
    roi = img[y0:y1, x0:x1].astype(np.float32)
    a = alpha[..., None]
    img[y0:y1, x0:x1] = (roi * (1 - a) + col * a).astype(np.uint8)
    if number is not None and r >= 12:
        fs = r / 40.0
        txt = str(number)
        (tw, th), _ = cv2.getTextSize(txt, cv2.FONT_HERSHEY_SIMPLEX, fs, 1)
        cv2.putText(img, txt, (int(cx - tw / 2 - r * 0.05), int(cy + th / 2 - r * 0.05)),
                    cv2.FONT_HERSHEY_SIMPLEX, fs, (20, 20, 20), max(1, int(r / 18)), cv2.LINE_AA)


def make_scene(seed, w=2400, h=1080):
    rng = random.Random(seed)
    img = np.zeros((h, w, 3), np.uint8)
    # background UI: dark gradient + some panels
    img[:] = (rng.randint(10, 50), rng.randint(10, 40), rng.randint(20, 60))
    felt = np.array(rng.choice(FELTS), np.float32) * rng.uniform(0.85, 1.15)
    felt = np.clip(felt, 0, 255)

    tw = rng.randint(1500, 1900)
    th = tw // 2
    left = (w - tw) // 2 + rng.randint(-60, 60)
    top = (h - th) // 2 + rng.randint(-30, 50)
    right, bottom = left + tw, top + th
    cush = rng.randint(28, 50)
    wood = rng.randint(30, 60)
    wood_col = (rng.randint(60, 120), rng.randint(35, 70), rng.randint(15, 40))
    cv2.rectangle(img, (left - cush - wood, top - cush - wood), (right + cush + wood, bottom + cush + wood), wood_col, -1)
    cush_col = tuple(int(c * rng.uniform(0.6, 0.85)) for c in felt)
    cv2.rectangle(img, (left - cush, top - cush), (right + cush, bottom + cush), cush_col, -1)

    # felt with vignette + noise
    ys, xs = np.mgrid[0:th, 0:tw].astype(np.float32)
    v = 1.08 - 0.25 * (((xs - tw / 2) / (tw / 2)) ** 2 + ((ys - th / 2) / (th / 2)) ** 2) * rng.uniform(0.4, 1.0)
    felt_img = felt[None, None, :] * v[..., None]
    felt_img += np.random.RandomState(seed).normal(0, 3, felt_img.shape)
    img[top:bottom, left:right] = np.clip(felt_img, 0, 255).astype(np.uint8)
    # head string / spot markings sometimes
    if rng.random() < 0.5:
        x = left + tw // 4
        cv2.line(img, (x, top), (x, bottom), tuple(int(c * 1.2) for c in felt), 2)

    r = rng.uniform(16, 30)
    pr = r * rng.uniform(1.6, 2.0)
    pockets = [(left, top), (right, top), (left, bottom), (right, bottom), ((left + right) // 2, top - int(r * 0.4)),
               ((left + right) // 2, bottom + int(r * 0.4))]
    for (px, py) in pockets:
        cv2.circle(img, (int(px), int(py)), int(pr), (8, 8, 8), -1, cv2.LINE_AA)

    # balls
    n_balls = rng.randint(2, 15)
    ids = [0, 8] + rng.sample([i for i in range(1, 16) if i != 8], n_balls - 1)
    ids = ids[:n_balls]
    placed = []

    def free(x, y, gap=0.0):
        if not (left + r <= x <= right - r and top + r <= y <= bottom - r):
            return False
        if any(math.hypot(x - a, y - b) < 2 * r + gap for (a, b, _) in placed):
            return False
        if any(math.hypot(x - a, y - b) < pr + r for (a, b) in pockets):
            return False
        return True

    rack = rng.random() < 0.25 and len(ids) >= 10
    if rack:
        ax, ay = left + tw * 0.72, top + th / 2
        k = 0
        rest = [i for i in ids if i != 0]
        for row in range(5):
            for j in range(row + 1):
                if k >= len(rest):
                    break
                x = ax + row * r * math.sqrt(3) + rng.uniform(-0.3, 0.3)
                y = ay + (j - row / 2) * 2 * r + rng.uniform(-0.3, 0.3)
                placed.append((x, y, rest[k])); k += 1
        cx, cy = left + tw * 0.25, top + th / 2 + rng.uniform(-50, 50)
        placed.append((cx, cy, 0))
    else:
        for bid in ids:
            for _ in range(300):
                if placed and rng.random() < 0.3:
                    # touching an existing ball
                    a, b, _ = rng.choice(placed)
                    ang = rng.uniform(0, 2 * math.pi)
                    x, y = a + math.cos(ang) * (2 * r + 0.5), b + math.sin(ang) * (2 * r + 0.5)
                    if free(x, y, -1.0):
                        break
                elif rng.random() < 0.15:
                    # touching a cushion
                    side = rng.randint(0, 3)
                    x = rng.uniform(left + r, right - r); y = rng.uniform(top + r, bottom - r)
                    if side == 0: x = left + r
                    if side == 1: x = right - r
                    if side == 2: y = top + r
                    if side == 3: y = bottom - r
                    if free(x, y):
                        break
                else:
                    x = rng.uniform(left + r, right - r); y = rng.uniform(top + r, bottom - r)
                    if free(x, y, 2):
                        break
            else:
                continue
            placed.append((x, y, bid))

    # shadows first
    sh = img.copy()
    for (x, y, _) in placed:
        cv2.circle(sh, (int(x + r * 0.15), int(y + r * 0.2)), int(r * 1.02), (0, 0, 0), -1, cv2.LINE_AA)
    img = cv2.addWeighted(img, 0.65, sh, 0.35, 0) if rng.random() < 0.7 else img
    outline = rng.random() < 0.5
    numbers = rng.random() < 0.8
    for (x, y, bid) in placed:
        if bid == 0:
            shade_disk(img, x, y, r, (245, 245, 240), outline=outline)
        elif bid == 8:
            shade_disk(img, x, y, r, (25, 25, 25), number=8 if numbers else None, outline=outline)
        elif bid < 8:
            shade_disk(img, x, y, r, BALL_COLORS[bid - 1], number=bid if numbers else None, outline=outline)
        else:
            shade_disk(img, x, y, r, BALL_COLORS[bid - 9], stripe=True, number=bid if numbers else None,
                       angle=rng.uniform(0, math.pi), outline=outline)

    # game's own aim line + cue stick (distractors)
    cue = [p for p in placed if p[2] == 0]
    stick = None
    if cue and rng.random() < 0.6:
        cx, cy, _ = cue[0]
        ang = rng.uniform(0, 2 * math.pi)
        dx, dy = math.cos(ang), math.sin(ang)
        L = rng.uniform(200, 600)
        cv2.line(img, (int(cx + dx * r * 1.1), int(cy + dy * r * 1.1)), (int(cx + dx * L), int(cy + dy * L)),
                 (240, 240, 240), 2, cv2.LINE_AA)
        cv2.circle(img, (int(cx + dx * L), int(cy + dy * L)), int(r), (240, 240, 240), 2, cv2.LINE_AA)
        # stick on the other side
        g = r * rng.uniform(0.3, 1.5)
        s0 = (cx - dx * (r + g), cy - dy * (r + g))
        s1 = (cx - dx * (r + g + 900), cy - dy * (r + g + 900))
        cv2.line(img, (int(s0[0]), int(s0[1])), (int(s1[0]), int(s1[1])), (205, 160, 100), int(r * 0.45), cv2.LINE_AA)
        cv2.line(img, (int(s0[0]), int(s0[1])), (int(s0[0] - dx * r), int(s0[1] - dy * r)), (240, 240, 230),
                 int(r * 0.45), cv2.LINE_AA)
        stick = ang
    img = cv2.GaussianBlur(img, (3, 3), 0.7)
    truth = [(x, y, bid) for (x, y, bid) in placed]
    table = dict(left=left, top=top, right=right, bottom=bottom, pockets=pockets, pr=pr)
    return img, truth, table, r, stick


if __name__ == "__main__":
    for s in range(6):
        im, t, tb, r, st = make_scene(s)
        cv2.imwrite(f"/home/claude/harness/scene{s}.png", cv2.cvtColor(im, cv2.COLOR_RGB2BGR))
    print("ok")
