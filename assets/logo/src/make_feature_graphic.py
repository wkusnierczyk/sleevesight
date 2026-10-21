"""Google Play's feature graphic: `listing/graphics/feature-graphic.svg` and `feature-graphic.png`.

1024 x 500, on the label's red: "Sleeve" over "sight", right-aligned, as the tile draws them, "Sleeve"
white and "sight" in the logo's ink (the red "sight" would vanish on red), the tagline "Insights on
what's playing" under them, right-aligned with them, and the record large beside them, its centre at
the height of the centre of "sight" (where the 8b mark centres it), bleeding off the right and bottom
edges. The words are the tile's outlines (`tile.json`, written by `make_tile.py`); the record is the
brand sheet's drawing (`<symbol id="lp">` in `design/brand.html`, in its light colourway, `.lt`);
the tagline is outlined here from Roboto Flex, so the SVG needs no font to render.

The PNG is what Play takes: 24-bit, no alpha (Play refuses alpha in a feature graphic). It is
rendered from the SVG and written without a time or date chunk, so a rerun with the same tools
reproduces it byte for byte.

Usage, from the repository root:

    python3 assets/logo/src/make_feature_graphic.py RobotoFlex.ttf

Needs fontTools and uharfbuzz (`pip install fonttools uharfbuzz`; written with fontTools 4.67.0 and
uharfbuzz 0.56.3), `rsvg-convert` (librsvg; 2.61.4) and ImageMagick 7 (`magick`; 7.1.2), both from
Homebrew on a Mac. The font is the variable Roboto Flex from github.com/google/fonts, `ofl/robotoflex/
RobotoFlex[GRAD,XOPQ,XTRA,YOPQ,YTAS,YTDE,YTFI,YTLC,YTUC,opsz,slnt,wdth,wght].ttf`, version 3.200
(SHA-256 9b523f7d82593df0107173849ebb8c817471a1df4b4fb2c3cbf40cfd810c8281; SIL Open Font License
1.1, `assets/fonts/OFL.txt`); it is not kept here, since only the tagline's outlines are. Another
version of the font, or of the renderer, may give a slightly different file.
"""
import json
import os
import re
import subprocess
import sys
import tempfile

import uharfbuzz as hb
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

W, H = 1024, 500
GROUND = '#cf222e'            # the label's red
SLEEVE, SIGHT = '#ffffff', '#141a22'
TAGLINE, TAGLINE_OPACITY = '#ffffff', .9
TAG_TEXT = 'Insights on what’s playing'
TAG_SIZE, TAG_GAP = 40, 40    # px; the gap is from the words' ink to the tagline's cap height
TAG_AXES = {'wght': 400, 'wdth': 88, 'opsz': 36}
TAG_TRACK = -.01              # em, after each letter
CAP = .711                    # Roboto's cap height, in em
WORDS_H = 270                 # the words' ink height, px
RECORD_R = 250                # the record's radius, px
RECORD_X = W - 150            # its centre's x
RECORD_GAP = 48               # from the words' right ink edge to the record's left edge

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.normpath(os.path.join(here, '..', '..', '..'))
out_dir = os.path.join(root, 'listing', 'graphics')
num = lambda v: ('%.2f' % v).rstrip('0').rstrip('.')


def record():
    """The brand sheet's record, its 16-unit box at 8..24, with the light colourway's variables
    filled in: `--rim` as the fill, `--core` and `--groove` as each element's display."""
    page = open(os.path.join(root, 'design', 'brand.html')).read()
    sym = re.search(r'<symbol id="lp" viewBox="8 8 16 16">(.*?)</symbol>', page, re.S)
    lt = re.search(r'^\.lt\{(.*?)\}', page, re.S | re.M)
    if not sym or not lt:
        sys.exit('design/brand.html: no <symbol id="lp"> or no .lt rule')
    var = dict(re.findall(r'--([\w-]+):([^;}]+)', lt.group(1)))
    out = []
    for el in re.findall(r'<[^>]+/>', sym.group(1)):
        style = re.search(r' style="(\w+):var\(--([\w-]+)\)"', el)
        if style:
            prop, name = style.groups()
            if name not in var:
                sys.exit(f'design/brand.html: .lt sets no --{name}')
            if prop == 'display' and var[name].strip() == 'none':
                continue
            el = el.replace(style.group(0), f' fill="{var[name].strip()}"' if prop == 'fill' else '')
        out.append(el)
    if len(out) < 4:
        sys.exit(f'design/brand.html: the record has {len(out)} elements, expected more')
    return ''.join(out)


def tagline(font_path):
    """The tagline outlined, its pen start at 0 and baseline at 0: (path, ink box)."""
    hbfont = hb.Font(hb.Face(hb.Blob.from_file_path(font_path)))
    hbfont.set_variations(TAG_AXES)
    font = instantiateVariableFont(TTFont(font_path), TAG_AXES)
    glyphs, order, upm = font.getGlyphSet(), font.getGlyphOrder(), font['head'].unitsPerEm
    buf = hb.Buffer()
    buf.add_str(TAG_TEXT)
    buf.guess_segment_properties()
    hb.shape(hbfont, buf, {'kern': True, 'liga': True})
    k = TAG_SIZE / upm
    pen, box, x = SVGPathPen(glyphs, ntos=num), BoundsPen(glyphs), 0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        t = (k, 0, 0, -k, (x + pos.x_offset) * k, -pos.y_offset * k)
        glyphs[order[info.codepoint]].draw(TransformPen(pen, t))
        glyphs[order[info.codepoint]].draw(TransformPen(box, t))
        x += pos.x_advance + TAG_TRACK * upm
    return pen.getCommands(), box.bounds


def main(font_path):
    tile = json.load(open(os.path.join(here, 'tile.json')))
    x0, y0, x1, y1 = tile['ink']
    s = WORDS_H / (y1 - y0)
    cap = TAG_SIZE * CAP
    top = (H - (WORDS_H + TAG_GAP + cap)) / 2      # the words and the tagline, centred vertically
    right = RECORD_X - RECORD_R - RECORD_GAP        # the words' and the tagline's right ink edge
    tx, ty = right - s * x1, top - s * y0
    # The centre of "sight": the middle of its letters' ink, top to bottom.
    sight = [letter['ink'] for letter in tile['letters']['sight']]
    record_y = ty + s * (min(b[1] for b in sight) + max(b[3] for b in sight)) / 2
    d, (tx0, _, tx1, _) = tagline(font_path)
    k = RECORD_R / 7.5                              # the record's rim has radius 7.5 in its box
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
           f'<rect width="{W}" height="{H}" fill="{GROUND}"/>'
           f'<g transform="translate({num(RECORD_X)} {num(record_y)}) scale({k:.5f}) translate(-16 -16)">'
           f'{record()}</g>'
           f'<g transform="translate({num(tx)} {num(ty)}) scale({s:.5f})">'
           f'<path fill="{SLEEVE}" d="{tile["sleeve"]}"/><path fill="{SIGHT}" d="{tile["sight"]}"/></g>'
           f'<path fill="{TAGLINE}" fill-opacity="{TAGLINE_OPACITY}" '
           f'transform="translate({num(right - tx1)} {num(top + WORDS_H + TAG_GAP + cap)})" d="{d}"/>'
           '</svg>\n')
    os.makedirs(out_dir, exist_ok=True)
    svg_path, png_path = (os.path.join(out_dir, 'feature-graphic.' + ext) for ext in ('svg', 'png'))
    open(svg_path, 'w').write(svg)
    with tempfile.TemporaryDirectory() as tmp:
        rgba = os.path.join(tmp, 'rgba.png')
        subprocess.run(['rsvg-convert', '-w', str(W), '-h', str(H), '-o', rgba, svg_path], check=True)
        subprocess.run(['magick', rgba, '-background', GROUND, '-alpha', 'remove', '-alpha', 'off', '-strip',
                        '-define', 'png:exclude-chunk=date,time', 'PNG24:' + png_path], check=True)
    print(os.path.relpath(svg_path, root), os.path.relpath(png_path, root))


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
