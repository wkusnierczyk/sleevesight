"""The app's logo outlines (#383): the stacked mark's words, and where "sight" moves for the one-line
header logo, as a Kotlin source file, so the app draws its logo with no font file.

The words are the stacked mark's (8b, `index.html`'s `#words`, from make_tile.py): Roboto Flex
regular at 88% width and the optical size 52.64, 37.6 units to the em, letter-spacing -.015em,
placed so that the dot of the i is at (0, 0). The record is the page's `#lp`, 37.5 units square at
(54.35, -4.59), its rim 7.5 of its 16: a disc of radius 17.58 centred at (73.1, 14.16), from the top
of the "h" to the foot of the "g", 10 units after the "t".

The one-line logo (as `_includes/logo-linear.html` sets it) is the same type, "Sleevesight" shaped
as one word, so "sight" follows "Sleeve" with the pair's kerning and the letter-spacing; "Sleeve"
stays where it is in the stacked mark, and "sight" and the record, which keeps its place beside
"sight", move together by (LINE_DX, LINE_DY). From assets/logo, with fontTools and uharfbuzz
installed (pip):

    python3 src/make_app_logo.py RobotoFlex.ttf \\
        ../../app/src/main/kotlin/app/sleevesight/app/ui/logo/LogoOutlines.kt

The font is the variable Roboto Flex from github.com/google/fonts (ofl/robotoflex, OFL); it is not
kept here. The words' paths are checked against src/tile.json, which make_tile.py writes from the
same font, so the app's logo and the page's cannot drift apart unnoticed.
"""
import json, os, re, sys
import uharfbuzz as hb
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import DecomposingRecordingPen

AXES = {'wght': 400, 'wdth': 88, 'opsz': 52.64}
SIZE = 37.6
TRACK = -.015 * SIZE

src, out = sys.argv[1], sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
tile = json.load(open(os.path.join(here, 'tile.json')))
hbfont = hb.Font(hb.Face(hb.Blob.from_file_path(src)))
hbfont.set_variations(AXES)
font = instantiateVariableFont(TTFont(src), AXES)
glyphs, order = font.getGlyphSet(), font.getGlyphOrder()
k = SIZE / font['head'].unitsPerEm


def shape(text):
    """The glyphs of a line with their pen positions in font units, as make_tile.py shapes them."""
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(hbfont, buf, {'kern': True, 'liga': True})
    x, glyphs_out, n = 0, [], len(buf.glyph_infos)
    for i, (info, pos) in enumerate(zip(buf.glyph_infos, buf.glyph_positions)):
        glyphs_out.append((order[info.codepoint], x + pos.x_offset, pos.y_offset))
        x += pos.x_advance + (TRACK / k if i < n - 1 else 0)
    return glyphs_out


num = lambda v: ('%.2f' % v).rstrip('0').rstrip('.')


def path(line, dx, dy):
    pen = SVGPathPen(glyphs, ntos=num)
    for name, gx, gy in line:
        glyphs[name].draw(TransformPen(pen, (k, 0, 0, -k, dx + gx * k, dy - gy * k)))
    return pen.getCommands()


def advance(text):
    """A line's advance in font units, with the letter-spacing between its letters."""
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(hbfont, buf, {'kern': True, 'liga': True})
    n = len(buf.glyph_positions)
    return sum(p.x_advance for p in buf.glyph_positions) + TRACK / k * (n - 1)


def bounds(draw):
    pen = BoundsPen(glyphs)
    draw(pen)
    return pen.bounds


# Placed as make_tile.py places them: right-aligned, then moved so the dot of the i is at (0, 0).
sleeve, sight = shape('Sleeve'), shape('sight')
x1, x2, b1, b2 = -advance('Sleeve') * k, -advance('sight') * k, 0.0, .9 * SIZE
name, ix, iy = next(g for g in sight if g[0] == font.getBestCmap()[ord('i')])
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
sx, sy, tx, ty = x1 - cx, b1 - cy, x2 - cx, b2 - cy
if path(sleeve, sx, sy) != tile['sleeve'] or path(sight, tx, ty) != tile['sight']:
    sys.exit("the words differ from src/tile.json: remake it with make_tile.py first")

# One word: "sight" starts where the shaping of "Sleevesight" puts its first glyph.
whole = shape('Sleevesight')
lx = sx + whole[len(sleeve)][1] * k
dx, dy = lx - tx, sy - ty
# The same glyphs, at the same spacing, so "sight" moved is the one-line word's.
tail = whole[len(sleeve):]
if [g[0] for g in tail] != [g[0] for g in sight] or any(
        abs((a[1] - tail[0][1]) - b[1]) > 1e-6 for a, b in zip(tail, sight)):
    sys.exit("the one-line \"sight\" is not the stacked one moved")



def kotlin(d, indent=' ' * 12, width=100):
    """A path as a Kotlin constant expression, in pieces of at most `width` characters, each
    broken before a command."""
    commands = re.findall(r'[A-Za-z][^A-Za-z]*', d)
    pieces = ['']
    for c in commands:
        if pieces[-1] and len(pieces[-1]) + len(c) > width:
            pieces.append('')
        pieces[-1] += c
    return (' +\n' + indent).join(f'"{p}"' for p in pieces)


kt = f'''package app.sleevesight.app.ui.logo

// Generated by assets/logo/src/make_app_logo.py from Roboto Flex; do not edit by hand.

/**
 * The logo's outlines, in the stacked mark's units (37.6 to the em, the dot of the i at the
 * origin, y down): "Sleeve" and "sight" as the product page's `#words`, and the record beside
 * "sight". The one-line logo moves "sight" and the record by ([LINE_DX], [LINE_DY]).
 */
internal object LogoOutlines {{
    const val SLEEVE: String =
        {kotlin(tile['sleeve'])}

    const val SIGHT: String =
        {kotlin(tile['sight'])}

    /** The record's centre and radius (the page's `#lp`, 37.5 square at (54.35, -4.59)). */
    const val RECORD_X: Float = 73.1f
    const val RECORD_Y: Float = 14.16f
    const val RECORD_R: Float = 17.58f

    /** How far "sight" and the record move to follow "Sleeve" on its line. */
    const val LINE_DX: Float = {dx:.2f}f
    const val LINE_DY: Float = {dy:.2f}f
}}
'''
open(out, 'w').write(kt)
print('line shift', round(dx, 2), round(dy, 2))
