"""Google Play's feature graphic, and the other designs tried for it.

All 1024 x 500, built from the logo's sources: the words are the tile's outlines (`tile.json`,
written by `make_tile.py`); the record is the brand sheet's drawing (`<symbol id="lp">` in
`design/brand.html`); the colours, other than white, are the sheet's light and dark colourways
(`.lt` and `.dk`, and its `--muted` for a tagline in grey); the tagline "Insights on what's
playing" is outlined here from Roboto Flex. So every SVG renders without a font.

  d2              The one on Google Play: on the label's red (`.lt`'s `--red`), "Sleeve" white over
                  "sight" in the logo's ink (a red "sight" would vanish on red), right-aligned, the
                  tagline right-aligned under them, and the record large beside them, its centre at
                  the height of the centre of "sight" (as the 8b mark places it), running off the
                  right and bottom edges. Written to `listing/graphics/feature-graphic.svg` and, for
                  Play, `feature-graphic.png`: 24-bit, no alpha (Play refuses alpha in a feature
                  graphic), without a time or date chunk, so a rerun reproduces it byte for byte.
  a2-mark-light   The 8b mark, centred, on the light ground; the tagline in grey under it, its left
  b2-mark-dark    edge at the left edge of "sight". And the same on the dark ground.
  c-sleeve-light  The text tile as a sleeve, the record halfway out of it (as in reveal R2), the
  c-sleeve-dark   tagline centred under them; light and dark.

The other designs are kept for the brand sheet, as `assets/logo/feature-graphic/<name>.svg`, SVG only.

`make_og_image.py`, the product page's link preview, imports this script's code (the tagline, the
record and the 8b mark's placement of it), and `make_mark.py`, the README's mark, its record and
placement, so after a change to that code run them again too.

Usage, from the repository root:

    python3 assets/logo/src/make_feature_graphic.py RobotoFlex.ttf               # d2, for Play
    python3 assets/logo/src/make_feature_graphic.py RobotoFlex.ttf --design all  # every design
    python3 assets/logo/src/make_feature_graphic.py RobotoFlex.ttf --design c-sleeve-dark

Needs fontTools and uharfbuzz (`pip install fonttools uharfbuzz`; written with fontTools 4.67.0 and
uharfbuzz 0.56.3), and for d2's PNG `rsvg-convert` (librsvg; 2.61.4) and ImageMagick 7 (`magick`;
7.1.2), both from Homebrew on a Mac. The font is the variable Roboto Flex from github.com/google/fonts,
`ofl/robotoflex/RobotoFlex[GRAD,XOPQ,XTRA,YOPQ,YTAS,YTDE,YTFI,YTLC,YTUC,opsz,slnt,wdth,wght].ttf`,
version 3.200 (SHA-256 9b523f7d82593df0107173849ebb8c817471a1df4b4fb2c3cbf40cfd810c8281; SIL Open
Font License 1.1, `assets/fonts/OFL.txt`); it is not kept here, since only the tagline's outlines
are. The script stops if the font's SHA-256 differs, since another version may outline the tagline
differently; to move to a new version on purpose, update `FONT_SHA256` and the version here.
Another version of the renderer may give a slightly different PNG.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from xml.etree import ElementTree
from xml.sax.saxutils import escape

import uharfbuzz as hb
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

W, H = 1024, 500
WHITE = '#ffffff'
TAG_TEXT = 'Insights on what’s playing'
TAG_AXES = {'wght': 400, 'wdth': 88, 'opsz': 36}
TAG_TRACK = -.01              # em, after each letter
RECORD_ELEMENTS = 7           # the elements of <symbol id="lp">, the core included
# The record in the 8b mark, in the words' units, as the sheet's `<use href="#lp" x y width height>`
# places it: (REC_X, REC_Y) is the top-left corner of the record's 16-unit box, REC_SIDE its side.
REC_X, REC_Y, REC_SIDE = 54.35, -4.59, 37.5
FONT_SHA256 = '9b523f7d82593df0107173849ebb8c817471a1df4b4fb2c3cbf40cfd810c8281'  # Roboto Flex 3.200
DESIGNS = ('d2', 'a2-mark-light', 'b2-mark-dark', 'c-sleeve-light', 'c-sleeve-dark')

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.normpath(os.path.join(here, '..', '..', '..'))
play_dir = os.path.join(root, 'listing', 'graphics')
designs_dir = os.path.join(root, 'assets', 'logo', 'feature-graphic')
num = lambda v: ('%.2f' % v).rstrip('0').rstrip('.')


def css_vars(text):
    return {k: v.strip() for k, v in re.findall(r'--([\w-]+):([^;}]+)', text)}


def brand_sheet():
    """From `design/brand.html`: the <symbol id="lp"> element, parsed as XML, as make_icon.py reads it,
    and each colourway's variables, name to value: `lt` and `dk`, each with the page's `--muted`
    of its scheme added as `muted`."""
    page = open(os.path.join(root, 'design', 'brand.html')).read()
    sym = re.search(r'<symbol\b[^>]*\bid=(["\'])lp\1.*?</symbol>', page, re.S)
    rules = {name: re.search(pattern, page, re.S | re.M) for name, pattern in (
        ('lt', r'^\.lt\{(.*?)\}'), ('dk', r'^\.dk\{(.*?)\}'), ('page', r'^:root\{(.*?)\}'),
        ('page-dark', r'^@media \(prefers-color-scheme: dark\)\{:root\{(.*?)\}'))}
    missing = ([] if sym else ['<symbol id="lp">']) + [
        f'the {name} rule' for name, m in rules.items() if not m]
    if missing:
        sys.exit('design/brand.html: no ' + ', '.join(missing))
    schemes = {'lt': css_vars(rules['lt'].group(1)), 'dk': css_vars(rules['dk'].group(1))}
    for scheme, rule, where in (('lt', 'page', ':root'), ('dk', 'page-dark', ':root in the dark media query')):
        muted = css_vars(rules[rule].group(1)).get('muted')
        if not muted:
            sys.exit(f'design/brand.html: {where} sets no --muted')
        schemes[scheme]['muted'] = muted
    try:
        symbol = ElementTree.fromstring(sym.group(0))
    except ElementTree.ParseError as e:
        sys.exit(f'design/brand.html: <symbol id="lp"> is not well-formed XML ({e}); the script reads it as XML')
    return symbol, schemes


def need(var, scheme, *names):
    missing = [name for name in names if not var.get(name)]
    if missing:
        sys.exit(f'design/brand.html: .{scheme} sets no ' + ', '.join('--' + name for name in missing))
    return [var[name] for name in names]


def record(symbol, var, scheme):
    """The brand sheet's record, its 16-unit box at 8..24, with a colourway's variables filled in:
    `--rim` as the fill, `--core` and `--groove` as each element's display. Each element is written
    back with its attributes in the sheet's order, so the drawing is the sheet's own."""
    elements = list(symbol)
    texts = [t.strip() for t in [symbol.text] + [x for el in elements for x in (el.text, el.tail)] if t and t.strip()]
    nested = next(((el, child) for el in elements for child in el), None)
    problems = [part for part in (
        symbol.attrib != {'id': 'lp', 'viewBox': '8 8 16 16'} and f'its attributes are {symbol.attrib}',
        len(elements) != RECORD_ELEMENTS and f'it holds {len(elements)} elements',
        texts and f'it holds text {texts[0][:40]!r}',
        nested and f'a <{nested[0].tag}> holds a <{nested[1].tag}>') if part]
    if problems:
        sys.exit(f'design/brand.html: <symbol id="lp" viewBox="8 8 16 16"> should hold {RECORD_ELEMENTS} empty '
                 f'elements and nothing else; ' + '; '.join(problems) + '; the script needs updating for it')
    out = []
    for el in elements:
        if '}' in el.tag or any('}' in name for name in el.attrib):
            sys.exit(f'design/brand.html: <symbol id="lp">: a namespaced name the script does not know on a '
                     f'<{el.tag}>: {dict(el.attrib)}; the script needs updating for it')
        style = el.attrib.get('style')
        prop = None
        if style is not None:
            m = re.fullmatch(r'(fill|display):var\(--([\w-]+)\)', style)
            if not m:
                sys.exit(f'design/brand.html: <symbol id="lp">: a style the script does not know on a '
                         f'<{el.tag}>: {style!r}; it fills in only fill and display from a variable')
            prop, var_name = m.groups()
            resolved, = need(var, scheme, var_name)
        # The style wins over a presentation attribute of the same property, as in the page, so that
        # attribute is dropped: the rim's `fill` comes from its style alone, and a `display` attribute
        # cannot hide what the style shows.
        attrs, shown = [], True
        for name, value in el.attrib.items():
            if name == 'style':
                if prop == 'fill':
                    attrs.append(('fill', resolved))
                shown = not (prop == 'display' and resolved == 'none')
            elif name != prop:
                attrs.append((name, value))
        if not shown:
            continue
        if any('var(' in value for _, value in attrs):
            sys.exit(f'design/brand.html: a variable the script does not fill in is left in the record, '
                     f'on a <{el.tag}>: {dict(attrs)}')
        out.append(f'<{el.tag}' + ''.join(f' {name}="{escape(value, {chr(34): "&quot;"})}"' for name, value in attrs)
                   + '/>')
    return ''.join(out)


class Tagline:
    """The tagline, outlined from Roboto Flex at any size."""

    def __init__(self, font_path):
        with open(font_path, 'rb') as f:
            digest = hashlib.sha256(f.read()).hexdigest()
        if digest != FONT_SHA256:
            sys.exit(f'{font_path}: SHA-256 {digest}, not that of Roboto Flex 3.200 ({FONT_SHA256}); '
                     'see the header for the font, or for moving to another version')
        self.hbfont = hb.Font(hb.Face(hb.Blob.from_file_path(font_path)))
        self.hbfont.set_variations(TAG_AXES)
        font = instantiateVariableFont(TTFont(font_path), TAG_AXES)
        self.glyphs, self.order, self.upm = font.getGlyphSet(), font.getGlyphOrder(), font['head'].unitsPerEm
        self.cap = font['OS/2'].sCapHeight / self.upm

    def outline(self, size):
        """At `size` px, pen start at 0, baseline at 0: (path, ink box, cap height in px)."""
        buf = hb.Buffer()
        buf.add_str(TAG_TEXT)
        buf.guess_segment_properties()
        hb.shape(self.hbfont, buf, {'kern': True, 'liga': True})
        k = size / self.upm
        pen, box, x = SVGPathPen(self.glyphs, ntos=num), BoundsPen(self.glyphs), 0
        for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
            t = (k, 0, 0, -k, (x + pos.x_offset) * k, -pos.y_offset * k)
            self.glyphs[self.order[info.codepoint]].draw(TransformPen(pen, t))
            self.glyphs[self.order[info.codepoint]].draw(TransformPen(box, t))
            x += pos.x_advance + TAG_TRACK * self.upm
        return pen.getCommands(), box.bounds, size * self.cap


def svg(ground, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
            f'<rect width="{W}" height="{H}" fill="{ground}"/>{body}</svg>\n')


def place_record(drawing, cx, cy, radius):
    k = radius / 7.5                                # the record's rim has radius 7.5 in its box
    return f'<g transform="translate({num(cx)} {num(cy)}) scale({k:.5f}) translate(-16 -16)">{drawing}</g>'


def sight_centre(tile):
    """The middle of the ink of "sight", top to bottom, in the words' units."""
    sight = [letter['ink'] for letter in tile['letters']['sight']]
    return (min(b[1] for b in sight) + max(b[3] for b in sight)) / 2


def design_d2(tile, symbol, schemes, tag):
    """On Play: the words and the tagline right-aligned, the record large beside "sight"."""
    words_h, tag_size, tag_gap = 270, 40, 40          # px; the gap from the words' ink to the tagline's caps
    radius, record_x, record_gap = 250, W - 150, 48   # the record; the gap from the words' right edge to it
    lt = schemes['lt']
    ground, sight_ink = need(lt, 'lt', 'red', 'ink')
    _, y0, x1, y1 = tile['ink']
    s = words_h / (y1 - y0)
    d, (_, _, tx1, _), cap = tag.outline(tag_size)
    top = (H - (words_h + tag_gap + cap)) / 2         # the words and the tagline, centred vertically
    right = record_x - radius - record_gap            # the words' and the tagline's right ink edge
    tx, ty = right - s * x1, top - s * y0
    return ground, svg(ground,
        place_record(record(symbol, lt, 'lt'), record_x, ty + s * sight_centre(tile), radius)
        + f'<g transform="translate({num(tx)} {num(ty)}) scale({s:.5f})">'
        f'<path fill="{WHITE}" d="{tile["sleeve"]}"/><path fill="{sight_ink}" d="{tile["sight"]}"/></g>'
        f'<path fill="{WHITE}" fill-opacity="0.9" '
        f'transform="translate({num(right - tx1)} {num(top + words_h + tag_gap + cap)})" d="{d}"/>')


def design_mark(tile, symbol, schemes, tag, scheme):
    """A2 and B2: the 8b mark (the words, the record beside "sight", as tall as it), centred; the
    tagline under it, its left ink edge at that of the s of "sight"."""
    width, tag_size, tag_gap = 540, 46, 44
    var = schemes[scheme]
    bg, ink, red, muted = need(var, scheme, 'bg', 'ink', 'red', 'muted')
    # The mark's ink runs from the words' left edge to the record's right edge: the box's centre plus
    # the rim's radius (7.5 of the box's 16 units), 90.68 to two places, as the sheet's 8b viewBox has it.
    rec_right = round(REC_X + REC_SIDE / 2 + 7.5 * REC_SIDE / 16, 2)
    x0, y0, _, y1 = tile['ink']
    s = width / (rec_right - x0)
    d, (dx0, _, _, _), cap = tag.outline(tag_size)
    height = s * (y1 - y0)
    top = (H - (height + tag_gap + cap)) / 2
    tx, ty = W / 2 - s * (x0 + rec_right) / 2, top - s * y0
    sight_left = tx + s * tile['letters']['sight'][0]['ink'][0]
    return bg, svg(bg,
        f'<g transform="translate({num(tx)} {num(ty)}) scale({s:.5f})">'
        f'<path fill="{ink}" d="{tile["sleeve"]}"/><path fill="{red}" d="{tile["sight"]}"/>'
        f'<g transform="translate({num(REC_X)} {num(REC_Y)}) scale({REC_SIDE / 16:.5f}) translate(-8 -8)">'
        f'{record(symbol, var, scheme)}</g></g>'
        f'<path fill="{muted}" transform="translate({num(sight_left - dx0)} {num(top + height + tag_gap + cap)})" '
        f'd="{d}"/>')


def design_sleeve(tile, symbol, schemes, tag, scheme):
    """C: the text tile (the sleeve) with the record halfway out of it to the right, as in reveal R2
    (the record 64 units in radius against the tile's 71.5 half side), its label clear of the tile;
    the tagline centred under them."""
    side, tag_size, tag_gap = 300, 44, 40
    var = schemes[scheme]
    bg, ink, red, muted, tile_bg, tile_edge = need(var, scheme, 'bg', 'ink', 'red', 'muted', 'tile-bg', 'tile-edge')
    s = side / 143
    radius = 64 * s
    out_by = 1.6 * radius                             # the record's centre from the tile's
    d, (dx0, _, dx1, _), cap = tag.outline(tag_size)
    top = (H - (side + tag_gap + cap)) / 2
    tile_cx = W / 2 - (side / 2 + out_by + radius) / 2 + side / 2
    cy = top + side / 2
    return bg, svg(bg,
        place_record(record(symbol, var, scheme), tile_cx + out_by, cy, radius)
        + f'<g transform="translate({num(tile_cx)} {num(cy)}) scale({s:.5f})">'
        f'<rect x="-71.5" y="-71.5" width="143" height="143" rx="31.5" fill="{tile_bg}" stroke="{tile_edge}"/>'
        f'<path fill="{ink}" d="{tile["sleeve"]}"/><path fill="{red}" d="{tile["sight"]}"/></g>'
        f'<path fill="{muted}" transform="translate({num(W / 2 - (dx0 + dx1) / 2)} {num(top + side + tag_gap + cap)})" '
        f'd="{d}"/>')


def build(name, tile, symbol, schemes, tag):
    if name == 'd2':
        return design_d2(tile, symbol, schemes, tag)
    scheme = 'lt' if name.endswith('-light') else 'dk'
    if name.startswith(('a2-', 'b2-')):
        return design_mark(tile, symbol, schemes, tag, scheme)
    return design_sleeve(tile, symbol, schemes, tag, scheme)


def write(built):
    """Writes the designs built, each (name, ground, SVG text). Every file is made first in one
    temporary directory, and they are moved into place only once all are complete, so a failed
    render leaves the files there as they were. d2 goes to `listing/graphics/`, with its PNG; the
    others to `assets/logo/feature-graphic/`."""
    with tempfile.TemporaryDirectory() as tmp:
        moves = []
        for name, ground, text in built:
            svg_tmp = os.path.join(tmp, name + '.svg')
            with open(svg_tmp, 'w') as f:
                f.write(text)
            if name == 'd2':
                png_tmp, rgba = os.path.join(tmp, name + '.png'), os.path.join(tmp, name + '-rgba.png')
                subprocess.run(['rsvg-convert', '-w', str(W), '-h', str(H), '-o', rgba, svg_tmp], check=True)
                subprocess.run(['magick', rgba, '-background', ground, '-alpha', 'remove', '-alpha', 'off',
                                '-strip', '-define', 'png:exclude-chunk=date,time', 'PNG24:' + png_tmp], check=True)
                moves += [(svg_tmp, os.path.join(play_dir, 'feature-graphic.svg')),
                          (png_tmp, os.path.join(play_dir, 'feature-graphic.png'))]
            else:
                moves.append((svg_tmp, os.path.join(designs_dir, name + '.svg')))
        for src, dst in moves:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.move(src, dst)
            print(os.path.relpath(dst, root))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('font', help='Roboto Flex 3.200, the variable font (see the header)')
    parser.add_argument('--design', choices=DESIGNS + ('all',), default='d2',
                        help='the design to build: d2 (the default) for Play, another, or all of them')
    args = parser.parse_args()
    tile = json.load(open(os.path.join(here, 'tile.json')))
    symbol, schemes = brand_sheet()
    tag = Tagline(args.font)
    names = DESIGNS if args.design == 'all' else (args.design,)
    missing = [f'{tool} (Homebrew: {pkg})' for tool, pkg in (('rsvg-convert', 'librsvg'), ('magick', 'imagemagick'))
               if not shutil.which(tool)]
    if 'd2' in names and missing:
        sys.exit("d2's PNG needs " + ' and '.join(missing) + '; nothing written')
    write([(name, *build(name, tile, symbol, schemes, tag)) for name in names])


if __name__ == '__main__':
    main()
