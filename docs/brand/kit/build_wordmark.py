"""Builds the Kalapod wordmark: Vazirmatn Bold (OFL) shaped with HarfBuzz, converted to outlines,
with every upright stem (alefs, lam) given a round top that echoes the tally strokes of the symbol.
Output: wordmark.svg (black) in font units scaled to 150 px/em, baseline at y=0 → placed by lockups."""
import uharfbuzz as hb
from fontTools.ttLib import TTFont
from fontTools.pens.basePen import BasePen
from shapely.geometry import Polygon, Point, box
from shapely.ops import unary_union

F = r"C:/Users/iranian/Desktop/Kalapod/app/src/main/res/font/vazirmatn_bold.ttf"
TEXT = "کالاپاد"
STEM_GLYPHS = {"uniFE8E", "uniFEFB"}          # final alef, lam-alef ligature

class Flatten(BasePen):
    def __init__(self, gs, dx):
        super().__init__(gs); self.dx = dx; self.contours = []; self.cur = []
    def _moveTo(self, p): self.cur = [self._t(p)]
    def _lineTo(self, p): self.cur.append(self._t(p))
    def _qCurveToOne(self, c, p):
        (x0, y0) = self._getCurrentPoint(); (cx, cy) = c; (x1, y1) = p
        for i in range(1, 9):
            t = i / 8
            self.cur.append(self._t(((1-t)**2*x0 + 2*(1-t)*t*cx + t*t*x1, (1-t)**2*y0 + 2*(1-t)*t*cy + t*t*y1)))
    def _curveToOne(self, c1, c2, p):
        (x0, y0) = self._getCurrentPoint()
        for i in range(1, 11):
            t = i / 10; u = 1 - t
            x = u**3*x0 + 3*u*u*t*c1[0] + 3*u*t*t*c2[0] + t**3*p[0]
            y = u**3*y0 + 3*u*u*t*c1[1] + 3*u*t*t*c2[1] + t**3*p[1]
            self.cur.append(self._t((x, y)))
    def _closePath(self):
        if len(self.cur) > 2: self.contours.append(self.cur)
        self.cur = []
    _endPath = _closePath
    def _t(self, p): return (p[0] + self.dx, p[1])

def glyph_geom(gs, name, dx):
    pen = Flatten(gs, dx); gs[name].draw(pen)
    # even-odd assembly: bigger contours first, holes subtract
    polys = sorted((Polygon(c).buffer(0) for c in pen.contours), key=lambda p: -p.area)
    g = Polygon()
    for p in polys:
        g = g.difference(p) if g.contains(p) else g.union(p)
    return g

def round_stem_tops(g, x0, x1):
    """Find upright stems that reach the cap height inside [x0,x1] and round their tops."""
    top = g.bounds[3]
    band = g.intersection(box(x0, top - 120, x1, top + 1))
    for stem in getattr(band, "geoms", [band]):
        sx0, _, sx1, _ = stem.bounds
        r = (sx1 - sx0) / 2; cx = (sx0 + sx1) / 2
        g = g.difference(box(sx0 - 1, top - r, sx1 + 1, top + 1)).union(Point(cx, top - r).buffer(r, quad_segs=24))
    return g

face = hb.Face(open(F, "rb").read()); font = hb.Font(face)
buf = hb.Buffer(); buf.add_str(TEXT); buf.guess_segment_properties(); hb.shape(font, buf, {})
tt = TTFont(F); gs = tt.getGlyphSet(); order = tt.getGlyphOrder()
parts, x = [], 0
for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
    name = order[info.codepoint]
    g = glyph_geom(gs, name, x + pos.x_offset)
    if name in STEM_GLYPHS:
        b = g.bounds
        g = round_stem_tops(g, b[0], b[2])
    parts.append(g); x += pos.x_advance
word = unary_union(parts).simplify(1.5, preserve_topology=True)
width = x
def path(geom):
    out = []
    for p in getattr(geom, "geoms", [geom]):
        for ring in [p.exterior, *p.interiors]:
            out.append("M" + " L".join(f"{px:.0f} {-py:.0f}" for px, py in ring.coords) + " Z")
    return " ".join(out)
open("wordmark.d", "w").write(f"{width}\n{path(word)}")
open("wordmark.svg", "w", encoding="utf-8").write(
    f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -1500 {width} 2250"><title>کالاپاد</title>'
    f'<path fill="#000" fill-rule="evenodd" d="{path(word)}"/></svg>\n')
print("width", width)
