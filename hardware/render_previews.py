"""Shaded PNG previews of the STL files: small z-buffer rasteriser (numpy + PIL), no OpenGL needed."""
import numpy as np, glob, os, struct
from PIL import Image, ImageDraw, ImageFont

def load(fn):
    data = open(fn, "rb").read()
    if data[:5] == b"solid" and b"facet" in data[:400]:
        v = [list(map(float, l.split()[1:4])) for l in data.decode().splitlines() if l.strip().startswith("vertex")]
        return np.array(v, np.float64).reshape(-1, 3, 3)
    n = struct.unpack("<I", data[80:84])[0]
    a = np.frombuffer(data[84:84 + n * 50], dtype=np.dtype([("n", "<3f4"), ("v", "<9f4"), ("a", "<u2")]))
    return a["v"].reshape(-1, 3, 3).astype(np.float64)

def rot(elev, azim):
    a, e = np.radians(azim), np.radians(elev)
    rz = np.array([[np.cos(a), -np.sin(a), 0], [np.sin(a), np.cos(a), 0], [0, 0, 1]])
    rx = np.array([[1, 0, 0], [0, np.cos(e), -np.sin(e)], [0, np.sin(e), np.cos(e)]])
    return rx @ rz

def render(fn, out, W=1200, H=900, elev=-60, azim=30):
    t = load(fn)
    R = rot(elev, azim)
    p = t @ R.T                                   # view space: x right, y up, z towards viewer
    n = np.cross(t[:, 1] - t[:, 0], t[:, 2] - t[:, 0]); n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-12
    nv = n @ R.T
    mn, mx = p.reshape(-1, 3).min(0), p.reshape(-1, 3).max(0)
    s = 0.85 * min(W / (mx[0] - mn[0]), H / (mx[1] - mn[1]))
    sx = (p[..., 0] - (mn[0] + mx[0]) / 2) * s + W / 2
    sy = H / 2 - (p[..., 1] - (mn[1] + mx[1]) / 2) * s
    z = p[..., 2]
    zb = np.full((H, W), -np.inf); img = np.zeros((H, W, 3)); img[:] = (0.97, 0.97, 0.98)
    L = np.array([-0.3, 0.5, 0.8]); L /= np.linalg.norm(L)
    base = np.array([0.20, 0.47, 0.86])
    for i in range(len(t)):
        x0, x1, x2 = sx[i]; y0, y1, y2 = sy[i]
        xa, xb = int(max(0, np.floor(min(x0, x1, x2)))), int(min(W - 1, np.ceil(max(x0, x1, x2))))
        ya, yb = int(max(0, np.floor(min(y0, y1, y2)))), int(min(H - 1, np.ceil(max(y0, y1, y2))))
        if xa > xb or ya > yb: continue
        den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if abs(den) < 1e-9: continue
        X, Y = np.meshgrid(np.arange(xa, xb + 1) + 0.5, np.arange(ya, yb + 1) + 0.5)
        w0 = ((y1 - y2) * (X - x2) + (x2 - x1) * (Y - y2)) / den
        w1 = ((y2 - y0) * (X - x2) + (x0 - x2) * (Y - y2)) / den
        w2 = 1 - w0 - w1
        m = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        if not m.any(): continue
        zz = w0 * z[i, 0] + w1 * z[i, 1] + w2 * z[i, 2]
        sub = zb[ya:yb + 1, xa:xb + 1]
        m &= zz > sub
        if not m.any(): continue
        nn = nv[i] if nv[i, 2] >= 0 else -nv[i]
        sh = 0.30 + 0.70 * max(0.0, float(nn @ L))
        sub[m] = zz[m]
        img[ya:yb + 1, xa:xb + 1][m] = base * sh + 0.08
    # edge darkening from depth discontinuities
    d = np.where(np.isfinite(zb), zb, np.nan)
    gx = np.abs(np.diff(d, axis=1, prepend=d[:, :1])); gy = np.abs(np.diff(d, axis=0, prepend=d[:1]))
    edge = np.nan_to_num(np.maximum(gx, gy)) > 0.6 / s * 6
    img[edge] *= 0.35
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    dr = ImageDraw.Draw(im)
    size = t.reshape(-1, 3).max(0) - t.reshape(-1, 3).min(0)
    try: f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 28)
    except Exception: f = ImageFont.load_default()
    dr.text((24, 20), f"{os.path.basename(fn)[:-4]}    {size[0]:.0f} × {size[1]:.0f} × {size[2]:.0f} mm", fill=(30, 30, 30), font=f)
    im.save(out)

if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    for f in sorted(glob.glob(os.path.join(here, "stl", "*.stl"))):
        render(f, os.path.join(here, "img", os.path.basename(f)[:-4] + ".png")); print("rendered", os.path.basename(f))
