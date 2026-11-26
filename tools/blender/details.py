# Details for the Meriva body: run inside build_meriva.py's namespace (exec), after the body is built and cut.
# Everything painted on the body (windows, lamps, grille, plates, badges, gaps) is a "decal": an outline drawn in a
# photo view, filled with triangles and dropped onto the body by casting rays, then lifted a few mm off the paint.
import bpy, bmesh, math
from mathutils import Vector, Matrix
from mathutils.bvhtree import BVHTree
from mathutils import geometry as G

DECALS = []
FONT_BOLD = "C:/Windows/Fonts/arialbd.ttf"

def body_bvh(o):
    dg = bpy.context.evaluated_depsgraph_get()
    return BVHTree.FromObject(o, dg)

# ---------------------------------------------------------------------------------------------------- projections
def side(sign):
    """(s, h) on the car's left (sign +1) or right (sign -1) side."""
    def f(u, v):
        return Vector((sign * 3.0, u - HL, v)), Vector((-sign, 0.0, 0.0))
    return f

def FRONT(u, v):
    return Vector((u, -HL - 1.5, v)), Vector((0.0, 1.0, 0.0))

def REAR(u, v):
    return Vector((u, HL + 1.5, v)), Vector((0.0, -1.0, 0.0))

def TOP(u, v):
    """(x, s) seen from above."""
    return Vector((u, v - HL, 4.0)), Vector((0.0, 0.0, -1.0))

class Oblique:
    """A view along direction -d (d points out of the car), for features that wrap round a corner."""
    def __init__(self, d, c):
        self.d = Vector(d).normalized(); self.c = Vector(c)
        self.e1 = Vector((0, 0, 1)).cross(self.d).normalized()
        self.e2 = self.d.cross(self.e1).normalized()
    def __call__(self, u, v):
        return self.c + self.e1 * u + self.e2 * v + self.d * 3.0, -self.d
    def to2d(self, p):
        q = Vector(p) - self.c
        return (q.dot(self.e1), q.dot(self.e2))

def hit(proj, u, v):
    o, d = proj(u, v)
    r = BVH.ray_cast(o, d, 20.0)
    if r[0] is None or r[1].dot(-d) < 0.03:
        return None
    return r[0], r[1]

def surface_point(proj, u, v):
    h = hit(proj, u, v)
    return h[0] if h else None

# ---------------------------------------------------------------------------------------------------- outlines
def chaikin(pts, it=3):
    """Corner-cutting smoothing of a closed outline; points given as (u, v, 1) stay sharp corners."""
    P = [(p[0], p[1], bool(len(p) > 2 and p[2])) for p in pts]
    for _ in range(it):
        Q = []
        n = len(P)
        for i in range(n):
            a, b = P[i], P[(i + 1) % n]
            if a[2]:
                Q.append(a)
            else:
                Q.append((0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1], False))
            Q.append((0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1], False))
        P = Q
    return [(p[0], p[1]) for p in P]

def resample(pts, step):
    """Closed polyline -> points about `step` apart."""
    out = []
    n = len(pts)
    for i in range(n):
        a, b = pts[i], pts[(i + 1) % n]
        d = math.hypot(b[0] - a[0], b[1] - a[1])
        k = max(1, int(math.ceil(d / step)))
        for j in range(k):
            t = j / k
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    # drop near-duplicates
    clean = []
    for p in out:
        if not clean or math.hypot(p[0] - clean[-1][0], p[1] - clean[-1][1]) > step * 0.2:
            clean.append(p)
    if len(clean) > 2 and math.hypot(clean[0][0] - clean[-1][0], clean[0][1] - clean[-1][1]) < step * 0.2:
        clean.pop()
    return clean

def area2(pts):
    return sum(pts[i][0] * pts[(i + 1) % len(pts)][1] - pts[(i + 1) % len(pts)][0] * pts[i][1] for i in range(len(pts)))

def inside(pt, poly):
    x, y = pt; c = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]; xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            c = not c
        j = i
    return c

def seg_dist(p, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    L2 = dx * dx + dy * dy
    t = 0.0 if L2 == 0 else clamp(((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / L2, 0.0, 1.0)
    return math.hypot(p[0] - a[0] - dx * t, p[1] - a[1] - dy * t)

def edge_dist(p, ring):
    return min(seg_dist(p, ring[i], ring[(i + 1) % len(ring)]) for i in range(len(ring)))

def circle(cu, cv, r, n=40, ry=None):
    ry = r if ry is None else ry
    return [(cu + r * math.cos(2 * math.pi * i / n), cv + ry * math.sin(2 * math.pi * i / n)) for i in range(n)]

def mirror_x(pts):
    return [(-p[0], p[1]) + tuple(p[2:]) for p in reversed(pts)]

def sym(half):
    """Outline given for x >= 0 from the centre line round to the centre line again -> full symmetric outline."""
    return list(half) + [(-p[0], p[1]) + tuple(p[2:]) for p in reversed(half) if abs(p[0]) > 1e-9]

# ---------------------------------------------------------------------------------------------------- decal
def decal(name, matname, outline, proj, offset=0.0025, spacing=0.02, dome=0.0, dome_w=0.012, smooth_it=3):
    pts = chaikin(outline, smooth_it) if smooth_it else [(p[0], p[1]) for p in outline]
    ring = resample(pts, spacing * 0.5)
    if area2(ring) < 0:
        ring.reverse()
    if len(ring) < 3:
        return None
    us = [p[0] for p in ring]; vs = [p[1] for p in ring]
    inner = []
    v = min(vs) + spacing * 0.5; row = 0
    while v < max(vs):
        u = min(us) + (spacing * 0.5 if row % 2 else 0.0) + spacing * 0.25
        while u < max(us):
            if inside((u, v), ring):
                inner.append((u, v))
            u += spacing
        v += spacing * 0.866; row += 1
    if inner:
        inner = [p for p in inner if edge_dist(p, ring) > spacing * 0.35]
    allp = [Vector(p) for p in ring + inner]
    res = G.delaunay_2d_cdt(allp, [], [list(range(len(ring)))], 1, 1e-7)
    ov, of = res[0], res[2]
    vmap, V3 = {}, []
    need_d = dome > 0
    for i, p in enumerate(ov):
        h = hit(proj, p.x, p.y)
        if h is None:
            vmap[i] = None
            continue
        off = offset
        if need_d:
            off += dome * smooth(0.0, dome_w, edge_dist((p.x, p.y), ring))
        vmap[i] = len(V3)
        V3.append(h[0] + h[1] * off)
    faces = []
    for f in of:
        if len(f) == 3 and all(vmap.get(k) is not None for k in f):
            faces.append(tuple(vmap[k] for k in f))
    if not faces:
        print("DECAL EMPTY", name)
        return None
    # face the outside
    a, b, c = (V3[k] for k in faces[0])
    _, d = proj(ov[0].x, ov[0].y)
    if (b - a).cross(c - a).dot(-d) < 0:
        faces = [(f[0], f[2], f[1]) for f in faces]
    me = mesh_from(name, V3, faces, [matname])
    me.shade_smooth()
    o = new_obj(name, me)
    DECALS.append(o)
    return o

def strip(name, matname, line, proj, width=0.004, offset=0.0028, closed=False):
    """A thin line (panel gap) along a polyline in a view."""
    pts = list(line)
    if closed:
        pts = pts + [pts[0]]
    L_, R_ = [], []
    for i, p in enumerate(pts):
        a = pts[max(0, i - 1)]; b = pts[min(len(pts) - 1, i + 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]; n = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / n * width / 2, dx / n * width / 2
        L_.append((p[0] + nx, p[1] + ny, 1)); R_.append((p[0] - nx, p[1] - ny, 1))
    return decal(name, matname, L_ + R_[::-1], proj, offset=offset, spacing=max(width, 0.01), smooth_it=0)

def lift_to(obj_mesh_pts, proj, offset):
    """Map flat (u, v, depth) points onto the body through a view (for text)."""
    out = []
    for (u, v, w) in obj_mesh_pts:
        h = hit(proj, u, v)
        out.append(None if h is None else h[0] + h[1] * (offset + w))
    return out

def text_decal(name, matname, txt, proj, cu, cv, size, offset=0.004, depth=0.0015, font=FONT_BOLD, spacing=1.0):
    cur = bpy.data.curves.new(name, "FONT")
    cur.body = txt; cur.size = size; cur.align_x = "CENTER"; cur.align_y = "CENTER"; cur.extrude = depth / 2
    cur.space_character = spacing
    cur.resolution_u = 4
    try:
        cur.font = bpy.data.fonts.load(font, check_existing=True)
    except Exception:
        pass
    tmp = bpy.data.objects.new(name + "_c", cur); bpy.context.scene.collection.objects.link(tmp)
    bpy.context.view_layer.update()
    me = evaluated_copy(tmp, name)
    bpy.data.objects.remove(tmp); bpy.data.curves.remove(cur)
    # the font lies in its XY plane facing +Z: x -> u (reading direction), y -> v, z -> lift off the surface
    pts = [(cu + v.co.x, cv + v.co.y, v.co.z + depth / 2) for v in me.vertices]
    P = lift_to(pts, proj, offset)
    bad = [i for i, p in enumerate(P) if p is None]
    if bad:
        print("TEXT", name, "vertices off the body:", len(bad))
    for v, p in zip(me.vertices, P):
        if p is not None:
            v.co = p
    me.materials.clear(); me.materials.append(MATS[matname])
    o = new_obj(name, me)
    # the view decides which way the letters read; flip winding if they face inwards
    me.update()
    DECALS.append(o)
    return o

# ---------------------------------------------------------------------------------------------------- side
DLO = [(1.073, 0.987, 1), (1.28, 1.006), (1.333, 1.014), (1.8, 1.05), (2.287, 1.085), (2.392, 1.085), (2.537, 1.09), (2.625, 1.12),
       (2.749, 1.136), (3.068, 1.141), (3.19, 1.141), (3.386, 1.152), (3.42, 1.19), (3.455, 1.263), (3.497, 1.40), (3.503, 1.452),
       (3.488, 1.48), (3.3, 1.496), (3.057, 1.507), (2.978, 1.508), (2.7, 1.516), (2.392, 1.523), (2.287, 1.528), (2.041, 1.5),
       (1.776, 1.447), (1.545, 1.366, 1)]
GLASS = [
    [(1.097, 1.0), (1.248, 1.016), (1.51, 1.338)],                                                        # front quarter light
    [(1.44, 1.04), (1.8, 1.063), (2.268, 1.099), (2.27, 1.508), (2.04, 1.485), (1.785, 1.435), (1.585, 1.36), (1.42, 1.15)],
    [(2.412, 1.1), (2.53, 1.103), (2.618, 1.134), (2.75, 1.151), (3.045, 1.155), (2.962, 1.494), (2.7, 1.502), (2.412, 1.508)],
    [(3.205, 1.157), (3.372, 1.166), (3.405, 1.2), (3.438, 1.265), (3.478, 1.4), (3.483, 1.448), (3.468, 1.47), (3.078, 1.492)],
]
FRONT_DOOR_GAP = [(1.282, 0.992), (1.272, 0.75), (1.266, 0.5), (1.265, 0.292)]
B_GAP = [(2.283, 1.08), (2.281, 0.75), (2.279, 0.292)]
SILL_GAP = [(1.265, 0.292), (2.0, 0.29), (2.8, 0.29), (3.08, 0.292)]
def rear_door_edge():
    pts = [(3.192, 1.138), (3.205, 0.95), (3.215, 0.80)]
    c, r = (SR, HUB), 0.452
    a0 = math.atan2(0.80 - c[1], 3.215 - c[0])
    a1 = math.atan2(0.292 - c[1], (c[0] - math.sqrt(max(0.0, r * r - (0.292 - c[1]) ** 2))) - c[0])
    for i in range(1, 13):
        a = a0 + (a1 - a0) * i / 12
        pts.append((c[0] + r * math.cos(a), c[1] + r * math.sin(a)))
    return pts

def side_details(sign):
    sd = side(sign)
    tag = "L" if sign > 0 else "R"
    decal("DLO_" + tag, "Trim", DLO, sd, offset=0.0022, spacing=0.05, smooth_it=2)
    for i, g in enumerate(GLASS):
        decal("Glass%d_%s" % (i, tag), "Glass", g, sd, offset=0.0042, spacing=0.05, smooth_it=3)
    for nm, line in (("gapA", FRONT_DOOR_GAP), ("gapB", B_GAP), ("gapS", SILL_GAP), ("gapC", rear_door_edge())):
        strip(nm + "_" + tag, "Trim", line, sd)
    # door handles: body-coloured grips over a dark recess
    for i, (s0, h0) in enumerate(((2.099, 0.856), (2.471, 0.895))):
        decal("HandleRecess%d_%s" % (i, tag), "Plastic", circle(s0, h0 - 0.004, 0.105, 28, 0.024), sd, offset=0.0018, spacing=0.012)
        decal("Handle%d_%s" % (i, tag), "Paint", circle(s0, h0, 0.098, 28, 0.017), sd, offset=0.003, spacing=0.012, dome=0.011, dome_w=0.012)
    decal("Repeater_" + tag, "Amber", circle(1.19, 0.85, 0.03, 24, 0.011), sd, offset=0.003, spacing=0.008, dome=0.004, dome_w=0.008)
    if sign < 0:   # fuel flap on the right
        strip("FuelFlap", "Trim", circle(3.66, 0.93, 0.058, 36), sd, closed=True)

# ---------------------------------------------------------------------------------------------------- front
GRILLE = sym([(0.0, 0.776), (0.2, 0.773), (0.33, 0.769), (0.394, 0.762), (0.372, 0.705), (0.332, 0.64), (0.292, 0.602), (0.15, 0.597), (0.0, 0.596)])
INTAKE = sym([(0.0, 0.398), (0.3, 0.398), (0.47, 0.392), (0.49, 0.36), (0.455, 0.3), (0.42, 0.272), (0.0, 0.27)])
FOG_HOUSING = [(0.548, 0.405), (0.62, 0.468), (0.70, 0.47), (0.725, 0.44), (0.715, 0.36), (0.655, 0.342), (0.565, 0.35)]
HEAD_FRONT_TOP = [(0.435, 0.654), (0.499, 0.776), (0.587, 0.852), (0.675, 0.904)]
HEAD_SIDE = [(0.643, 0.931), (0.481, 0.899), (0.505, 0.808), (0.359, 0.713), (0.25, 0.665)]
HEAD_FRONT_BOTTOM = [(0.70, 0.71), (0.613, 0.693), (0.512, 0.667)]

def front_details():
    decal("Grille", "Plastic", GRILLE, FRONT, offset=0.0025, spacing=0.03, smooth_it=2)
    for i, h in enumerate((0.722, 0.685, 0.648, 0.614)):
        w = 0.37 - (0.722 - h) * 0.75
        decal("Slat%d" % i, "Chrome", [(-w, h - 0.0045), (w, h - 0.0045), (w, h + 0.0045), (-w, h + 0.0045)], FRONT,
              offset=0.004, spacing=0.008, dome=0.004, dome_w=0.004, smooth_it=1)
    decal("GrilleBar", "Chrome", sym([(0.0, 0.784), (0.25, 0.781), (0.392, 0.774), (0.398, 0.764), (0.25, 0.761), (0.0, 0.758)]), FRONT,
          offset=0.0045, spacing=0.008, dome=0.006, dome_w=0.005, smooth_it=1)
    decal("BadgeRing", "Chrome", circle(0.0, 0.727, 0.056, 48), FRONT, offset=0.007, spacing=0.008, dome=0.006, dome_w=0.008)
    decal("BadgeCentre", "BadgeDark", circle(0.0, 0.727, 0.046, 48), FRONT, offset=0.0145, spacing=0.008)
    decal("BadgeMark", "Chrome", [(-0.022, 0.712), (0.0, 0.745), (0.022, 0.712), (0.012, 0.712), (0.0, 0.73), (-0.012, 0.712)], FRONT,
          offset=0.016, spacing=0.004, smooth_it=0)
    decal("Intake", "Plastic", INTAKE, FRONT, offset=0.0025, spacing=0.03, smooth_it=2)
    for i, h in enumerate((0.305, 0.338, 0.37)):
        decal("IntakeBar%d" % i, "AlloyDark", [(-0.45, h - 0.005), (0.45, h - 0.005), (0.45, h + 0.005), (-0.45, h + 0.005)], FRONT,
              offset=0.0045, spacing=0.01, dome=0.004, dome_w=0.004, smooth_it=1)
    decal("FrontLip", "Plastic", sym([(0.0, 0.25), (0.6, 0.25), (0.86, 0.26), (0.9, 0.18), (0.0, 0.17)]), FRONT, offset=0.002, spacing=0.05, smooth_it=1)
    for sgn in (1, -1):
        tag = "L" if sgn > 0 else "R"
        fh = [(sgn * p[0], p[1]) for p in FOG_HOUSING]
        decal("FogHousing_" + tag, "Plastic", fh, FRONT, offset=0.003, spacing=0.012)
        decal("FogRing_" + tag, "HeadChrome", circle(sgn * 0.656, 0.425, 0.036, 32), FRONT, offset=0.0045, spacing=0.006)
        decal("FogLens_" + tag, "HeadLens", circle(sgn * 0.656, 0.425, 0.026, 32), FRONT, offset=0.006, spacing=0.006, dome=0.004, dome_w=0.01)
        headlamp(sgn)
    # number plate: white, with the name the showroom plates carry
    decal("PlateF", "PlateWhite", [(-0.26, 0.416, 1), (0.26, 0.416, 1), (0.26, 0.527, 1), (-0.26, 0.527, 1)], FRONT, offset=0.006, spacing=0.03, smooth_it=1)
    text_decal("PlateTextF", "PlateText", "Meriva", FRONT, 0.0, 0.469, 0.085, offset=0.0075)

HEAD_F = [(0.435, 0.654), (0.499, 0.776), (0.587, 0.852), (0.675, 0.896), (0.748, 0.915), (0.792, 0.885), (0.80, 0.81),
          (0.765, 0.745), (0.70, 0.716), (0.613, 0.694), (0.512, 0.668)]
HEAD_TAIL = [(0.24, 0.86), (0.36, 0.888), (0.48, 0.908), (0.643, 0.931, 1), (0.55, 0.9), (0.45, 0.862), (0.34, 0.81), (0.24, 0.765)]

def headlamp(sgn):
    """The swept headlamp: the face seen from the front plus its tail running back along the wing."""
    tag = "L" if sgn > 0 else "R"
    f = [(sgn * x, h) for (x, h) in HEAD_F]
    decal("HeadBezel_" + tag, "HeadDark", f, FRONT, offset=0.003, spacing=0.012, smooth_it=3)
    decal("HeadTail_" + tag, "HeadLens", HEAD_TAIL, side(sgn), offset=0.0045, spacing=0.012, dome=0.004, dome_w=0.02, smooth_it=3)
    cx = sum(p[0] for p in f) / len(f); cy = sum(p[1] for p in f) / len(f)
    inner = [(cx + (x - cx) * 0.9, cy + (h - cy) * 0.86) for (x, h) in f]
    decal("HeadLens_" + tag, "HeadLens", inner, FRONT, offset=0.0055, spacing=0.012, dome=0.007, dome_w=0.03, smooth_it=3)
    for (x, h, r) in ((0.53, 0.722, 0.034), (0.648, 0.766, 0.046)):
        decal("HeadBowl_%s_%d" % (tag, int(r * 1000)), "HeadChrome", circle(sgn * x, h, r, 32), FRONT, offset=0.0135, spacing=0.008,
              dome=0.004, dome_w=0.01)
        decal("HeadProj_%s_%d" % (tag, int(r * 1000)), "HeadDark", circle(sgn * x, h, r * 0.42, 24), FRONT, offset=0.018, spacing=0.006)

# ---------------------------------------------------------------------------------------------------- rear
TAIL_REAR_TOP = [(0.423, 0.894), (0.466, 1.0), (0.605, 1.048), (0.709, 1.055)]
TAIL_SIDE = [(3.985, 1.051), (3.756, 1.059), (3.772, 1.027), (3.888, 0.951), (4.019, 0.853)]
TAIL_REAR_BOTTOM = [(0.709, 0.812), (0.605, 0.855), (0.466, 0.855), (0.423, 0.88)]

def rear_details():
    decal("RearGlassFrame", "Trim", sym([(0.0, 1.478), (0.4, 1.475), (0.57, 1.46), (0.66, 1.25), (0.685, 1.07), (0.6, 1.058), (0.0, 1.055)]),
          REAR, offset=0.0025, spacing=0.05, smooth_it=2)
    decal("RearGlass", "Glass", sym([(0.0, 1.452), (0.38, 1.45), (0.53, 1.437), (0.62, 1.25), (0.64, 1.093), (0.56, 1.083), (0.0, 1.08)]),
          REAR, offset=0.0045, spacing=0.05, smooth_it=2)
    decal("RearWiper", "Plastic", [(0.02, 1.097), (-0.42, 1.112), (-0.42, 1.124), (0.02, 1.109)], REAR, offset=0.008, spacing=0.01,
          dome=0.008, dome_w=0.005, smooth_it=1)
    decal("TailBar", "Chrome", sym([(0.0, 0.893), (0.362, 0.891), (0.37, 0.883), (0.362, 0.877), (0.0, 0.875)]), REAR,
          offset=0.004, spacing=0.008, dome=0.005, dome_w=0.004, smooth_it=1)
    decal("RBadgeRing", "Chrome", circle(0.0, 0.967, 0.06, 48), REAR, offset=0.006, spacing=0.008, dome=0.006, dome_w=0.008)
    decal("RBadgeCentre", "BadgeDark", circle(0.0, 0.967, 0.05, 48), REAR, offset=0.0135, spacing=0.008)
    decal("RBadgeMark", "Chrome", [(0.022, 0.952), (0.0, 0.985), (-0.022, 0.952), (-0.012, 0.952), (0.0, 0.97), (0.012, 0.952)], REAR,
          offset=0.015, spacing=0.004, smooth_it=0)
    decal("PlateR", "PlateYellow", [(-0.26, 0.719, 1), (0.26, 0.719, 1), (0.26, 0.83, 1), (-0.26, 0.83, 1)], REAR, offset=0.006, spacing=0.03, smooth_it=1)
    text_decal("PlateTextR", "PlateText", "Meriva", REAR_TEXT, 0.0, 0.772, 0.085, offset=0.0075)
    text_decal("BadgeMERIVA", "Chrome", "MERIVA", REAR_TEXT, -0.48, 0.79, 0.028, offset=0.004, spacing=1.15)
    text_decal("BadgeTURBO", "Chrome", "TURBO", REAR_TEXT, 0.475, 0.79, 0.028, offset=0.004, spacing=1.15)
    decal("RearLower", "Plastic", sym([(0.0, 0.33), (0.62, 0.33), (0.8, 0.31), (0.85, 0.22), (0.0, 0.24)]), REAR, offset=0.002, spacing=0.05, smooth_it=1)
    for sgn in (1, -1):
        taillamp(sgn)
        tag = "L" if sgn > 0 else "R"
        decal("Reflector_" + tag, "TailDark", [(sgn * 0.62, 0.43), (sgn * 0.76, 0.43), (sgn * 0.76, 0.452), (sgn * 0.62, 0.452)], REAR,
              offset=0.003, spacing=0.008, smooth_it=1)

def REAR_TEXT(u, v):
    """Rear view with the reading direction flipped (seen from behind, the car's left is on the viewer's left)."""
    return REAR(-u, v)

def taillamp(sgn):
    tag = "L" if sgn > 0 else "R"
    pts3 = []
    for (x, h) in TAIL_REAR_TOP:
        pts3.append(surface_point(REAR, sgn * x, h))
    for (s0, h) in TAIL_SIDE:
        pts3.append(surface_point(side(sgn), s0, h))
    for (x, h) in TAIL_REAR_BOTTOM:
        pts3.append(surface_point(REAR, sgn * x, h))
    pts3 = [p for p in pts3 if p is not None]
    c = sum(pts3, Vector()) / len(pts3)
    ob = Oblique((sgn * 0.6, 0.66, 0.3), c)
    out = [ob.to2d(p) for p in pts3]
    decal("TailDark_" + tag, "TailDark", out, ob, offset=0.003, spacing=0.012, smooth_it=3)
    up = [(a * 0.93, b * 0.88 + 0.012) for a, b in out]
    decal("TailRed_" + tag, "TailRed", up, ob, offset=0.0065, spacing=0.012, dome=0.006, dome_w=0.03, smooth_it=3)
    p0, p1 = surface_point(REAR, sgn * 0.47, 0.918), surface_point(REAR, sgn * 0.62, 0.92)
    if p0 is not None and p1 is not None:
        a, b = ob.to2d(p0), ob.to2d(p1)
        strip("Reverse_" + tag, "Reverse", [a, b], ob, width=0.024, offset=0.013)

# ---------------------------------------------------------------------------------------------------- top
def top_details():
    def ws(inset_side, s0, s1):
        left = [(F["rail"](s) - inset_side if F["rail"](s) - inset_side > 0 else 0.0, s) for s in [s0 + (s1 - s0) * i / 14 for i in range(15)]]
        return [(x, s) for x, s in left] + [(-x, s) for x, s in reversed(left)]
    decal("ScreenFrame", "Trim", ws(0.012, 0.99, 2.075), TOP, offset=0.0025, spacing=0.06, smooth_it=2)
    decal("Screen", "Glass", ws(0.06, 1.035, 2.03), TOP, offset=0.0045, spacing=0.06, smooth_it=2)
    decal("Cowl", "Plastic", [(-0.72, 0.955), (0.72, 0.955), (0.73, 0.995), (-0.73, 0.995)], TOP, offset=0.002, spacing=0.02, smooth_it=1)
    # wipers parked along the base of the screen
    for x0, x1 in ((0.22, 0.74), (-0.36, 0.16)):
        decal("Wiper_%d" % int(x0 * 100), "Plastic", [(x0, 1.045), (x1, 1.052), (x1, 1.064), (x0, 1.057)], TOP, offset=0.01, spacing=0.01,
              dome=0.01, dome_w=0.005, smooth_it=1)
    # bonnet shut lines along the wings
    for sgn in (1, -1):
        strip("BonnetGap_%d" % sgn, "Trim", [(sgn * 0.70, 0.66), (sgn * 0.735, 0.8), (sgn * 0.75, 0.95)], TOP)

# ---------------------------------------------------------------------------------------------------- parts
def add_mesh_obj(name, bm, matnames, parent=None):
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    for n in matnames:
        me.materials.append(MATS[n])
    return new_obj(name, me, parent)

def lathe(bm, profile, segs, mat_index=0, axis_x=True):
    """Spin (r, x) points round the X axis."""
    rings = []
    for (r, x) in profile:
        ring = []
        for k in range(segs):
            a = 2 * math.pi * k / segs
            ring.append(bm.verts.new((x, r * math.cos(a), r * math.sin(a))))
        rings.append(ring)
    for i in range(len(rings) - 1):
        for k in range(segs):
            k2 = (k + 1) % segs
            f = bm.faces.new((rings[i][k], rings[i][k2], rings[i + 1][k2], rings[i + 1][k]))
            f.material_index = mat_index
    return rings

def box_between(bm, corners, mat_index):
    """Hexahedron from 8 corners: 0-3 one end (ccw), 4-7 the other end."""
    v = [bm.verts.new(c) for c in corners]
    for q in ((0, 1, 2, 3), (7, 6, 5, 4), (0, 4, 5, 1), (1, 5, 6, 2), (2, 6, 7, 3), (3, 7, 4, 0)):
        f = bm.faces.new([v[i] for i in q]); f.material_index = mat_index

FIRST_SPOKE_FACE = [0]

def wheel_mesh():
    """One wheel (tyre, 10-spoke alloy, brake disc), axle along X, outer face towards +X, centre at the origin."""
    bm = bmesh.new()
    MAT = ["Tyre", "Alloy", "AlloyDark", "Disc", "BadgeDark", "Chrome"]
    R = TYRE_R
    tyre = [(0.219, -0.094), (0.255, -0.104), (0.292, -0.108), (0.309, -0.1), (R - 0.002, -0.084), (R, -0.06), (R, 0.06), (R - 0.002, 0.084),
            (0.309, 0.1), (0.292, 0.108), (0.255, 0.104), (0.219, 0.094)]
    lathe(bm, tyre, 72, 0)
    lathe(bm, [(0.229, 0.07), (0.232, 0.078), (0.226, 0.084), (0.214, 0.082), (0.2, 0.072)], 72, 1)      # polished lip
    lathe(bm, [(0.201, 0.072), (0.201, -0.09), (0.12, -0.09)], 48, 2)                                    # barrel, dark inside
    lathe(bm, [(0.0, -0.035), (0.146, -0.035), (0.146, -0.01), (0.0, -0.01)], 40, 3)                    # brake disc
    lathe(bm, [(0.074, 0.05), (0.074, 0.08), (0.07, 0.088), (0.036, 0.093), (0.034, 0.098), (0.0, 0.098)], 36, 1)   # hub face
    lathe(bm, [(0.027, 0.097), (0.026, 0.101), (0.0, 0.101)], 24, 4)                                     # centre cap
    # ten spokes in five V pairs, dished: inner end proud of the rim face
    FIRST_SPOKE_FACE[0] = len(bm.faces)
    for pair in range(5):
        base = 2 * math.pi * pair / 5
        for side_ in (-1, 1):
            a_in = base + side_ * math.radians(5.5)
            a_out = base + side_ * math.radians(13.0)
            w_in, w_out = 0.0115, 0.0175
            r_in, r_out = 0.066, 0.212
            x_in, x_out = 0.088, 0.073
            t = 0.022
            def pt(r, a, dw, x):
                ca, sa = math.cos(a), math.sin(a)
                return (x, r * ca - dw * sa, r * sa + dw * ca)
            box_between(bm, [pt(r_in, a_in, -w_in, x_in), pt(r_in, a_in, w_in, x_in), pt(r_in, a_in, w_in, x_in - t), pt(r_in, a_in, -w_in, x_in - t),
                             pt(r_out, a_out, -w_out, x_out), pt(r_out, a_out, w_out, x_out), pt(r_out, a_out, w_out, x_out - t), pt(r_out, a_out, -w_out, x_out - t)], 1)
    for k in range(5):   # wheel nuts
        a = 2 * math.pi * (k + 0.5) / 5
        cy, cz = 0.048 * math.cos(a), 0.048 * math.sin(a)
        ring1, ring2 = [], []
        for j in range(10):
            b = 2 * math.pi * j / 10
            ring1.append(bm.verts.new((0.09, cy + 0.0105 * math.cos(b), cz + 0.0105 * math.sin(b))))
            ring2.append(bm.verts.new((0.101, cy + 0.0085 * math.cos(b), cz + 0.0085 * math.sin(b))))
        for j in range(10):
            j2 = (j + 1) % 10
            f = bm.faces.new((ring1[j], ring1[j2], ring2[j2], ring2[j])); f.material_index = 5
        f = bm.faces.new(ring2); f.material_index = 5
        f = bm.faces.new(list(reversed(ring1))); f.material_index = 5
    bm.faces.index_update()
    closed = [f for f in bm.faces if f.index >= FIRST_SPOKE_FACE[0]]
    bmesh.ops.recalc_face_normals(bm, faces=closed)
    me = bpy.data.meshes.new("Wheel"); bm.to_mesh(me); bm.free()
    for n in MAT:
        me.materials.append(MATS[n])
    me.shade_smooth()
    try:
        me.set_sharp_from_angle(angle=math.radians(40))
    except Exception:
        pass
    return me

def wheels():
    me = wheel_mesh()
    out = []
    for nm, s0, tr in (("Wheel_FL", SF, TRACK_F), ("Wheel_FR", SF, -TRACK_F), ("Wheel_RL", SR, TRACK_R), ("Wheel_RR", SR, -TRACK_R)):
        e = bpy.data.objects.new(nm, None)
        bpy.context.scene.collection.objects.link(e)
        e.location = (tr, s0 - HL, HUB)
        w = bpy.data.objects.new(nm + "_mesh", me)
        bpy.context.scene.collection.objects.link(w)
        w.parent = e
        if tr < 0:
            w.rotation_euler = (0, 0, math.pi)
        out.append(e)
        # brake caliper (stays put while the wheel turns): behind the disc at the back
        bm = bmesh.new()
        a0, a1 = math.radians(15), math.radians(62)
        side_ = 1 if tr > 0 else -1
        x0, x1 = tr - side_ * 0.055, tr - side_ * 0.002
        corners = []
        for x in (x0, x1):
            for (r, a) in ((0.112, a0), (0.152, a0), (0.152, a1), (0.112, a1)):
                corners.append((x, s0 - HL + r * math.cos(a), HUB + r * math.sin(a)))
        box_between(bm, corners[:4] + corners[4:], 0)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        cal = add_mesh_obj("Caliper_" + nm[-2:], bm, ["Caliper"])
        DECALS.append(cal)
    return out

def mirrors():
    for sgn in (1, -1):
        tag = "L" if sgn > 0 else "R"
        bm = bmesh.new()
        bmesh.ops.create_uvsphere(bm, u_segments=28, v_segments=16, radius=1.0)
        for v in bm.verts:
            x, y, z = v.co
            if y > 0.35:          # flat back where the glass sits
                y = 0.35 + (y - 0.35) * 0.15
            v.co = Vector((x * 0.088, y * 0.07, z * 0.063))
        for f in bm.faces:
            f.material_index = 0
        # glass on the back face
        g = bmesh.ops.create_circle(bm, cap_ends=True, radius=1.0, segments=28)
        for v in g["verts"]:
            v.co = Vector((v.co.x * 0.075, 0.0, v.co.y * 0.05)) + Vector((0, 0.0345, 0))
        for f in bm.faces:
            if all(abs(v.co.y - 0.0345) < 1e-6 for v in f.verts):
                f.material_index = 1
        # arm down to the door
        arm = bmesh.ops.create_cube(bm, size=1.0)
        for v in arm["verts"]:
            v.co = Vector((v.co.x * 0.06 - sgn * 0.065, v.co.y * 0.05, v.co.z * 0.03 - 0.05))
        for f in bm.faces:
            if all(v in arm["verts"] for v in f.verts):
                f.material_index = 2
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        o = add_mesh_obj("Mirror_" + tag, bm, ["Paint", "MirrorGlass", "Plastic"])
        o.location = (sgn * 0.9, 1.493 - HL, 1.09)
        o.rotation_euler = (0, 0, -sgn * 0.12)
        o.data.shade_smooth()
        DECALS.append(o)

def spoiler():
    """Roof spoiler over the rear glass with the high-level brake light in its trailing edge."""
    bm = bmesh.new()
    N = 31
    rows = []
    for i in range(N):
        x = -0.6 + 1.2 * i / (N - 1)
        k = (abs(x) / 0.6) ** 4
        drop = 0.042 * (x / 0.62) ** 2
        s_tr = 3.875 - 0.09 * k
        prof = [(3.66, 1.548), (3.76, 1.545), (s_tr - 0.02, 1.536), (s_tr, 1.522), (s_tr - 0.01, 1.506), (s_tr - 0.08, 1.508), (3.72, 1.52)]
        rows.append([bm.verts.new((x, s - HL, h - drop)) for (s, h) in prof])
    n = len(rows[0])
    for i in range(N - 1):
        for j in range(n):
            j2 = (j + 1) % n
            bm.faces.new((rows[i][j], rows[i][j2], rows[i + 1][j2], rows[i + 1][j]))
    bm.faces.new(rows[0]); bm.faces.new(list(reversed(rows[-1])))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    o = add_mesh_obj("Spoiler", bm, ["Paint"])
    o.data.shade_smooth()
    DECALS.append(o)
    # brake light on the trailing edge
    bm = bmesh.new()
    q = [bm.verts.new((x, 3.877 - HL, h)) for (x, h) in ((-0.14, 1.509), (-0.14, 1.52), (0.14, 1.52), (0.14, 1.509))]
    bm.faces.new(q)
    bl = add_mesh_obj("BrakeLight", bm, ["BrakeLight"])
    DECALS.append(bl)

def aerial():
    p = surface_point(TOP, 0.0, 3.6)
    if p is None:
        return
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, segments=8, radius1=0.006, radius2=0.0025, depth=0.27,
                          matrix=Matrix.Translation(p) @ Matrix.Rotation(-0.7, 4, "X") @ Matrix.Translation((0, 0, 0.135)))
    bmesh.ops.create_cone(bm, cap_ends=True, segments=12, radius1=0.016, radius2=0.012, depth=0.02, matrix=Matrix.Translation(p + Vector((0, 0, 0.008))))
    o = add_mesh_obj("Aerial", bm, ["Plastic"])
    DECALS.append(o)

def exhaust():
    bm = bmesh.new()
    lathe(bm, [(0.0, -0.14), (0.026, -0.14), (0.031, -0.13), (0.031, 0.0), (0.024, 0.0), (0.024, -0.1)], 24, 0)
    o = add_mesh_obj("Exhaust", bm, ["Exhaust"])
    o.location = (0.43, 4.2 - HL, 0.245)
    o.rotation_euler = (0, 0, math.pi / 2)
    DECALS.append(o)

def build_details(body):
    global BVH
    BVH = body_bvh(body)
    side_details(1); side_details(-1)
    front_details()
    rear_details()
    top_details()
    mirrors(); spoiler(); aerial(); exhaust()
    ws = wheels()
    print("DECALS", len(DECALS), "verts", sum(len(o.data.vertices) for o in DECALS if o.type == "MESH"))
    return ws
