"""
Convert hand-coded pixel-art Dog SVG sprites into smooth-path traced SVGs
matching the style of Cat / Bird / Ducky (potrace output wrapped in a
`scale(0.125 0.125) translate(0,N) scale(1,-1)` group).

Pipeline per input SVG:
  1. Render to a high-resolution transparent PNG via Inkscape CLI
     (viewBox * 0.125 inverse -> inner pixel space matches Cat's 256-unit
     convention, e.g. 48x48 viewBox -> 384x384 PNG).
  2. Quantize the PNG to a small color palette using Pillow (median cut).
  3. For each palette color, run potrace on the binary mask and convert
     the Bezier segments to an SVG path 'd' string in inner-pixel space.
  4. Emit an SVG that wraps each color's <path> in a <g> with the same
     potrace flip-and-scale transform Cat/Bird/Ducky use, so coordinates
     and rendering match those pets exactly.

Run from repo root:
    python tools/trace-dog-sprites.py [--only PATTERN]
"""
from __future__ import annotations

import argparse
import io
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter
import potrace

INKSCAPE = r"C:\Program Files\Inkscape\bin\inkscape.exe"
REPO_ROOT = Path(__file__).resolve().parent.parent
SPRITES_ROOT = REPO_ROOT / "src" / "main" / "resources" / "Sprites" / "Dog"

# Files to trace. (relative-to-SPRITES_ROOT, viewBox-size).
# Dance/LayDown/PlayBow are intentionally excluded — they are regenerated
# from Idle by the existing gen-*-frames.ps1 scripts.
DEFAULT_TARGETS: list[tuple[str, int]] = (
    [(f"Idle/tile00{i}.svg", 48) for i in range(4)]
    + [(f"Walk/Left/tile00{i}.svg", 48) for i in range(4)]
    + [(f"Walk/Right/tile00{i}.svg", 48) for i in range(4)]
    + [(f"Run/Left/tile00{i}.svg", 48) for i in range(4)]
    + [(f"Run/Right/tile00{i}.svg", 48) for i in range(4)]
    + [(f"Sit/tile00{i}.svg", 48) for i in range(4)]
    + [("Pee/pee.svg", 48)]
    + [("Scratch/scratch.svg", 48)]
)

# Inner-pixel grid. Render at 16 px per viewBox unit (48 * 16 = 768) so a
# 1-pixel detail in the source is still 16 px wide after rendering and
# survives the Gaussian blur applied before tracing. The output path
# coordinates land in 0..(viewbox*PX_PER_UNIT) and are wrapped in a
# `scale(1/PX_PER_UNIT)` transform that matches Cat/Bird/Ducky's style.
PX_PER_UNIT = 16
# Gaussian-blur radius applied to the rendered PNG before quantization.
# Just enough to round axis-aligned pixel-block corners without erasing
# 1-px source features (which are PX_PER_UNIT px wide after upscale).
BLUR_RADIUS = 5.5


_HEX_RE = re.compile(r'fill="#([0-9a-fA-F]{6})"')
# The `belly` linearGradient in Dog/Idle blends #f7eede -> #c89a6a; trace
# treats blended pixels as their nearest palette neighbour, but we still
# need to feed those two endpoints into the palette so the body reads as
# a single mid-tone region rather than getting split into the wrong bucket.
_GRADIENT_STOPS_RE = re.compile(r'stop-color="#([0-9a-fA-F]{6})"')


def render_png(svg_path: Path, png_path: Path, size: int) -> None:
    cmd = [
        INKSCAPE,
        "--export-type=png",
        f"--export-width={size}",
        f"--export-height={size}",
        "--export-background-opacity=0",
        f"--export-filename={png_path}",
        str(svg_path),
    ]
    subprocess.run(cmd, check=True, capture_output=True)


def extract_palette(svg_path: Path) -> list[tuple[int, int, int]]:
    """Pull every literal fill="#rrggbb" and gradient stop-color from the
    source SVG. Returns deduped, in first-seen order.
    """
    text = svg_path.read_text(encoding="utf-8", errors="ignore")
    colors: list[tuple[int, int, int]] = []
    seen: set[tuple[int, int, int]] = set()
    for m in list(_HEX_RE.finditer(text)) + list(_GRADIENT_STOPS_RE.finditer(text)):
        h = m.group(1).lower()
        rgb = (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))
        if rgb not in seen:
            seen.add(rgb)
            colors.append(rgb)
    return colors


def snap_to_palette(
    img: Image.Image, palette: list[tuple[int, int, int]]
) -> list[tuple[tuple[int, int, int], np.ndarray]]:
    """Assign every opaque pixel to its nearest palette colour (squared
    Euclidean RGB distance). Returns (rgb, mask) per palette colour with
    non-trivial coverage.
    """
    arr = np.asarray(img.convert("RGBA"))
    rgb = arr[..., :3].astype(np.int32)
    alpha = arr[..., 3]
    opaque = alpha >= 96  # blur softens edges; keep mostly-opaque pixels
    pal = np.asarray(palette, dtype=np.int32)  # (P,3)
    # Distance from each pixel to each palette colour: (H,W,P).
    diff = rgb[..., None, :] - pal[None, None, :, :]
    dist2 = (diff * diff).sum(axis=-1)
    nearest = dist2.argmin(axis=-1)
    nearest[~opaque] = -1

    results: list[tuple[tuple[int, int, int], np.ndarray]] = []
    for i, rgb_tuple in enumerate(palette):
        mask = nearest == i
        if mask.sum() < 16:
            continue
        results.append((rgb_tuple, mask))
    # Larger underlying regions first so smaller details sit on top.
    results.sort(key=lambda t: -int(t[1].sum()))
    return results


def trace_mask(mask: np.ndarray) -> potrace.Path:
    # potracer's Bitmap.__init__ unconditionally calls invert() on its
    # input, so to trace the True pixels of `mask` we must hand it the
    # inverse first. Passing a plain bool array avoids the threshold
    # codepath that would otherwise misinterpret 0/1 uint values.
    #
    # We also flip the mask vertically so that, after the Cat-style
    # `translate(0,N) scale(1,-1)` wrapper transform flips it back, the
    # rendered output is right-side-up.
    flipped = np.flipud(~mask.astype(bool))
    bmp = potrace.Bitmap(flipped)
    return bmp.trace(turdsize=2, alphamax=1.0, opticurve=1, opttolerance=0.2)


def path_to_d(path: potrace.Path) -> str:
    """Convert potrace Path object to an SVG path 'd' string.
    Coordinates are emitted as-is (mask pixel space). The caller is
    responsible for placing them inside a flip+scale transform group.
    """
    def xy(p):
        return p.x, p.y

    parts: list[str] = []
    for curve in path:
        sx, sy = xy(curve.start_point)
        parts.append(f"M{sx:.2f} {sy:.2f}")
        for segment in curve:
            if segment.is_corner:
                cx, cy = xy(segment.c)
                ex, ey = xy(segment.end_point)
                parts.append(f"L{cx:.2f} {cy:.2f} L{ex:.2f} {ey:.2f}")
            else:
                c1x, c1y = xy(segment.c1)
                c2x, c2y = xy(segment.c2)
                ex, ey = xy(segment.end_point)
                parts.append(
                    f"C{c1x:.2f} {c1y:.2f} {c2x:.2f} {c2y:.2f} {ex:.2f} {ey:.2f}"
                )
        parts.append("Z")
    return " ".join(parts)


def trace_one(svg_in: Path, svg_out: Path, viewbox: int) -> None:
    inner = viewbox * PX_PER_UNIT  # e.g. 48 * 8 = 384
    palette = extract_palette(svg_in)
    if not palette:
        raise RuntimeError(f"no palette colors found in {svg_in}")
    with tempfile.TemporaryDirectory() as td:
        png_path = Path(td) / "render.png"
        render_png(svg_in, png_path, inner)
        img = Image.open(png_path).convert("RGBA")
        if img.size != (inner, inner):
            img = img.resize((inner, inner), Image.NEAREST)
        img = img.filter(ImageFilter.GaussianBlur(radius=BLUR_RADIUS))
        groups = snap_to_palette(img, palette)

    scale = 1.0 / PX_PER_UNIT
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {viewbox} {viewbox}" width="{viewbox}" height="{viewbox}">',
    ]
    transform = f"scale({scale:.6f} {scale:.6f}) translate(0.000000,{inner}.000000) scale(1.000000,-1.000000)"
    for (r, g, b), mask in groups:
        path = trace_mask(mask)
        d = path_to_d(path)
        if not d:
            continue
        hex_color = f"#{r:02x}{g:02x}{b:02x}"
        lines.append(
            f'  <g transform="{transform}" fill="{hex_color}" stroke="none"><path d="{d}"/></g>'
        )
    lines.append("</svg>")
    svg_out.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--only",
        default="",
        help="Substring filter applied to the relative sprite path.",
    )
    args = ap.parse_args()

    targets = [t for t in DEFAULT_TARGETS if args.only in t[0]]
    if not targets:
        print(f"No targets matched --only={args.only!r}", file=sys.stderr)
        return 2

    for rel, vb in targets:
        svg_in = SPRITES_ROOT / rel
        if not svg_in.exists():
            print(f"  SKIP  {rel} (missing)")
            continue
        svg_out = svg_in  # overwrite in place
        trace_one(svg_in, svg_out, vb)
        print(f"  done  {rel}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
