"""The product page's link preview image (`og:image`), and the other designs tried for it.

1200 x 630 px, the size most link previews crop to (Open Graph's 1.91:1), built from the logo's
sources as `make_feature_graphic.py` builds the feature graphic, and with its code: the words are
the tile's outlines (`tile.json`), the record is the brand sheet's drawing (`<symbol id="lp">` in
`design/brand.html`), the colours are the sheet's colourways, and the tagline "Insights on what's
playing" is outlined from Roboto Flex, so the SVG renders without a font.

Each design is the 8b mark (the words, the record beside "sight", as tall as it) with the tagline
under it, centred, and kept inside the middle 630 x 630 square, so a service that crops the image
to a square thumbnail, as some chat apps do for a compact preview, still shows the whole mark; the
words fill about half the height, so they read when the preview is drawn a few hundred pixels wide.

  red     The one the page uses: on the label's red (`.lt`'s `--red`), "Sleeve" white over "sight"
          in the logo's ink (a red "sight" would vanish on red), the tagline white, as the Play
          feature graphic sets them. A vivid ground stands apart from a chat's light and dark
          backgrounds alike, where white merges with a light one and black with a dark one.
          Written to `assets/og-image.svg` and `assets/og-image.png`: 24-bit, no alpha (some
          services draw transparency as black), without a time or date chunk, so a rerun
          reproduces it byte for byte.
  light   The mark in the light colourway on its white ground, the tagline in grey.
  dark    The mark in the dark colourway on its black ground, the tagline in grey.

Usage, from the repository root:

    python3 assets/logo/src/make_og_image.py RobotoFlex.ttf                            # red, in place
    python3 assets/logo/src/make_og_image.py RobotoFlex.ttf --design all --out <dir>   # every design

`--out` writes `<design>.svg` and `<design>.png` under another directory, leaving the committed
files as they are; the designs other than red are written only there. The font, and the Python
packages (fontTools and uharfbuzz), are those of `make_feature_graphic.py`; its header says where
the font comes from, and the script stops on another version of it. The PNG needs `rsvg-convert`
(librsvg; 2.61.4) and ImageMagick 7 (`magick`; 7.1.2), both from Homebrew on a Mac. Another version
of the renderer may give a slightly different PNG.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # its neighbour, under `python -I` too
from make_feature_graphic import (  # noqa: E402
    REC_SIDE, REC_X, REC_Y, WHITE, Tagline, brand_sheet, here, need, num, record, root)

W, H = 1200, 630
DESIGNS = ('red', 'light', 'dark')
out_dir = os.path.join(root, 'assets')


def svg(ground, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
            f'<rect width="{W}" height="{H}" fill="{ground}"/>{body}</svg>\n')


def colours(name, schemes):
    """(ground, "Sleeve", "sight", tagline fill, tagline opacity, the record's colourway)."""
    if name == 'red':
        lt = schemes['lt']
        red, ink = need(lt, 'lt', 'red', 'ink')
        return red, WHITE, ink, WHITE, 0.9, 'lt'
    scheme = 'lt' if name == 'light' else 'dk'
    bg, ink, red, muted = need(schemes[scheme], scheme, 'bg', 'ink', 'red', 'muted')
    return bg, ink, red, muted, 1, scheme


def build(name, tile, symbol, schemes, tag):
    """The 8b mark, centred, the tagline centred under it."""
    width, tag_size, tag_gap = 556, 50, 40          # px; the gap from the words' ink to the tagline's caps
    ground, sleeve, sight, tag_fill, tag_opacity, scheme = colours(name, schemes)
    # The mark's ink runs from the words' left edge to the record's right edge, as in
    # `make_feature_graphic.design_mark`: the record's box centre plus the rim's radius.
    rec_right = round(REC_X + REC_SIDE / 2 + 7.5 * REC_SIDE / 16, 2)
    x0, y0, _, y1 = tile['ink']
    s = width / (rec_right - x0)
    d, (dx0, _, dx1, _), cap = tag.outline(tag_size)
    height = s * (y1 - y0)
    top = (H - (height + tag_gap + cap)) / 2
    tx, ty = W / 2 - s * (x0 + rec_right) / 2, top - s * y0
    opacity = '' if tag_opacity == 1 else f' fill-opacity="{num(tag_opacity)}"'
    return ground, svg(ground,
        f'<g transform="translate({num(tx)} {num(ty)}) scale({s:.5f})">'
        f'<path fill="{sleeve}" d="{tile["sleeve"]}"/><path fill="{sight}" d="{tile["sight"]}"/>'
        f'<g transform="translate({num(REC_X)} {num(REC_Y)}) scale({REC_SIDE / 16:.5f}) translate(-8 -8)">'
        f'{record(symbol, schemes[scheme], scheme)}</g></g>'
        f'<path fill="{tag_fill}"{opacity} '
        f'transform="translate({num(W / 2 - (dx0 + dx1) / 2)} {num(top + height + tag_gap + cap)})" d="{d}"/>')


def write(built, out):
    """Writes each design built, (name, ground, SVG text), as SVG and PNG. Every file is made first in
    one temporary directory and moved into place only once all are complete, so a failed render
    leaves the files there as they were."""
    with tempfile.TemporaryDirectory() as tmp:
        moves = []
        for name, ground, text in built:
            svg_tmp = os.path.join(tmp, name + '.svg')
            png_tmp, rgba = os.path.join(tmp, name + '.png'), os.path.join(tmp, name + '-rgba.png')
            with open(svg_tmp, 'w') as f:
                f.write(text)
            subprocess.run(['rsvg-convert', '-w', str(W), '-h', str(H), '-o', rgba, svg_tmp], check=True)
            subprocess.run(['magick', rgba, '-background', ground, '-alpha', 'remove', '-alpha', 'off',
                            '-strip', '-define', 'png:exclude-chunk=date,time', 'PNG24:' + png_tmp], check=True)
            stem = os.path.join(out, name) if out else os.path.join(out_dir, 'og-image')
            moves += [(svg_tmp, stem + '.svg'), (png_tmp, stem + '.png')]
        for src, dst in moves:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.move(src, dst)
            print(os.path.relpath(dst) if out else os.path.relpath(dst, root))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('font', help="Roboto Flex 3.200, the variable font (see make_feature_graphic.py's header)")
    parser.add_argument('--design', choices=DESIGNS + ('all',), default='red',
                        help='the design to build: red (the default), the one the page uses, another, or all')
    parser.add_argument('--out', help='a directory to write <design>.svg and .png to, instead of assets/og-image.*')
    args = parser.parse_args()
    names = DESIGNS if args.design == 'all' else (args.design,)
    if names != ('red',) and not args.out:
        sys.exit('only red is written in place, as assets/og-image.*; give --out for the others')
    missing = [f'{tool} (Homebrew: {pkg})' for tool, pkg in (('rsvg-convert', 'librsvg'), ('magick', 'imagemagick'))
               if not shutil.which(tool)]
    if missing:
        sys.exit('The PNG needs ' + ' and '.join(missing) + '; nothing written')
    tile = json.load(open(os.path.join(here, 'tile.json')))
    symbol, schemes = brand_sheet()
    tag = Tagline(args.font)
    write([(name, *build(name, tile, symbol, schemes, tag)) for name in names], args.out)


if __name__ == '__main__':
    main()
