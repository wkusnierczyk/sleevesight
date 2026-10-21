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
1.1, `assets/fonts/OFL.txt`); it is not kept here, since only the tagline's outlines are. The script
stops if the font's SHA-256 differs, since another version may outline the tagline differently; to
move to a new version on purpose, update `FONT_SHA256` and the version here. Another version of the
renderer may give a slightly different PNG.

The colours, other than white, come from the brand sheet's light colourway (`.lt` in
`design/brand.html`): the ground is its `--red`, the label's red, and "sight" its `--ink`.
"""
import hashlib
import json
import os
import re
import shutil
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
SLEEVE = '#ffffff'            # the ground and "sight" are the brand sheet's (`colourway`)
TAGLINE, TAGLINE_OPACITY = '#ffffff', .9
TAG_TEXT = 'Insights on what’s playing'
TAG_SIZE, TAG_GAP = 40, 40    # px; the gap is from the words' ink to the tagline's cap height
TAG_AXES = {'wght': 400, 'wdth': 88, 'opsz': 36}
TAG_TRACK = -.01              # em, after each letter
WORDS_H = 270                 # the words' ink height, px
RECORD_R = 250                # the record's radius, px
RECORD_X = W - 150            # its centre's x
RECORD_GAP = 48               # from the words' right ink edge to the record's left edge
RECORD_ELEMENTS = 7           # the elements of <symbol id="lp">, the core included
FONT_SHA256 = '9b523f7d82593df0107173849ebb8c817471a1df4b4fb2c3cbf40cfd810c8281'  # Roboto Flex 3.200

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.normpath(os.path.join(here, '..', '..', '..'))
out_dir = os.path.join(root, 'listing', 'graphics')
num = lambda v: ('%.2f' % v).rstrip('0').rstrip('.')


def brand_sheet():
    """From `design/brand.html`: the body of <symbol id="lp">, and the light colourway's variables
    (the `.lt` rule), name to value."""
    page = open(os.path.join(root, 'design', 'brand.html')).read()
    sym = re.search(r'<symbol id="lp" viewBox="8 8 16 16">(.*?)</symbol>', page, re.S)
    lt = re.search(r'^\.lt\{(.*?)\}', page, re.S | re.M)
    if not sym or not lt:
        sys.exit('design/brand.html: no <symbol id="lp"> or no .lt rule')
    return sym.group(1), {k: v.strip() for k, v in re.findall(r'--([\w-]+):([^;}]+)', lt.group(1))}


def colourway(var):
    """The ground (`--red`) and the ink of "sight" (`--ink`)."""
    missing = [name for name in ('red', 'ink') if name not in var]
    if missing:
        sys.exit('design/brand.html: .lt sets no ' + ', '.join('--' + name for name in missing))
    return var['red'], var['ink']


def record(body, var):
    """The brand sheet's record, its 16-unit box at 8..24, with the light colourway's variables
    filled in: `--rim` as the fill, `--core` and `--groove` as each element's display."""
    elements = re.findall(r'<[^>]+/>', body)
    other = re.sub(r'<[^>]+/>', '', body).strip()
    if len(elements) != RECORD_ELEMENTS or other:
        sys.exit(f'design/brand.html: <symbol id="lp"> should hold {RECORD_ELEMENTS} self-closing elements '
                 f'and nothing else; it holds {len(elements)}' + (f', and also {other[:40]!r}' if other else '')
                 + '; the script needs updating for it')
    out = []
    for el in elements:
        style = re.search(r' style="(\w+):var\(--([\w-]+)\)"', el)
        if style:
            prop, name = style.groups()
            if name not in var:
                sys.exit(f'design/brand.html: .lt sets no --{name}')
            if prop == 'display' and var[name] == 'none':
                continue
            el = el.replace(style.group(0), f' fill="{var[name]}"' if prop == 'fill' else '')
        out.append(el)
    return ''.join(out)


def tagline(font_path):
    """The tagline outlined, its pen start at 0 and baseline at 0: (path, ink box, cap height in px)."""
    with open(font_path, 'rb') as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    if digest != FONT_SHA256:
        sys.exit(f'{font_path}: SHA-256 {digest}, not that of Roboto Flex 3.200 ({FONT_SHA256}); '
                 'see the header for the font, or for moving to another version')
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
    return pen.getCommands(), box.bounds, TAG_SIZE * font['OS/2'].sCapHeight / upm


def main(font_path):
    tile = json.load(open(os.path.join(here, 'tile.json')))
    x0, y0, x1, y1 = tile['ink']
    s = WORDS_H / (y1 - y0)
    body, var = brand_sheet()
    ground, sight_ink = colourway(var)
    d, (_, _, tx1, _), cap = tagline(font_path)
    top = (H - (WORDS_H + TAG_GAP + cap)) / 2      # the words and the tagline, centred vertically
    right = RECORD_X - RECORD_R - RECORD_GAP        # the words' and the tagline's right ink edge
    tx, ty = right - s * x1, top - s * y0
    # The centre of "sight": the middle of its letters' ink, top to bottom.
    sight = [letter['ink'] for letter in tile['letters']['sight']]
    record_y = ty + s * (min(b[1] for b in sight) + max(b[3] for b in sight)) / 2
    k = RECORD_R / 7.5                              # the record's rim has radius 7.5 in its box
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
           f'<rect width="{W}" height="{H}" fill="{ground}"/>'
           f'<g transform="translate({num(RECORD_X)} {num(record_y)}) scale({k:.5f}) translate(-16 -16)">'
           f'{record(body, var)}</g>'
           f'<g transform="translate({num(tx)} {num(ty)}) scale({s:.5f})">'
           f'<path fill="{SLEEVE}" d="{tile["sleeve"]}"/><path fill="{sight_ink}" d="{tile["sight"]}"/></g>'
           f'<path fill="{TAGLINE}" fill-opacity="{TAGLINE_OPACITY}" '
           f'transform="translate({num(right - tx1)} {num(top + WORDS_H + TAG_GAP + cap)})" d="{d}"/>'
           '</svg>\n')
    # Both are made in a temporary directory and moved into place only once both exist, so a failed
    # render leaves the pair in `listing/graphics/` as it was.
    os.makedirs(out_dir, exist_ok=True)
    names = ('feature-graphic.svg', 'feature-graphic.png')
    with tempfile.TemporaryDirectory() as tmp:
        svg_tmp, png_tmp = (os.path.join(tmp, name) for name in names)
        rgba = os.path.join(tmp, 'rgba.png')
        with open(svg_tmp, 'w') as f:
            f.write(svg)
        subprocess.run(['rsvg-convert', '-w', str(W), '-h', str(H), '-o', rgba, svg_tmp], check=True)
        subprocess.run(['magick', rgba, '-background', ground, '-alpha', 'remove', '-alpha', 'off', '-strip',
                        '-define', 'png:exclude-chunk=date,time', 'PNG24:' + png_tmp], check=True)
        for name in names:
            shutil.move(os.path.join(tmp, name), os.path.join(out_dir, name))
            print(os.path.relpath(os.path.join(out_dir, name), root))


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
