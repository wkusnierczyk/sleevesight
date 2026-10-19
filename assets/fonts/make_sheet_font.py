"""Cut Roboto Flex down to what the brand sheet (`design/brand.html`) uses, as `RobotoFlex-sheet.woff`.

The sheet sets its text in Roboto Flex and, like the product page, loads nothing from another site,
so it carries its own copy of the font. The full variable font is about 1.7 MB; this copy keeps
  - the axes the sheet varies: weight 400-700, width 87.5-100% (the marks are at 88%), and optical
    size over its whole range, which the browser sets from the font size; every other axis is fixed
    at its default;
  - the characters in `design/brand.html` (every one in the file, markup and script included, so
    text the script writes is covered), plus printable ASCII.
Text added to the sheet in a character outside that set falls back to the next font in the stack,
so after such an edit, run this again.

Usage, from the repository root, with fontTools installed (`pip install fonttools`):
    python3 assets/fonts/make_sheet_font.py RobotoFlex.ttf
where RobotoFlex.ttf is the variable font from https://github.com/googlefonts/roboto-flex
(SIL Open Font License 1.1, `assets/fonts/OFL.txt`, which declares no Reserved Font Name, so the
cut-down copy keeps the name).
"""
import html
import os
import sys
import tempfile

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

PAGE = 'design/brand.html'
OUT = 'assets/fonts/RobotoFlex-sheet.woff'
AXES = {'wght': (400, 700), 'wdth': (87.5, 100), 'opsz': (8, 144)}

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
    text = html.unescape(open(PAGE, encoding='utf-8').read())
    chars = {ord(c) for c in text if ord(c) >= 0x20} | set(range(0x20, 0x7f))
    opts = subset.Options()
    opts.layout_features = ['*']
    opts.name_IDs = ['*']
    opts.notdef_outline = True
    opts.flavor = 'woff'
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=sorted(chars))
    sub.subset(font)
    subset.save_font(font, OUT, opts)
print(f'{OUT}: {os.path.getsize(OUT)} bytes, {len(chars)} characters')
