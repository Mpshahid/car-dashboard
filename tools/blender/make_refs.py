"""Cut the user's photo montage (ref/meriva_views.webp) into the per-view reference images (2x) that views.py uses."""
from PIL import Image
im = Image.open("ref/meriva_views.webp").convert("RGB")
crops = {"side_left": (418, 0, 1168, 325), "front": (0, 0, 415, 330), "rear": (1180, 0, 1536, 330), "side_right": (30, 330, 830, 665),
         "front34": (850, 330, 1536, 665), "rear34": (0, 660, 660, 1024), "top": (700, 665, 1536, 1024)}
for k, b in crops.items():
    c = im.crop(b)
    c.resize((c.width * 2, c.height * 2), Image.LANCZOS).save("ref/%s.png" % k)
print("ok")
