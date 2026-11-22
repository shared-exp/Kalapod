"""Builds the Kalapod 'Tally' symbol masters with boolean geometry (shapely).
Run: python build_symbol.py  → symbol.svg (master) and symbol-small.svg (16–32 px cut)."""
from shapely.geometry import LineString, box
from shapely.ops import unary_union
from shapely import affinity

def rbar(x, y, w, h):
    r = w / 2
    return box(x + r - 0.001, y + r, x + w - r + 0.001, y + h - r).buffer(r, quad_segs=24)

def tally(xs, bar_w, top, height, tick, tick_w, gutter):
    bars = unary_union([rbar(x, top, bar_w, height) for x in xs])
    line = LineString(tick)
    shape = unary_union([bars.difference(line.buffer(tick_w / 2 + gutter, quad_segs=24)),
                         line.buffer(tick_w / 2, quad_segs=24)])
    shape = shape.simplify(0.12, preserve_topology=True)   # drop redundant points, keep curves smooth
    minx, miny, maxx, maxy = shape.bounds
    return affinity.translate(shape, 128 - (minx + maxx) / 2, 125 - (miny + maxy) / 2)

def path(geom):
    out = []
    for p in getattr(geom, "geoms", [geom]):
        for ring in [p.exterior, *p.interiors]:
            out.append("M" + " L".join(f"{x:.2f} {y:.2f}" for x, y in ring.coords) + " Z")
    return " ".join(out)

def write(name, geom, title, note):
    open(name, "w", encoding="utf-8").write(f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256">
  <title>{title}</title>
  <!-- {note} -->
  <path fill="#000" fill-rule="evenodd" d="{path(geom)}"/>
</svg>
''')

# Master: four strokes 24 wide / 14 gap, tick 24 wide at exact 45°, 12 px gutter.
master = tally((60, 98, 136, 174), 24, 58, 140, [(44, 148), (90, 194), (218, 66)], 24, 12)
write("symbol.svg", master, "Kalapod", "Master symbol: four tally strokes closed by a check mark. Strokes 24/14, tick 24 @45°, gutter 12.")

# Small-size cut (≤32 px): three heavier strokes, wider gutter, so gaps stay open on screen.
small = tally((64, 112, 160), 32, 56, 144, [(44, 150), (94, 200), (222, 72)], 32, 16)
write("symbol-small.svg", small, "Kalapod (small)", "Small-size cut for 16–32 px: three strokes 32/16, tick 32, gutter 16.")
