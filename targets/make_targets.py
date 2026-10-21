"""Printable calibration targets and speckle patterns for the DIC apps.

Print at 100 % ("actual size", no fit-to-page). Check the 100 mm ruler on each page with a caliper
and enter the MEASURED square size in the calibration app.
ChArUco boards use dictionary DICT_5X5_250, marker = 0.7 × square, OpenCV default (non-legacy) layout —
the presets below must match BoardPreset in the calibration app.
"""
import cv2, numpy as np
from PIL import Image, ImageDraw, ImageFont
import os, sys

DPI = 600
MM = DPI / 25.4
A4 = (210, 297)
OUT = os.path.dirname(os.path.abspath(__file__))

# name, squaresX, squaresY, square mm, field of view it suits
PRESETS = [
    ("CH-L25", 10, 7, 25.0, "large FOV 150–400 mm"),
    ("CH-M12", 11, 8, 12.0, "medium FOV 60–200 mm"),
    ("CH-S5",  11, 8, 5.0,  "small FOV 25–80 mm"),
    ("CH-XS2", 11, 8, 2.0,  "micro FOV 10–30 mm (photo paper / glass)"),
]
DICT = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_5X5_250)

def font(sz):
    for f in ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/dejavu/DejaVuSans.ttf"]:
        if os.path.exists(f): return ImageFont.truetype(f, int(sz))
    return ImageFont.load_default()

def page(landscape=False):
    w, h = (A4[1], A4[0]) if landscape else A4
    return Image.new("L", (round(w * MM), round(h * MM)), 255)

def board_img(sx, sy, sq):
    b = cv2.aruco.CharucoBoard((sx, sy), sq, sq * 0.7, DICT)
    px = (round(sx * sq * MM), round(sy * sq * MM))
    return Image.fromarray(b.generateImage(px, marginSize=0, borderBits=1))

def ruler(d, x, y, length=100):
    """100 mm check ruler with 1 mm ticks."""
    d.line([(x, y), (x + length * MM, y)], fill=0, width=3)
    for i in range(length + 1):
        t = 40 if i % 10 == 0 else (25 if i % 5 == 0 else 14)
        d.line([(x + i * MM, y), (x + i * MM, y - t)], fill=0, width=2)
    d.text((x, y + 10), f"{length} mm check: measure the distance between the end ticks", fill=0, font=font(3.0 * MM))

def save(img, name):
    img.save(os.path.join(OUT, name + ".pdf"), resolution=DPI)
    prev = img.copy(); prev.thumbnail((1400, 1400)); prev.save(os.path.join(OUT, name + "_preview.png"))
    print("wrote", name)

def charuco_pages():
    for name, sx, sy, sq, fov in PRESETS:
        bw, bh = sx * sq, sy * sq
        land = bw > 190
        p = page(land); d = ImageDraw.Draw(p)
        pw, ph = p.size[0] / MM, p.size[1] / MM
        f = font(4.2 * MM); fs = font(3.0 * MM)
        hdr = f"{name}  ChArUco {sx}x{sy}, square {sq:g} mm, marker {sq*0.7:g} mm, DICT_5X5_250  —  {fov}"
        d.text((10 * MM, 8 * MM), hdr, fill=0, font=fs)
        if bw * 2 + 15 < pw - 20 and bh * 2 + 15 < ph - 45:
            # small boards: several copies (for the step block / tilt wedges)
            cols = int((pw - 20 + 10) // (bw + 10)); rows = int((ph - 45 + 10) // (bh + 10))
            cols, rows = max(1, cols), max(1, rows)
            img = board_img(sx, sy, sq)
            x0 = (pw - (cols * bw + (cols - 1) * 10)) / 2
            for r in range(rows):
                for c in range(cols):
                    p.paste(img, (round((x0 + c * (bw + 10)) * MM), round((18 + r * (bh + 10)) * MM)))
        else:
            img = board_img(sx, sy, sq)
            p.paste(img, (round((pw - bw) / 2 * MM), round(16 * MM)))
        ruler(d, 10 * MM, (ph - 14) * MM)
        save(p, f"target_{name}")

def speckle_pages():
    """Random speckle (black dots on white, ~50 % coverage) for several dot sizes.
    Rule of thumb: dot ≈ 3–5 camera pixels → FOV ≈ 1280 px × dot / 4."""
    rng = np.random.default_rng(7)
    p = page(); d = ImageDraw.Draw(p); fs = font(3.0 * MM)
    d.text((10 * MM, 8 * MM), "DIC speckle patterns — print, cut, glue (or use as stencil). Matte paper / matte laminate.", fill=0, font=fs)
    sizes = [(0.15, "FOV ~50 mm"), (0.3, "FOV ~100 mm"), (0.6, "FOV ~200 mm"), (1.2, "FOV ~400 mm")]
    y = 16
    for dot, fov in sizes:
        w, h = 190, 60
        a = np.full((round(h * MM), round(w * MM)), 255, np.uint8)
        n = int(0.5 * w * h / (np.pi * (dot / 2) ** 2))
        cx = rng.uniform(0, w, n) * MM; cy = rng.uniform(0, h, n) * MM
        r = dot / 2 * MM * rng.uniform(0.7, 1.3, n)
        for x, yy, rr in zip(cx, cy, r):
            cv2.circle(a, (int(x), int(yy)), max(1, int(rr)), 0, -1, lineType=cv2.LINE_AA)
        p.paste(Image.fromarray(a), (round(10 * MM), round(y * MM)))
        d.text((10 * MM, (y + h + 1) * MM), f"dot {dot} mm  ({fov} at 1280 px)", fill=0, font=fs)
        y += h + 8
        if y + h > 280: break
    save(p, "speckle_A4")

if __name__ == "__main__":
    charuco_pages(); speckle_pages()
