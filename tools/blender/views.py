"""Cameras that match the views in the reference photo (2x crops in ref/). Blender axes: +X car's left, -Y front, +Z up."""
import math
HL = 4.288 / 2
def side_left():
    # Solved from the photo: hubs 379.5 px/m (wheelbase), centre-line ends 344.9 px/m (length) -> 8.78 m from the
    # centre line, abeam s = 2.324 m from the nose, camera 1.1 m up and level.
    return dict(name="side_left", ref="ref/side_left.png", W=1500, H=650, loc=(8.776, 2.324 - HL, 1.1),
                rot=(math.pi / 2, 0, math.pi / 2), f=3027, cx=806.6, cy=224.4)

def side_right():
    # The complete side photo (nose and tail both in frame). Rims fitted at (258.3, 528.5) and (1299.7, 521.9):
    # 393.9 px/m at the hubs; nose-to-tail 1569 px -> 365.9 px/m on the centre line -> 11.26 m away, abeam s = 2.118.
    return dict(name="side_right", ref="ref/side_right.png", W=1600, H=670, loc=(-11.26, 2.118 - HL, 1.0),
                rot=(math.pi / 2, 0, -math.pi / 2), f=4120, cx=814, cy=255.8)

def look(name, ref, W, H, az_deg, dist, height, target=(0.0, 0.0, 0.72), lens_mm=60, ortho=None):
    """Camera on a circle round the car: az 0 = straight ahead of the nose, 90 = the car's left side, 180 = behind."""
    a = math.radians(az_deg)
    tx, ty, tz = target
    loc = (tx + dist * math.sin(a), ty - dist * math.cos(a), height)
    return dict(name=name, ref=ref, W=W, H=H, loc=loc, target=target, lens=lens_mm, ortho=ortho)

def front():    return look("front", "ref/front.png", 830, 660, 0, 7.5, 0.55, (0, -0.2, 0.78), 82)
def rear():     return look("rear", "ref/rear.png", 712, 660, 180, 9.5, 0.45, (0, 0.2, 0.8), 92)
def front34():  return look("front34", "ref/front34.png", 1372, 670, 38, 9.0, 1.15, (0.05, -0.15, 0.72), 70)
def rear34():   return look("rear34", "ref/rear34.png", 1320, 728, 128, 8.6, 1.25, (0.05, 0.25, 0.72), 66)
def top():      return dict(name="top", ref="ref/top.png", W=1672, H=718, loc=(0, 0, 25), target=(0, 0, 0), lens=None, ortho=4.62, rot=(0, 0, -math.pi / 2))
