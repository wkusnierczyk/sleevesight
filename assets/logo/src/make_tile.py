"""The text tile, the alternative logo: "Sleeve" over "sight", right-aligned, on a rounded square.

Roboto Flex at regular weight, 88% width and the optical size for 52.64 px (the size the tile was
chosen at, 56 px on the brand sheet), outlined, so the logo needs no font. Placed so that the dot of
the i in "sight" is at (0, 0), the centre of the tile (-72 to 72), which the intro turns it about.
Writes tile-light.svg, tile-dark.svg and src/tile.json (the paths and the words' ink box, and each
letter's path and ink box, `letters`). The letters' paths are copied by hand into the `#words` of the
product page (`index.html`) and of the brand sheet, one path to a letter, so an intro can show or turn
each letter on its own; a line's path is its letters' paths one after another. From
assets/logo, with fontTools and uharfbuzz installed (pip):

    python3 src/make_tile.py RobotoFlex.ttf

The font is the variable Roboto Flex from github.com/google/fonts (ofl/robotoflex, OFL); it is not
kept here, since only its outlines are.
"""
import json, os, sys
import uharfbuzz as hb
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import DecomposingRecordingPen

AXES = {'wght': 400, 'wdth': 88, 'opsz': 52.64}
SIZE = 37.6              # the text's size in the 144-unit tile (40 px * .94, as on the brand sheet)
PITCH = .9 * SIZE        # line height .9
TRACK = -.015 * SIZE     # letter spacing -.015em, between letters only
COLORS = {'light': ('#fff', 'none', '#141a22', '#cf222e'), 'dark': ('#000', '#ffffff26', '#fff', '#f0443a')}

src = sys.argv[1]
hbfont = hb.Font(hb.Face(hb.Blob.from_file_path(src)))
hbfont.set_variations(AXES)
font = instantiateVariableFont(TTFont(src), AXES)
glyphs, order, cmap = font.getGlyphSet(), font.getGlyphOrder(), font.getBestCmap()
k = SIZE / font['head'].unitsPerEm

def shape(text):
    """The glyphs of a line, with their pen positions in font units, and the line's advance."""
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(hbfont, buf, {'kern': True, 'liga': True})
    x, out, n = 0, [], len(buf.glyph_infos)
    for i, (info, pos) in enumerate(zip(buf.glyph_infos, buf.glyph_positions)):
        out.append((order[info.codepoint], x + pos.x_offset, pos.y_offset))
        x += pos.x_advance + (TRACK / k if i < n - 1 else 0)
    return out, x

def placed(line, dx, dy, pen):
    """Draws a line onto `pen`, in tile units (y down), its pen start at dx and baseline at dy."""
    for name, gx, gy in line:
        glyphs[name].draw(TransformPen(pen, (k, 0, 0, -k, dx + gx * k, dy - gy * k)))

def bounds(draw):
    pen = BoundsPen(glyphs)
    draw(pen)
    return pen.bounds

sleeve, w1 = shape('Sleeve')
sight, w2 = shape('sight')
# Right-aligned at x = 0, the first baseline at 0, for now.
x1, x2, b1, b2 = -w1 * k, -w2 * k, 0.0, PITCH
# The dot: of the i's contours, the one whose bottom is highest.
name, ix, iy = next(g for g in sight if g[0] == cmap[ord('i')])
rec = DecomposingRecordingPen(glyphs)
glyphs[name].draw(rec)
contours, cur = [], []
for op, args in rec.value:
    cur.append((op, args))
    if op in ('closePath', 'endPath'):
        contours.append(cur)
        cur = []
dot = max((bounds(lambda p, c=c: [getattr(p, op)(*args) for op, args in c]) for c in contours), key=lambda b: b[1])
cx = x2 + (ix + (dot[0] + dot[2]) / 2) * k
cy = b2 - (iy + (dot[1] + dot[3]) / 2) * k
x1, x2, b1, b2 = x1 - cx, x2 - cx, b1 - cy, b2 - cy

num = lambda v: ('%.2f' % v).rstrip('0').rstrip('.')
paths = []
for line, dx, dy in ((sleeve, x1, b1), (sight, x2, b2)):
    pen = SVGPathPen(glyphs, ntos=num)
    placed(line, dx, dy, pen)
    paths.append(pen.getCommands())
ink = bounds(lambda p: (placed(sleeve, x1, b1, p), placed(sight, x2, b2, p)))
letters = {}
for i, (key, line, dx, dy) in enumerate((('sleeve', sleeve, x1, b1), ('sight', sight, x2, b2))):
    letters[key] = []
    for glyph in line:
        pen = SVGPathPen(glyphs, ntos=num)
        placed([glyph], dx, dy, pen)
        box = bounds(lambda p: placed([glyph], dx, dy, p))
        letters[key].append({'d': pen.getCommands(), 'ink': [round(v, 2) for v in box]})
    assert ''.join(l['d'] for l in letters[key]) == paths[i]

here = os.path.dirname(os.path.abspath(__file__))
json.dump({'sleeve': paths[0], 'sight': paths[1], 'ink': [round(v, 2) for v in ink], 'letters': letters},
          open(os.path.join(here, 'tile.json'), 'w'))
for scheme, (bg, edge, ink_color, red) in COLORS.items():
    open(os.path.join(here, '..', f'tile-{scheme}.svg'), 'w').write(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="-72 -72 144 144" width="144" height="144">'
        f'<rect x="-71.5" y="-71.5" width="143" height="143" rx="31.5" fill="{bg}" stroke="{edge}"/>'
        f'<path fill="{ink_color}" d="{paths[0]}"/><path fill="{red}" d="{paths[1]}"/></svg>\n')
print('ink box', [round(v, 2) for v in ink])
