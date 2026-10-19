"""Cut Roboto Flex down to what the product page (`index.html`) and its alternatives
(`design/landing-tile.html`, `design/landing-record.html`) use, as `RobotoFlex-page.woff2`.

The pages set their text in Roboto Flex and load nothing from another site, so they carry their own
copy of the font, as the brand sheet does (`make_sheet_font.py`). Without it, the page took whatever
Roboto the visitor had installed: on a machine with only Roboto Bold, every weight rendered bold.
The full variable font is about 1.7 MB; this copy keeps
  - the axes the pages vary: weight 400-700, width 88-100% (the headings are at 88-92%), and optical
    size over its whole range, which the browser sets from the font size; every other axis is fixed
    at its default, slant included (italic text, `<i>` and `.chat .by`, is slanted by the browser);
  - the characters in the pages (every one in the files, markup and script included, so text the
    scripts write is covered), plus printable ASCII, Latin-1 and the common punctuation (dashes,
    curly quotes, the ellipsis), so ordinary edits need no rebuild.
Text added to the pages in a character outside that set falls back to the next font in the stack,
so after such an edit, run this again. A character Roboto Flex itself lacks, such as the check mark
✓, falls back all the same; the script names any the pages use.

Usage, from the repository root, with fontTools and Brotli installed (`pip install fonttools brotli`):
    python3 assets/fonts/make_page_font.py RobotoFlex.ttf
where RobotoFlex.ttf is the variable font from github.com/google/fonts (ofl/robotoflex; version
3.200 when this was written). Its licence, the SIL Open Font License 1.1, is `assets/fonts/OFL.txt`.
"""
import html
import os
import sys
import tempfile

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

PAGES = ['index.html', 'design/landing-tile.html', 'design/landing-record.html']
OUT = 'assets/fonts/RobotoFlex-page.woff2'
AXES = {'wght': (400, 700), 'wdth': (88, 100), 'opsz': (8, 144)}
EXTRA = set(range(0x20, 0x7f)) | set(range(0xa0, 0x100)) | set(range(0x2010, 0x2027))

if len(sys.argv) != 2:
    sys.exit(__doc__)
font = TTFont(sys.argv[1])
font = instancer.instantiateVariableFont(
    font, {a.axisTag: AXES.get(a.axisTag, a.defaultValue) for a in font['fvar'].axes})
# The subsetter fails on an instanced font held in memory, so it goes through a file.
with tempfile.TemporaryDirectory() as tmp:
    path = os.path.join(tmp, 'instance.ttf')
    font.save(path)
    font = TTFont(path)
    text = ''.join(html.unescape(open(p, encoding='utf-8').read()) for p in PAGES)
    used = {ord(c) for c in text if ord(c) >= 0x20}
    chars = used | EXTRA
    opts = subset.Options()
    opts.layout_features = ['*']
    opts.name_IDs = ['*']
    opts.notdef_outline = True
    opts.flavor = 'woff2'
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=sorted(chars))
    missing = sorted(used - set(font.getBestCmap()))
    sub.subset(font)
    subset.save_font(font, OUT, opts)
print(f'{OUT}: {os.path.getsize(OUT)} bytes, {len(chars)} characters')
if missing:
    print('not in Roboto Flex, so drawn by a fallback font: ' + ' '.join(f'{chr(c)} U+{c:04X}' for c in missing))
