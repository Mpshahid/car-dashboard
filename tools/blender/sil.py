"""Silhouette of the car in a reference crop (light grey studio background) or in a render (alpha)."""
import sys
import numpy as np
from PIL import Image
def photo_mask(path):
    a = np.asarray(Image.open(path).convert("RGB")).astype(float)
    bg = np.array([226, 228, 232.0])
    d = np.abs(a - bg).max(axis=2)
    m = d > 38
    # drop thin things (aerial): keep pixels with dark neighbours 3px left and right
    k = m.copy(); k[:, 3:-3] = m[:, 3:-3] & m[:, :-6] & m[:, 6:]
    return k
def render_mask(path):
    a = np.asarray(Image.open(path).convert("RGBA"))
    return a[:, :, 3] > 128
def top_profile(m):
    H, W = m.shape
    out = np.full(W, -1)
    for x in range(W):
        col = np.nonzero(m[:, x])[0]
        if len(col): out[x] = col[0]
    return out
def ends(m, rows):
    out = {}
    for y in rows:
        r = np.nonzero(m[y])[0]
        out[y] = (int(r[0]), int(r[-1])) if len(r) else None
    return out
if __name__ == "__main__":
    m = photo_mask(sys.argv[1])
    t = top_profile(m)
    print("TOP", [(x, int(t[x])) for x in range(0, m.shape[1], 25)])
    print("ENDS", ends(m, range(40, m.shape[0], 20)))
