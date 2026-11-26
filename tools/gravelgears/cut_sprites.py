#!/usr/bin/env python3
"""Cuts Gravel & Gears' sprites out of the owner's art sheets into transparent WebP.

Usage: python3 tools/gravelgears/cut_sprites.py SHEETS_DIR games/src/main/res/drawable-nodpi
Needs opencv-python-headless, pillow and numpy. SHEETS_DIR holds, by the names the owner gave them:
  obstacles_day.png / obstacles_night.png (3 x 3: pine, boulder, log / stump, ramp, berm / mud, gravel, flags)
  heads.png (3 x 3: goat, fox, badger by left, front, right)
  animals_day.jpg (3 x 3: marmot, deer, crow), animals_night.jpg (3 x 3: raccoon, stag, owl),
  animals_exclusive.jpg (skunk 3 poses over 3 firefly frames).
The riders on the trail are drawn from parts in code (GravelGearsRig.kt), so the rider pose strips and pedal sheets
are not cut any more.
Daytime sheets sit on white (the background is flood-filled from the corners, then the anti-aliased edge is
un-blended from white). Night sheets sit on black: the sprite's alpha is its brightness, so glows fade out
over the dark ground.
"""
import sys, cv2, numpy as np
from PIL import Image

def alpha_white(img):
    h, w = img.shape[:2]
    ff = np.zeros((h + 2, w + 2), np.uint8)
    for seed in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
        if ff[seed[1] + 1, seed[0] + 1] == 0 and img[seed[1], seed[0]].min() > 225:
            cv2.floodFill(img.copy(), ff, seed, (0, 0, 0), (14,) * 3, (14,) * 3,
                          4 | cv2.FLOODFILL_FIXED_RANGE | cv2.FLOODFILL_MASK_ONLY | (255 << 8))
    bg = ff[1:-1, 1:-1] > 0
    # white pockets inside a sprite (between wheel spokes) stay; big clear gaps are background
    white = ((img.min(axis=2) > 243) & ~bg).astype(np.uint8)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(white, connectivity=4)
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] > 2500:
            bg |= lab == i
    alpha = np.where(bg, 0, 255).astype(np.float32)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    band = (cv2.dilate(bg.astype(np.uint8), k) > 0) & (cv2.erode(bg.astype(np.uint8), k) == 0)
    rgb = img[..., ::-1].astype(np.float32)
    a = np.clip((255 - rgb.min(axis=2)) / 255.0 * 1.6, 0, 1)
    alpha[band] = a[band] * 255
    out = rgb.copy()
    aa = np.maximum(alpha / 255.0, 1e-3)[..., None]
    un = np.clip((rgb - (1 - aa) * 255) / aa, 0, 255)
    out[band] = un[band]
    return np.dstack([out, alpha]).astype(np.uint8)

def alpha_black(img):
    rgb = img[..., ::-1].astype(np.float32)
    a = np.clip(rgb.max(axis=2) / 70.0, 0, 1)
    out = np.clip(rgb / np.maximum(a, 1e-3)[..., None], 0, 255)
    out[a < 0.02] = 0
    return np.dstack([out, a * 255]).astype(np.uint8)

def boxes(rgba, cols, rows, thresh=24, min_h=0):
    """One box per grid cell: the union of every shape whose centre falls in that cell."""
    h, w = rgba.shape[:2]
    m = (rgba[..., 3] > thresh).astype(np.uint8) * 255
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15)))
    cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    out = {}
    for c in cs:
        if cv2.contourArea(c) < 150:
            continue
        x, y, bw, bh = cv2.boundingRect(c)
        if bh < min_h:
            continue  # a line of caption text, not a sprite
        key = (min(rows - 1, int((y + bh / 2) / h * rows)), min(cols - 1, int((x + bw / 2) / w * cols)))
        if key in out:
            ox, oy, ow, oh = out[key]
            x0, y0 = min(ox, x), min(oy, y)
            out[key] = (x0, y0, max(ox + ow, x + bw) - x0, max(oy + oh, y + bh) - y0)
        else:
            out[key] = (x, y, bw, bh)
    assert len(out) == cols * rows, (len(out), sorted(out))
    return [out[(r, c)] for r in range(rows) for c in range(cols)]

def save(rgba, box, path, height, mask=None):
    x, y, bw, bh = box
    crop = rgba[y:y + bh, x:x + bw].copy()
    if mask is not None:
        crop = mask(crop)
    ys, xs = np.nonzero(crop[..., 3] > 6)
    crop = crop[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    im = Image.fromarray(crop, "RGBA")
    s = height / im.height
    im = im.resize((max(1, round(im.width * s)), height), Image.Resampling.LANCZOS)
    im.save(path, quality=88, method=6)

def load(path, black):
    img = cv2.imread(path)
    return alpha_black(img) if black else alpha_white(img)

def export(sheets, res):
    d = lambda n: f"{sheets}/{n}"
    # obstacles: tall props share a scale so a pine is taller than a boulder
    names = ["pine", "boulder", "log", "stump", "ramp", "berm", "mud", "gravel", "flags"]
    heights = [360, 220, 200, 230, 240, 220, 180, 160, 200]
    for suffix, black in [("day", False), ("night", True)]:
        rgba = load(d(f"obstacles_{suffix}.png"), black)
        for name, b, hgt in zip(names, boxes(rgba, 3, 3), heights):
            save(rgba, b, f"{res}/gg_{suffix}_{name}.webp", hgt)
    rgba = load(d("heads.png"), False)
    bs = boxes(rgba, 3, 3)
    for i, who in enumerate(["gravel", "spokes", "knobby"]):
        for j, face in enumerate(["left", "front", "right"]):
            save(rgba, bs[i * 3 + j], f"{res}/gg_head_{who}_{face}.webp", 160)
    # animals
    rgba = load(d("animals_day.jpg"), False)
    bs = boxes(rgba, 3, 3)
    for n, b in zip(["marmot_idle", "marmot_run", "marmot_tumble", "deer_stand", "deer_crouch", "deer_leap",
                     "crow_a", "crow_b", "crow_c"], bs):
        save(rgba, b, f"{res}/gg_{n}.webp", 200)
    rgba = load(d("animals_night.jpg"), True)
    bs = boxes(rgba, 3, 3)
    for n, b in zip(["raccoon_idle", "raccoon_run", "raccoon_tumble", "stag_stand", "stag_freeze", "stag_leap",
                     "owl_a", "owl_b", "owl_c"], bs):
        save(rgba, b, f"{res}/gg_{n}.webp", 200)
    rgba = load(d("animals_exclusive.jpg"), True)
    bs = boxes(rgba, 3, 2)
    for n, b in zip(["skunk_walk", "skunk_alert", "skunk_spray", "firefly_a", "firefly_b", "firefly_c"], bs):
        save(rgba, b, f"{res}/gg_{n}.webp", 200)

if __name__ == "__main__":
    export(sys.argv[1], sys.argv[2])
