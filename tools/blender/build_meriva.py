"""
Blender (bpy) build of the user's car: a 2012 Vauxhall Meriva B, dark metallic blue, UK plates.

  blender --background --factory-startup --python build_meriva.py -- <workdir> [--stage body|full] [--render v1,v2] [--export file.glb]

Car coordinates used below: s = metres back from the nose, x = metres to the car's LEFT, y = metres above the ground.
Blender: X = x, Y = s - L/2 (the car faces -Y), Z = y. glTF export turns that into +Y up, +Z forward, +X left,
the same axes the web page's car uses. Origin on the ground, half way along the car.

Shapes come from the user's montage photo (front, rear, both sides, 3/4 views, top), measured on solved cameras.
"""
import bpy, bmesh, math, sys, os, bisect, importlib.util
from mathutils import Vector, Matrix
from mathutils.bvhtree import BVHTree
from mathutils import geometry as G

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
WORK = ARGS[0] if ARGS else os.getcwd()
def opt(name, default=None):
    return ARGS[ARGS.index(name) + 1] if name in ARGS else default
STAGE = opt("--stage", "full")

L = 4.288
HL = L / 2
SF, SR = 0.885, 0.885 + 2.644          # axles, metres back from the nose
HUB = 0.31                             # wheel centre height (loaded)
TYRE_R = 0.322
ARCH_R = 0.372
TRACK_F, TRACK_R = 1.488 / 2, 1.509 / 2

def P(s, x, y):
    return Vector((x, s - HL, y))

def clamp(v, a, b):
    return a if v < a else b if v > b else v

def smooth(a, b, x):
    t = clamp((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)

def lerp(a, b, t):
    return a + (b - a) * t

# ---------------------------------------------------------------------------------------------------- outlines
def mono(pts):
    """Monotone cubic through (x, y) points, x increasing: never overshoots."""
    X = [p[0] for p in pts]; Y = [p[1] for p in pts]; n = len(pts)
    d = [(Y[i + 1] - Y[i]) / (X[i + 1] - X[i]) for i in range(n - 1)]
    m = [0.0] * n
    m[0] = d[0]; m[-1] = d[-1]
    for i in range(1, n - 1):
        m[i] = 0.0 if d[i - 1] * d[i] <= 0 else (d[i - 1] + d[i]) / 2
    for i in range(n - 1):
        if d[i] == 0:
            m[i] = m[i + 1] = 0.0
            continue
        a, b = m[i] / d[i], m[i + 1] / d[i]
        h = a * a + b * b
        if h > 9:
            t = 3 / math.sqrt(h)
            m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]
    def f(x):
        if x <= X[0]: return Y[0]
        if x >= X[-1]: return Y[-1]
        i = min(bisect.bisect_right(X, x) - 1, n - 2)
        h = X[i + 1] - X[i]; t = (x - X[i]) / h; t2 = t * t; t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * Y[i] + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * Y[i + 1] + (t3 - t2) * h * m[i + 1]
    return f

PROFILE = dict(
    # centre-line top: bumper, bonnet, windscreen, roof, rear window, tailgate
    top=[[0, 0.47], [0.012, 0.53], [0.019, 0.551], [0.03, 0.606], [0.057, 0.66], [0.071, 0.715], [0.087, 0.77], [0.131, 0.825], [0.197, 0.879], [0.312, 0.934], [0.47, 0.989], [0.67, 1.043], [0.883, 1.098], [0.975, 1.124], [1.118, 1.204], [1.254, 1.278], [1.391, 1.347], [1.528, 1.412], [1.664, 1.47], [1.801, 1.524], [1.938, 1.557], [2.074, 1.579], [2.211, 1.592], [2.348, 1.598], [2.55, 1.603], [2.76, 1.603], [2.89, 1.599], [3.03, 1.593], [3.17, 1.583], [3.30, 1.572], [3.44, 1.56], [3.58, 1.546], [3.68, 1.535], [3.75, 1.522], [3.80, 1.50], [3.84, 1.465], [3.875, 1.426], [3.927, 1.371], [3.974, 1.316], [4.02, 1.262], [4.058, 1.207], [4.099, 1.153], [4.14, 1.12], [4.17, 1.09], [4.195, 1.03], [4.205, 0.95], [4.212, 0.85], [4.218, 0.75], [4.232, 0.67], [4.258, 0.61], [4.278, 0.555], [4.288, 0.49]],
    bot=[[0, 0.40], [0.019, 0.31], [0.05, 0.255], [0.093, 0.22], [0.2, 0.195], [0.31, 0.18], [0.7, 0.178], [3.7, 0.178], [3.9, 0.2], [4.05, 0.23], [4.15, 0.26], [4.19, 0.278], [4.24, 0.31], [4.265, 0.35], [4.28, 0.4], [4.288, 0.45]],
    # half width at the widest point (plan view)
    halfw=[[0, 0.30], [0.02, 0.45], [0.06, 0.6], [0.12, 0.72], [0.2, 0.8], [0.3, 0.85], [0.45, 0.88], [0.65, 0.895], [0.8, 0.902], [0.95, 0.906], [3.5, 0.906], [3.76, 0.894], [3.9, 0.87], [4.03, 0.83], [4.12, 0.78], [4.2, 0.7], [4.26, 0.56], [4.288, 0.36]],
    # height of the widest point
    shoulder=[[0, 0.47], [0.1, 0.52], [0.3, 0.58], [0.6, 0.66], [0.9, 0.74], [1.3, 0.8], [3.1, 0.8], [3.5, 0.76],
              [3.9, 0.7], [4.288, 0.6]],
    # upper character line (crease) just below the windows
    crease=[[0, 0.47], [0.15, 0.72], [0.45, 0.9], [0.6, 0.925], [1.0, 0.955], [1.5, 0.983], [2.0, 1.003], [2.5, 1.023],
            [3.0, 1.043], [3.5, 1.06], [3.8, 1.07], [4.0, 1.07], [4.288, 0.95]],
    # glass base (front of the A-pillar: the wing top beside the bonnet)
    belt=[[0, 0.48], [0.02, 0.56], [0.06, 0.70], [0.12, 0.79], [0.25, 0.86], [0.45, 0.925], [0.65, 0.975], [0.85, 1.025], [0.95, 1.045], [1.01, 1.03], [1.07, 0.993], [1.15, 0.978], [1.35, 0.993], [1.8, 1.032], [2.28, 1.07], [2.5, 1.075], [2.75, 1.1], [3.0, 1.115], [3.4, 1.125], [3.7, 1.13], [3.9, 1.12], [4.05, 1.09], [4.2, 1.04], [4.288, 0.96]],
    # half width of the top surface: bonnet, windscreen (A-pillars), roof rails, rear window
    rail=[[0, 0.16], [0.03, 0.26], [0.1, 0.42], [0.2, 0.6], [0.35, 0.7], [0.55, 0.75], [0.8, 0.765], [1.0, 0.755], [1.3, 0.72], [1.6, 0.688], [1.9, 0.66], [2.1, 0.65], [2.6, 0.652], [3.2, 0.65], [3.6, 0.638], [3.8, 0.62], [3.9, 0.615], [4.0, 0.63], [4.1, 0.64], [4.17, 0.6], [4.22, 0.5], [4.26, 0.38], [4.288, 0.18]],
    # Meriva "swoosh": the concave sweep from the front door's leading edge down to the sills
    swoosh=[[1.27, 0.76], [1.326, 0.736], [1.39, 0.655], [1.456, 0.564], [1.52, 0.49], [1.587, 0.433], [1.65, 0.398],
            [1.699, 0.383], [1.78, 0.372], [1.863, 0.367], [2.27, 0.375], [2.677, 0.383], [3.003, 0.391], [3.09, 0.405],
            [3.134, 0.418], [3.18, 0.44]],
)

def build_profiles(pr):
    f = {k: mono(v) for k, v in pr.items()}
    return f

F = build_profiles(PROFILE)

def crown(s):
    """How much higher the centre line is than the edges of the top surface (bonnet, windscreen, roof, rear glass)."""
    c = 0.03 * smooth(0.0, 0.2, s)                     # bonnet
    c = lerp(c, 0.045, smooth(0.9, 1.1, s))            # windscreen and roof
    c = lerp(c, 0.04, smooth(3.75, 3.9, s))            # rear window
    return c * (1 - smooth(4.16, 4.288, s))

SW_A, SW_B = 1.27, 3.18
def swoosh(s):
    """(height, recess depth, sharp) of the swoosh at station s; recess 0 outside it."""
    if s <= SW_A or s >= SW_B:
        return None
    rec = 0.02 * smooth(SW_A, SW_A + 0.08, s) * (1 - smooth(SW_B - 0.13, SW_B, s))
    return F["swoosh"](s), rec

# ---------------------------------------------------------------------------------------------------- end rounding
# Seen from above, the ends are rounded at every height (both side photos and the top view agree): a point x metres
# off the centre line sits setback(x) further in than the centre line at the same height.
ROUND = dict(front_low=(0.12, 0.15), front_high=(0.12, 0.10), rear_low=(0.12, 0.17), rear_high=(0.16, 0.12))

def _solve(f, y, lo, hi, rising):
    """s in [lo, hi] where the monotone outline f reaches height y (None if it never does)."""
    flo, fhi = f(lo), f(hi)
    if not (min(flo, fhi) <= y <= max(flo, fhi)):
        return None
    for _ in range(48):
        m = (lo + hi) / 2
        if (f(m) < y) == rising: lo = m
        else: hi = m
    return (lo + hi) / 2

def front_s_at(y):
    """Where the centre line's front face is at height y."""
    if F["bot"](0) <= y <= F["top"](0):
        return 0.0
    if y > F["top"](0):
        return _solve(F["top"], y, 0.0, 0.97, True)
    return _solve(F["bot"], y, 0.0, 0.5, False)

def rear_s_at(y):
    """Where the centre line's back face is at height y."""
    if F["bot"](L) <= y <= F["top"](L):
        return L
    if y > F["top"](L):
        return _solve(F["top"], y, 3.6, L, False)
    return _solve(F["bot"], y, 3.6, L, True)

def smin(a, b, k=0.03):
    return (a + b - math.sqrt((a - b) ** 2 + k * k)) / 2

def setback(x, a, b):
    e = 0.05
    return a * (math.sqrt(x * x + e * e) - e) + b * x * x

def width_for(d, a, b):
    """Largest |x| whose setback is at most d."""
    if d <= 0:
        return 0.0
    lo, hi = 0.0, 2.0
    for _ in range(40):
        m = (lo + hi) / 2
        if setback(m, a, b) <= d: lo = m
        else: hi = m
    return lo

def end_cap(s, y):
    """Widest the body may be at station s and height y, from the rounded ends (None = no limit)."""
    cap = None
    t = smooth(0.55, 0.75, y)
    if s < 0.9 and y < 1.12:
        s0 = front_s_at(y)
        if s0 is not None:
            a = lerp(ROUND["front_low"][0], ROUND["front_high"][0], t); b = lerp(ROUND["front_low"][1], ROUND["front_high"][1], t)
            cap = width_for(s - s0, a, b)
    if s > 3.6 and y < 1.56:
        s1 = rear_s_at(y)
        if s1 is not None:
            a = lerp(ROUND["rear_low"][0], ROUND["rear_high"][0], t); b = lerp(ROUND["rear_low"][1], ROUND["rear_high"][1], t)
            w = width_for(s1 - s, a, b)
            cap = w if cap is None else min(cap, w)
    return cap


def keys(s):
    """Half cross-section control points (x >= 0) from the bottom centre to the top centre: [(x, y, sharp)]."""
    yb, yt, hw = F["bot"](s), F["top"](s), F["halfw"](s)
    cr = crown(s)
    xr = min(F["rail"](s), hw - 0.06)
    xw = clamp(hw - 0.14, 0.04, 0.62)
    y_sill = yb + 0.085
    sw = swoosh(s)
    shY = F["shoulder"](s)
    crY = F["crease"](s)
    beltY = F["belt"](s)
    railY = yt - cr
    # heights must climb from the bottom to the top
    if sw:
        swy, rec = sw
        swy = min(swy, shY - 0.03)
    else:
        swy, rec = (y_sill + shY) / 2, 0.0
    hs = [yb, yb, yb + 0.03, y_sill, swy - 0.012, swy, shY, crY, beltY, railY, yt - cr * 0.15, yt]
    # The top line wins: near the ends the side lines run out above it, so squeeze them under it first.
    hs[-1] = yt
    for k in range(len(hs) - 2, -1, -1):
        hs[k] = min(hs[k], hs[k + 1] - 0.0015)
    for k in range(1, len(hs)):
        hs[k] = max(hs[k], hs[k - 1] + 0.0015)
    hs[0] = hs[1] = min(hs[0], hs[1])
    # Near the ends the lines above the shoulder (and below it) run out of room and bunch up a few mm apart;
    # spread them evenly instead, or the section zigzags and the surface wrinkles.
    room = hs[11] - hs[6]
    w = 1 - smooth(0.12, 0.3, room)
    if w > 0:
        for k, f in zip(range(7, 11), (0.3, 0.55, 0.75, 0.9)):
            hs[k] = lerp(hs[k], hs[6] + room * f, w)
    room = hs[6] - hs[1]
    w = 1 - smooth(0.12, 0.3, room)
    if w > 0:
        for k, f in zip(range(2, 6), (0.12, 0.35, 0.6, 0.8)):
            hs[k] = lerp(hs[k], hs[1] + room * f, w)
    crease_on = 0.55 < s < 3.95
    xb = max(xr + 0.01, hw - 0.05 - 0.03 * smooth(1.0, 1.5, s))
    xs = [0.0, xw, hw - 0.05, hw - 0.018 - rec * 0.35, hw - 0.012 - rec, hw - 0.006, hw, hw - 0.012, xb, xr, xr * 0.6, 0.0]
    for k in range(1, 11):
        c = end_cap(s, hs[k])
        if c is not None:
            xs[k] = max(0.0, smin(xs[k], c))
    # keep the top surface a sensible width where the caps have narrowed it
    xs[10] = min(xs[10], xs[9] * 0.6)
    if s < 0.9 or s > 3.6:
        # at the ends the section bulges out to the shoulder and back in, nothing else (no swoosh there)
        for k in range(2, 7):
            xs[k] = max(xs[k], xs[k - 1])
        for k in range(7, 11):
            xs[k] = min(xs[k], xs[k - 1])
    wing_edge = 0.25 < s < 0.97   # crisp line along the top of the wings, beside the bonnet
    sharp = [False, False, False, False, rec > 0.004, rec > 0.004, False, crease_on, wing_edge, False, False, False]
    pts = [(xs[k], hs[k], sharp[k]) for k in range(12)]
    return pts

SEGS = [6, 3, 3, 8, 2, 10, 12, 4, 4, 16, 7, 8]   # subdivisions of each span between key points (last unused)

def catmull(p0, p1, p2, p3, t):
    """Centripetal Catmull-Rom between p1 and p2."""
    def d(a, b):
        return max(1e-5, math.sqrt(math.hypot(b[0] - a[0], b[1] - a[1])))
    t1 = d(p0, p1); t2 = t1 + d(p1, p2); t3 = t2 + d(p2, p3)
    u = t1 + (t2 - t1) * t
    def L2(a, b, ta, tb):
        k = (u - ta) / (tb - ta)
        return (a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k)
    A1 = L2(p0, p1, 0, t1); A2 = L2(p1, p2, t1, t2); A3 = L2(p2, p3, t2, t3)
    return L2(L2(A1, A2, 0, t2), L2(A2, A3, t1, t3), t1, t2)

def half_ring(s):
    K = keys(s)
    out = [(K[0][0], K[0][1])]
    n = len(K)
    for k in range(n - 1):
        p1, p2 = K[k], K[k + 1]
        # a sharp key point breaks the tangent: mirror the neighbour so the curve leaves it straight
        if k == 0 or p1[2]:
            p0 = (2 * p1[0] - p2[0], 2 * p1[1] - p2[1])
        else:
            p0 = K[k - 1][:2]
        if k + 2 >= n or p2[2]:
            p3 = (2 * p2[0] - p1[0], 2 * p2[1] - p1[1])
        else:
            p3 = K[k + 2][:2]
        segs = SEGS[k]
        if k >= 9:   # rail -> centre: a parabolic crown, y = top - crown * (x / rail)^2
            xr, yr = K[9][0], K[9][1]
            yt = K[-1][1]
            n9 = SEGS[9] + SEGS[10]
            if k == 9:
                for i in range(1, n9 + 1):
                    u = i / n9
                    x = xr * (1 - u)
                    out.append((x, yt - (yt - yr) * (x / xr) ** 2 if xr > 1e-6 else yt))
            continue
        if k == 8:   # glass -> rail: arrive along the roof's tangent so the rail is rounded, not creased
            xr, yr, yt = K[9][0], K[9][1], K[-1][1]
            p3 = (xr * 0.75, yt - (yt - yr) * 0.75 ** 2)
        for i in range(1, segs + 1):
            t = i / segs
            if k <= 1:   # flat underbody spans stay straight
                out.append((p1[0] + (p2[0] - p1[0]) * t, p1[1] + (p2[1] - p1[1]) * t))
            else:
                out.append(catmull(p0, p1[:2], p2[:2], p3, t))
    return out

HALF_N = sum(SEGS[:11]) + 1           # points in half a ring, bottom centre .. top centre

def ring(s):
    """Closed ring of points (x, y), starting at the bottom centre, round the left side, top, right side."""
    H = half_ring(s)
    return H + [(-x, y) for (x, y) in reversed(H[1:-1])]

def stations():
    """Loft stations: closer together where the outline changes quickly, at most 4 cm apart."""
    st = [0.0]
    prev = ring(0.0)
    s = 0.0
    acc = 0.0
    step = 0.002
    while s < L - 1e-9:
        s = min(L, s + step)
        cur = ring(s)
        mv = max(math.hypot(a[0] - b[0], a[1] - b[1]) for a, b in zip(cur, prev))
        acc += math.hypot(step, mv)
        prev = cur
        if acc >= 0.03 or s - st[-1] >= 0.05 or s >= L:
            st.append(s)
            acc = 0.0
    if L - st[-1] > 1e-6:
        st.append(L)
    if st[-1] - st[-2] < 0.004:
        st.pop(-2)
    return st

# ---------------------------------------------------------------------------------------------------- scene helpers
def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)

MATS = {}
def mat(name, rgb, metal=0.0, rough=0.5, emit=None):
    if name in MATS:
        return MATS[name]
    m = bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except Exception:
        pass
    b = m.node_tree.nodes.get("Principled BSDF")
    lin = [c / 255.0 for c in rgb]
    lin = [(c / 12.92) if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in lin]
    b.inputs["Base Color"].default_value = (*lin, 1.0)
    b.inputs["Metallic"].default_value = metal
    b.inputs["Roughness"].default_value = rough
    m.diffuse_color = (*lin, 1.0)
    MATS[name] = m
    return m

def hexrgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))

def materials():
    mat("Paint", hexrgb("#1f2d63"), 0.55, 0.32)
    mat("Glass", hexrgb("#1b2530"), 0.35, 0.04)
    mat("Trim", hexrgb("#0b0c0e"), 0.15, 0.28)          # gloss black pillars, window surrounds
    mat("Plastic", hexrgb("#141518"), 0.0, 0.72)        # textured black plastic
    mat("Liner", hexrgb("#0c0d0f"), 0.0, 0.9)           # wheel-arch liners
    mat("Under", hexrgb("#0d0e10"), 0.0, 0.92)
    mat("Chrome", hexrgb("#dfe4ea"), 1.0, 0.1)
    mat("HeadLens", hexrgb("#b9c3cf"), 0.75, 0.12)
    mat("HeadChrome", hexrgb("#e9eef5"), 1.0, 0.05)
    mat("HeadDark", hexrgb("#2a2f37"), 0.6, 0.25)
    mat("Amber", hexrgb("#e3a33c"), 0.25, 0.2)
    mat("TailRed", hexrgb("#b5121c"), 0.1, 0.12)
    mat("TailDark", hexrgb("#5a0a10"), 0.1, 0.15)
    mat("Reverse", hexrgb("#d8d6d6"), 0.5, 0.1)
    mat("BrakeLight", hexrgb("#6b0a10"), 0.1, 0.15)
    mat("Tyre", hexrgb("#141414"), 0.0, 0.9)
    mat("Alloy", hexrgb("#d3d8de"), 0.6, 0.28)
    mat("AlloyDark", hexrgb("#2a2d32"), 0.6, 0.5)
    mat("Disc", hexrgb("#7c8187"), 0.85, 0.42)
    mat("Caliper", hexrgb("#24272c"), 0.4, 0.55)
    mat("PlateWhite", hexrgb("#f2f2ec"), 0.0, 0.35)
    mat("PlateYellow", hexrgb("#f4c400"), 0.0, 0.35)
    mat("PlateText", hexrgb("#121212"), 0.0, 0.5)
    mat("BadgeDark", hexrgb("#1b2230"), 0.4, 0.3)
    mat("MirrorGlass", hexrgb("#a2abb5"), 1.0, 0.03)
    mat("Exhaust", hexrgb("#55595f"), 0.9, 0.35)

def new_obj(name, me, parent=None):
    o = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(o)
    if parent is not None:
        o.parent = parent
    return o

def mesh_from(name, verts, faces, mat_names, face_mats=None):
    me = bpy.data.meshes.new(name)
    me.from_pydata([tuple(v) for v in verts], [], faces)
    me.update()
    for n in mat_names:
        me.materials.append(MATS[n])
    if face_mats:
        for p, mi in zip(me.polygons, face_mats):
            p.material_index = mi
    return me

def evaluated_copy(o, name):
    dg = bpy.context.evaluated_depsgraph_get()
    oe = o.evaluated_get(dg)
    me = bpy.data.meshes.new_from_object(oe, preserve_all_data_layers=True, depsgraph=dg)
    me.name = name
    return me

# ---------------------------------------------------------------------------------------------------- body
def build_body():
    ST = stations()
    rings = [ring(s) for s in ST]
    NV = len(rings[0])
    verts = []
    for s, R in zip(ST, rings):
        for (x, y) in R:
            verts.append(P(s, x, y))
    faces = []
    NS = len(ST)
    for i in range(NS - 1):
        a0, b0 = i * NV, (i + 1) * NV
        for j in range(NV):
            j2 = (j + 1) % NV
            faces.append((a0 + j, a0 + j2, b0 + j2, b0 + j))
    # end caps: fans to a centre point
    for i, flip in ((0, True), (NS - 1, False)):
        c = sum((verts[i * NV + j] for j in range(NV)), Vector()) / NV
        ci = len(verts)
        verts.append(c)
        for j in range(NV):
            j2 = (j + 1) % NV
            f = (ci, i * NV + j, i * NV + j2)
            faces.append(f if flip else (ci, i * NV + j2, i * NV + j))
    me = mesh_from("Body", verts, faces, ["Paint", "Under"])
    # make the normals face outwards
    bm = bmesh.new(); bm.from_mesh(me)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    # wheel-arch lips: a little flare round each arch
    for v in bm.verts:
        x, sy, y = v.co.x, v.co.y + HL, v.co.z
        if abs(x) < 0.65 or y < 0.24:
            continue
        for c in (SF, SR):
            r = math.hypot(sy - c, y - HUB)
            if ARCH_R - 0.01 < r < ARCH_R + 0.11 and y > HUB - 0.12:
                k = 1 - smooth(ARCH_R, ARCH_R + 0.11, r)
                v.co.x += math.copysign(0.013 * k, x)
    bm.to_mesh(me); bm.free()
    body = new_obj("Body", me)
    print("BODY stations", NS, "ring", NV, "verts", len(me.vertices), "faces", len(me.polygons))
    return body, ST

def cut_wheel_wells(body):
    """Boolean the wheel wells out of the closed body; the cut faces become the black arch liners."""
    coll = bpy.data.collections.new("cutters")
    bpy.context.scene.collection.children.link(coll)
    for c in (SF, SR):
        for side in (1, -1):
            bm = bmesh.new()
            bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=72, radius1=ARCH_R, radius2=ARCH_R, depth=0.8,
                                  matrix=Matrix.Translation((side * 0.94, c - HL, HUB)) @ Matrix.Rotation(math.pi / 2, 4, "Y"))
            me = bpy.data.meshes.new("cut"); bm.to_mesh(me); bm.free()
            me.materials.append(MATS["Liner"])
            o = bpy.data.objects.new("cut", me); coll.objects.link(o)
    mod = body.modifiers.new("wells", "BOOLEAN")
    mod.operation = "DIFFERENCE"
    mod.operand_type = "COLLECTION"
    mod.collection = coll
    try:
        mod.solver = "EXACT"
    except Exception:
        pass
    try:
        mod.material_mode = "TRANSFER"
    except Exception:
        pass
    me = evaluated_copy(body, "Body")
    body.modifiers.remove(mod)
    old = body.data
    body.data = me
    bpy.data.meshes.remove(old)
    for o in list(coll.objects):
        bpy.data.objects.remove(o)
    bpy.data.collections.remove(coll)
    # underbody faces: black
    ui = me.materials.find("Under")
    for p in me.polygons:
        if p.normal.z < -0.8 and p.center.z < 0.26 and me.materials[p.material_index].name == "Paint":
            p.material_index = ui
    print("WELLS verts", len(me.vertices), "faces", len(me.polygons), "mats", [m.name for m in me.materials])

def decimate(o, ratio):
    """Fewer triangles where the body is flat (keeps the shape; the file has to load on a phone)."""
    if ratio >= 0.999:
        return
    mod = o.modifiers.new("dec", "DECIMATE")
    mod.decimate_type = "COLLAPSE"
    mod.ratio = ratio
    mod.use_symmetry = True
    mod.symmetry_axis = "X"
    mod.use_collapse_triangulate = True
    me = evaluated_copy(o, "Body")
    o.modifiers.remove(mod)
    old = o.data; o.data = me; bpy.data.meshes.remove(old)
    print("DECIMATED verts", len(me.vertices), "faces", len(me.polygons))

def shade(o, angle=32):
    me = o.data
    me.shade_smooth()
    try:
        me.set_sharp_from_angle(angle=math.radians(angle))
    except Exception as e:
        print("sharp-from-angle unavailable:", e)

# ---------------------------------------------------------------------------------------------------- render
def load_views():
    spec = importlib.util.spec_from_file_location("views", os.path.join(WORK, "views.py"))
    v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
    return v

def setup_render():
    sc = bpy.context.scene
    sc.render.engine = "BLENDER_EEVEE"
    sc.render.film_transparent = True
    sc.view_settings.view_transform = "Standard"
    w = bpy.data.worlds.new("w"); sc.world = w
    try:
        w.use_nodes = True
    except Exception:
        pass
    bg = w.node_tree.nodes["Background"]
    bg.inputs[0].default_value = (0.78, 0.8, 0.84, 1); bg.inputs[1].default_value = 0.9
    for rot, e in (((0.75, 0.15, 0.9), 2.6), ((1.2, 0.0, -2.2), 1.0)):
        sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
        sun.data.energy = e; sun.rotation_euler = rot
        sc.collection.objects.link(sun)

def render_view(v, out):
    sc = bpy.context.scene
    cam = sc.camera
    if cam is None:
        cd = bpy.data.cameras.new("cam"); cam = bpy.data.objects.new("cam", cd); sc.collection.objects.link(cam); sc.camera = cam
    cd = cam.data
    W, H = v["W"], v["H"]
    sc.render.resolution_x, sc.render.resolution_y, sc.render.resolution_percentage = W, H, 100
    cd.sensor_fit = "HORIZONTAL"; cd.sensor_width = 36
    cd.clip_start = 0.05; cd.clip_end = 200
    cd.shift_x = cd.shift_y = 0.0
    cd.type = "PERSP"
    if "f" in v:
        cd.lens = v["f"] * 36 / W
        cd.shift_x = (W / 2 - v["cx"]) / W; cd.shift_y = (v["cy"] - H / 2) / W
    elif v.get("ortho"):
        cd.type = "ORTHO"; cd.ortho_scale = v["ortho"]
    else:
        cd.lens = v["lens"]
    cam.location = v["loc"]
    if "rot" in v:
        cam.rotation_euler = v["rot"]
    else:
        d = Vector(v["target"]) - Vector(v["loc"])
        cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    sc.render.filepath = out
    bpy.ops.render.render(write_still=True)
    print("RENDERED", out)

# ---------------------------------------------------------------------------------------------------- main
def export_glb(body, path):
    """One mesh for everything that doesn't move (body, glass, lamps, trim, mirrors...), the four wheels as nodes."""
    statics = [o for o in globals().get("DECALS", []) if o.type == "MESH" and o.name in bpy.data.objects]
    if statics:
        with bpy.context.temp_override(active_object=body, object=body, selected_objects=[body] + statics,
                                       selected_editable_objects=[body] + statics):
            bpy.ops.object.join()
    body.name = "MerivaBody"
    bpy.ops.export_scene.gltf(filepath=path, export_format="GLB", use_selection=False, export_apply=True,
                              export_texcoords=False, export_normals=True, export_cameras=False, export_lights=False,
                              export_yup=True, export_extras=False)
    print("EXPORTED", path, os.path.getsize(path))

def showcase(prefix):
    """Studio renders: soft daylight, a pale floor that catches the car's shadow."""
    sc = bpy.context.scene
    sc.render.engine = "BLENDER_EEVEE"
    sc.render.film_transparent = False
    sc.view_settings.view_transform = "AgX"
    try:
        sc.view_settings.look = "AgX - Medium High Contrast"
    except Exception:
        pass
    w = bpy.data.worlds.new("studio"); sc.world = w
    try:
        w.use_nodes = True
    except Exception:
        pass
    nt = w.node_tree
    bg = nt.nodes["Background"]
    sky = nt.nodes.new("ShaderNodeTexSky")
    try:
        sky.sky_type = "HOSEK_WILKIE"
    except Exception:
        pass
    nt.links.new(sky.outputs[0], bg.inputs[0])
    bg.inputs[1].default_value = 0.55
    sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
    sun.data.energy = 3.2; sun.data.angle = math.radians(6)
    sun.rotation_euler = (math.radians(50), 0, math.radians(35))
    sc.collection.objects.link(sun)
    fill = bpy.data.objects.new("fill", bpy.data.lights.new("fill", "AREA"))
    fill.data.energy = 900; fill.data.size = 6
    fill.location = (-4, -5, 4); fill.rotation_euler = (math.radians(55), 0, math.radians(-40))
    sc.collection.objects.link(fill)
    bm = bmesh.new()
    bmesh.ops.create_grid(bm, x_segments=1, y_segments=1, size=30)
    me = bpy.data.meshes.new("floor"); bm.to_mesh(me); bm.free()
    fm = bpy.data.materials.new("Floor")
    try:
        fm.use_nodes = True
    except Exception:
        pass
    fb = fm.node_tree.nodes.get("Principled BSDF")
    fb.inputs["Base Color"].default_value = (0.62, 0.64, 0.67, 1); fb.inputs["Roughness"].default_value = 0.85
    me.materials.append(fm)
    new_obj("Floor", me)
    try:
        sc.eevee.taa_render_samples = 64
        sc.eevee.use_shadows = True
    except Exception:
        pass
    cd = bpy.data.cameras.new("show"); cam = bpy.data.objects.new("show", cd); sc.collection.objects.link(cam); sc.camera = cam
    cd.lens = 55; cd.sensor_width = 36
    sc.render.resolution_x, sc.render.resolution_y, sc.render.resolution_percentage = 1400, 820, 100
    for name, az, dist, hgt in (("front34", 38, 9.4, 1.6), ("rear34", 140, 9.4, 1.8), ("side", 270, 10.5, 1.2), ("top34", 55, 9.0, 5.5)):
        a = math.radians(az)
        target = Vector((0.0, 0.0, 0.62))
        cam.location = (dist * math.sin(a), -dist * math.cos(a), hgt)
        cam.rotation_euler = (target - Vector(cam.location)).to_track_quat("-Z", "Y").to_euler()
        sc.render.filepath = prefix + "_" + name + ".png"
        bpy.ops.render.render(write_still=True)
        print("SHOWCASE", sc.render.filepath)

def main():
    reset_scene()
    materials()
    body, ST = build_body()
    cut_wheel_wells(body)
    decimate(body, float(opt("--decimate", "0.5")))
    shade(body)
    if STAGE == "full":
        exec(compile(open(os.path.join(WORK, "details.py"), encoding="utf-8").read(), "details.py", "exec"), globals())
        build_details(body)
    if opt("--export"):
        export_glb(body, opt("--export"))
    if opt("--render"):
        views = load_views()
        setup_render()
        for name in opt("--render").split(","):
            v = getattr(views, name)()
            render_view(v, os.path.join(WORK, "out", "r_" + name + ".png"))
    if opt("--showcase"):
        showcase(opt("--showcase"))
    if opt("--blend"):
        bpy.ops.wm.save_as_mainfile(filepath=opt("--blend"))

main()
