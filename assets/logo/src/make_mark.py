"""The stacked mark on its own, for the README's header: `mark-light.svg` and `mark-dark.svg`.

The 8b mark (the words, the record beside "sight", as tall as it), as the feature graphic's a2 and
b2 draw it and with `make_feature_graphic.py`'s code: the words are the tile's outlines
(`tile.json`), the record is the brand sheet's drawing (`<symbol id="lp">` in `design/brand.html`),
in the sheet's light and dark colourways. No ground and no margin: the viewBox is the mark's ink,
so the page it sits on gives the background and the spacing. Needs no font.

Usage, from the repository root:

    python3 assets/logo/src/make_mark.py

The Python packages are those of `make_feature_graphic.py` (fontTools and uharfbuzz), which it
imports; nothing here uses them.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # its neighbour, under `python -I` too
from make_feature_graphic import (  # noqa: E402
    REC_SIDE, REC_X, REC_Y, brand_sheet, here, need, num, record, root)

SCHEMES = {'light': 'lt', 'dark': 'dk'}
out_dir = os.path.dirname(here)


def build(tile, symbol, schemes, scheme):
    ink, red = need(schemes[scheme], scheme, 'ink', 'red')
    # The record's rim: the centre of its 16-unit box, radius 7.5 of those units.
    cx, cy, r = REC_X + REC_SIDE / 2, REC_Y + REC_SIDE / 2, 7.5 * REC_SIDE / 16
    x0, y0, x1, y1 = tile['ink']
    x0, y0, x1, y1 = min(x0, cx - r), min(y0, cy - r), max(x1, cx + r), max(y1, cy + r)
    w, h = x1 - x0, y1 - y0
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{num(x0)} {num(y0)} {num(w)} {num(h)}" '
            f'width="{num(w)}" height="{num(h)}">'
            f'<path fill="{ink}" d="{tile["sleeve"]}"/><path fill="{red}" d="{tile["sight"]}"/>'
            f'<g transform="translate({num(REC_X)} {num(REC_Y)}) scale({REC_SIDE / 16:.5f}) translate(-8 -8)">'
            f'{record(symbol, schemes[scheme], scheme)}</g></svg>\n')


def main():
    tile = json.load(open(os.path.join(here, 'tile.json')))
    symbol, schemes = brand_sheet()
    for name, scheme in SCHEMES.items():
        path = os.path.join(out_dir, f'mark-{name}.svg')
        with open(path, 'w') as f:
            f.write(build(tile, symbol, schemes, scheme))
        print(os.path.relpath(path, root))


if __name__ == '__main__':
    main()
