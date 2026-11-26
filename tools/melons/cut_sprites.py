#!/usr/bin/env python3
"""Cuts Bitter Melons' sprites out of the owner's art sheets (white backgrounds) into transparent WebP.

Usage: python3 tools/melons/cut_sprites.py SHEETS_DIR games/src/main/res/drawable-nodpi
Needs opencv-python-headless and pillow. SHEETS_DIR holds the 2048 px sheets rage.jpg (4 x 4 melons: rage
across, poses down), enemies.jpg (5 enemies, whole then damaged, with captions), blocks.jpg (glass, wood,
stone and steel across, wear down), hazards.jpg (3 x 2) and melons.jpg (4 x 4: Bitter Melon, Split Cantaloupe,
Heavy Honeydew and Overripe Melon across, poses down), and the 3168 x 1344 backgrounds world1.jpg to world4.jpg.
The lit fuses (the Overripe Melon, and the full-rage melon once landed) and their sparks are painted out: everything outside its body's outline goes.

Based on the owner's extract_sprites: the background is flood-filled from the corners against pure white
(a fixed range, so light fills inside a sprite are kept), large white gaps inside a sprite are cleared too,
and the anti-aliased edge is un-blended from white so no white fringe shows on the sky.
"""
import cv2, numpy as np, sys
from PIL import Image

HOLE = 6000  # sheet px: larger white areas inside a sprite are gaps, not teeth or eye whites

def alpha_of(img):
    h, w = img.shape[:2]
    ff = np.zeros((h + 2, w + 2), np.uint8)
    for seed in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
        if ff[seed[1] + 1, seed[0] + 1] == 0 and img[seed[1], seed[0]].min() > 230:
            cv2.floodFill(img.copy(), ff, seed, (0, 0, 0), (12,) * 3, (12,) * 3, 4 | cv2.FLOODFILL_FIXED_RANGE | cv2.FLOODFILL_MASK_ONLY | (255 << 8))
    bg = ff[1:-1, 1:-1] > 0
    white = ((img.min(axis=2) > 243) & ~bg).astype(np.uint8)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(white, connectivity=4)
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] > HOLE:
            bg |= lab == i
    alpha = np.where(bg, 0, 255).astype(np.float32)
    # A 2 px band each side of the edge: un-blend the anti-aliased pixels from white.
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    band = (cv2.dilate(bg.astype(np.uint8), k) > 0) & (cv2.erode(bg.astype(np.uint8), k) == 0)
    rgb = img[..., ::-1].astype(np.float32)
    a = (255 - rgb.min(axis=2)) / 255.0
    a = np.clip(a * 1.6, 0, 1)
    alpha[band] = a[band] * 255
    out = rgb.copy()
    aa = np.maximum(alpha / 255.0, 1e-3)[..., None]
    un = np.clip((rgb - (1 - aa) * 255) / aa, 0, 255)
    out[band] = un[band]
    return np.dstack([out, alpha]).astype(np.uint8)

def cells(img, cols, rows):
    """Groups the sheet's shapes into a cols x rows grid: big shapes set each cell,
    nearby small ones (sparkles, steam) join it, and lines of caption text are left out."""
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    _, m = cv2.threshold(g, 245, 255, cv2.THRESH_BINARY_INV)
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9)))
    cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    bs = [cv2.boundingRect(c) for c in cs if cv2.contourArea(c) > 300]
    big = sorted([b for b in bs if b[2] * b[3] > 60000], key=lambda b: b[1] + b[3] / 2)
    assert len(big) == cols * rows, (len(big), big)
    big = [sorted(big[r * cols:(r + 1) * cols], key=lambda b: b[0]) for r in range(rows)]
    big = [b for row in big for b in row]
    out = [list(b) for b in big]
    for b in bs:
        if b in big or b[2] > 3 * b[3]:
            continue
        cx, cy = b[0] + b[2] / 2, b[1] + b[3] / 2
        d = [max(abs(cx - (B[0] + B[2] / 2)) - B[2] / 2, abs(cy - (B[1] + B[3] / 2)) - B[3] / 2) for B in big]
        i = min(range(len(big)), key=lambda k: d[k])
        if d[i] < 60:
            x0, y0 = min(out[i][0], b[0]), min(out[i][1], b[1])
            out[i] = [x0, y0, max(out[i][0] + out[i][2], b[0] + b[2]) - x0, max(out[i][1] + out[i][3], b[1] + b[3]) - y0]
    return [(x - 4, y - 4, bw + 8, bh + 8) for x, y, bw, bh in out]

def save(rgba, box, path, size, square):
    x, y, bw, bh = box
    crop = rgba[y:y + bh, x:x + bw]
    ys, xs = np.nonzero(crop[..., 3] > 8)
    crop = crop[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    im = Image.fromarray(crop, "RGBA")
    if square:
        im.thumbnail((size - 8, size - 8), Image.Resampling.LANCZOS)
        c = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        c.paste(im, ((size - im.width) // 2, (size - im.height) // 2))
        im = c
    else:
        im = im.resize((size, size), Image.Resampling.LANCZOS)
    im.save(path, optimize=True)

def grid(sheet, prefix, cols, rows, size, square=True, boxes=None):
    img = cv2.imread(sheet)
    rgba = alpha_of(img)
    h, w = img.shape[:2]
    if boxes is None:
        boxes = cells(img, cols, rows)
    for i, b in enumerate(boxes):
        save(rgba, b, f"{prefix}_{i // cols}{i % cols}.png", size, square)

def body_only(rgba):
    """Keeps a melon's body and clears what sticks out above it (a fuse, sparks): the body is the ellipse as wide as
    the sprite's widest row, centred on that row, reaching down to the sprite's bottom; above the centre only
    pixels inside it are kept, and below it the side sparks go too."""
    a = rgba[..., 3] > 8
    widths = a.sum(axis=1)
    cy = int(np.argmax(widths))
    xs = np.nonzero(a[cy])[0]
    cx, rx = (xs.min() + xs.max()) / 2, (xs.max() - xs.min()) / 2
    bottom = np.nonzero(a.any(axis=1))[0].max()
    ry = bottom - cy
    yy, xx = np.mgrid[0:a.shape[0], 0:a.shape[1]]
    inside = ((xx - cx) / (rx * 1.02)) ** 2 + ((yy - cy) / (ry * 1.05)) ** 2 <= 1
    out = rgba.copy()
    out[..., 3] = np.where(inside, out[..., 3], 0)
    return out

def export(sheets, res):
    """Writes the game's sprites: each at a quarter of its sheet size, so one sheet's sprites share a scale."""
    def put(rgba, box, name, scale=0.25, size=None, keep=None):
        x, y, bw, bh = box
        crop = rgba[max(0, y):y + bh, max(0, x):x + bw]
        if keep:
            crop = keep(crop)
        ys, xs = np.nonzero(crop[..., 3] > 8)
        crop = crop[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
        im = Image.fromarray(crop, "RGBA")
        im = im.resize(size or (round(im.width * scale), round(im.height * scale)), Image.Resampling.LANCZOS)
        im.save(f"{res}/melons_{name}.webp", quality=86, method=6)
    img = cv2.imread(f"{sheets}/rage.jpg"); rgba = alpha_of(img); b = cells(img, 4, 4)
    for row, pose in [(0, "ready"), (2, "landed"), (3, "flying")]:
        for col in range(4):
            # The full-rage melon's landed pose has a lit fuse too.
            keep = body_only if (row, col) == (2, 3) else None
            put(rgba, b[row * 4 + col], f"melon_{pose}_{col}", keep=keep)
    img = cv2.imread(f"{sheets}/melons.jpg"); rgba = alpha_of(img); b = cells(img, 4, 4)
    for col, kind in [(1, "split"), (2, "heavy"), (3, "overripe")]:
        for row, pose in [(0, "ready"), (2, "landed"), (3, "flying")]:
            put(rgba, b[row * 4 + col], f"{kind}_{pose}", keep=body_only if kind == "overripe" else None)
    img = cv2.imread(f"{sheets}/enemies.jpg"); rgba = alpha_of(img)
    en = [(67, 200, 313, 336), (456, 125, 350, 412), (849, 185, 373, 352), (1243, 54, 395, 483), (1706, 146, 264, 391),
          (32, 1083, 392, 382), (455, 998, 350, 466), (828, 1078, 398, 388), (1288, 1001, 305, 464), (1656, 1073, 350, 392)]
    for i, name in [(0, "app"), (1, "bot"), (2, "prompt"), (3, "ad"), (4, "battery")]:
        for j, state in [(0, ""), (5, "_hit")]:
            x, y, bw, bh = en[i + j]
            put(rgba, (x - 6, y - 6, bw + 12, bh + 12), f"enemy_{name}{state}")
    img = cv2.imread(f"{sheets}/blocks.jpg"); rgba = alpha_of(img)
    for c, name in enumerate(["glass", "wood", "stone", "steel"]):
        for r in range(4):
            put(rgba, (c * 512, r * 512, 512, 512), f"block_{name}_{r}", size=(128, 128))
    img = cv2.imread(f"{sheets}/hazards.jpg"); rgba = alpha_of(img); b = cells(img, 3, 2)
    for i, name in enumerate(["slingshot", "banner", "fan", "beam", "drone", "shield"]):
        put(rgba, b[i], name)
    for n in range(1, 5):
        bg = Image.open(f"{sheets}/world{n}.jpg").convert("RGB").resize((1600, 679), Image.Resampling.LANCZOS)
        bg.save(f"{res}/melons_world{n}.webp", quality=80, method=6)

if __name__ == "__main__":
    export(sys.argv[1], sys.argv[2])
