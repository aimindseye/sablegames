#!/usr/bin/env python3
"""Turns the Tartarus Breach handoff art into the WebP files the game bundles.

Usage: python3 tools/tartarus/cut_sprites.py HANDOFF_DIR games/src/main/res/drawable-nodpi
HANDOFF_DIR is the unpacked Tartarus_Breach_Dev_Handoff_v3 folder. Needs pillow and cairosvg
(for the small SVG icons). Everything is shrunk to what the phone screens need: a square 720 x 720 or
1440 x 1440 screen never shows a sprite larger than about 60 % of its width.
"""
import io
import sys
from pathlib import Path

import cairosvg
from PIL import Image


def save(im, out, name, longest, quality=82):
    im = im.copy()
    im.thumbnail((longest, longest), Image.LANCZOS)
    im.save(out / f"{name}.webp", "WEBP", quality=quality, method=6)


def main(src, out):
    src, out = Path(src), Path(out)
    out.mkdir(parents=True, exist_ok=True)
    art = src / "05_illustrated_assets"
    for i, gate in enumerate(["asphodel", "phlegethon", "labyrinth", "abyssal_seal"], 1):
        save(Image.open(art / "backgrounds" / f"bg_gate_0{i}_{gate}.png").convert("RGB"), out, f"tb_bg_{i}", 1024, 78)
    for kind, longest in (("enemies", 384), ("bosses", 512)):
        for f in sorted((art / "sliced_pngs" / kind).glob("*.png")):
            save(Image.open(f).convert("RGBA"), out, f"tb_{f.stem}", longest)
    for f in sorted((art / "sliced_pngs" / "weapons").glob("*.png")):
        save(Image.open(f).convert("RGBA"), out, f"tb_{f.stem}", 512)
    # The Spartan portrait is cut from the owner's arena mockup, centre of the status bar.
    mock = Image.open(src / "02_ui_mockups" / "screen_02_arena_hud.png").convert("RGB")
    save(mock.crop((418, 824, 608, 1000)), out, "tb_portrait", 192, 88)
    svg = src / "03_sprites_vector" / "svg"
    icons = {f"tb_boon_{p.stem.split('_')[1]}": p for p in sorted((svg / "boons").glob("boon_*.svg"))}
    for name, p in icons.items():
        png = cairosvg.svg2png(url=str(p), output_width=128, output_height=128)
        save(Image.open(io.BytesIO(png)).convert("RGBA"), out, name, 128, 90)


if __name__ == "__main__":
    main(*sys.argv[1:3])
