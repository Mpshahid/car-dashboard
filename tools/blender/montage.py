import sys
from PIL import Image, ImageDraw
names = sys.argv[1].split(","); out = sys.argv[2]
rows = []
for n in names:
    ref = Image.open("ref/%s.png" % n).convert("RGB")
    r = Image.open("out/r_%s.png" % n).convert("RGBA")
    bg = Image.new("RGBA", r.size, (232, 234, 238, 255)); bg.alpha_composite(r); r = bg.convert("RGB").resize(ref.size)
    h = 330; ref = ref.resize((int(ref.width * h / ref.height), h)); r = r.resize(ref.size)
    row = Image.new("RGB", (ref.width * 2 + 10, h), "white"); row.paste(ref, (0, 0)); row.paste(r, (ref.width + 10, 0)); rows.append(row)
W = max(r.width for r in rows); H = sum(r.height + 8 for r in rows)
m = Image.new("RGB", (W, H), "white"); y = 0
for r in rows: m.paste(r, (0, y)); y += r.height + 8
m.save(out)
