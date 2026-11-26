import sys
from PIL import Image, ImageChops, ImageFilter
ref, ren, out = sys.argv[1:4]
a = Image.open(ref).convert("RGBA"); b = Image.open(ren).convert("RGBA").resize(a.size)
mask = b.split()[3].point(lambda v: 255 if v > 128 else 0)
edge = mask.filter(ImageFilter.FIND_EDGES).filter(ImageFilter.MaxFilter(3))
half = Image.blend(a, Image.alpha_composite(a, b), 0.5)
red = Image.new("RGBA", a.size, (255, 30, 30, 255))
half.paste(red, (0, 0), edge)
half.convert("RGB").save(out)
