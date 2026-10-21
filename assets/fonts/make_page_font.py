"""Cut the product page's fonts (`index.html`) down to what it uses, as `Roboto*-page.woff2`.

The page sets its titles, labels and the app's text (the hero phone and the example boxes) in Roboto
Flex, its own running text in Roboto Slab, and the citation chips in Roboto Mono. It loads nothing
from another site, so it carries its own copy of each, as the brand sheet does (`make_sheet_font.py`).
Without them, the page took whatever Roboto the visitor had installed: on a machine with only Roboto
Bold, every weight rendered bold. Each copy keeps
  - the axes the page varies (see `FONTS`), every other axis fixed at its default. Flex: weight
    400-700, width 88-100% (the headings are at 88-92%), and optical size over its whole range, which
    the browser sets from the font size; its slant is fixed too, so italic text (`<i>` and `.chat .by`)
    is slanted by the browser. Slab: weight 350-700 (the text is at 350, bold at 700). Mono: the one
    weight the chips use, 500;
  - the characters in the page (every one in the file, markup and script included, so text the
    scripts write is covered), plus printable ASCII, Latin-1 and the common punctuation (dashes,
    curly quotes, the ellipsis), so ordinary edits need no rebuild. Mono, used only for the chips'
    source letters and numbers, keeps printable ASCII alone, and the chips' labels (the text of each
    `class="cite …"` element) are checked against it.
Text added to the page in a character outside that set falls back to the next font in the stack,
so after such an edit, run this again for Flex and Slab. A character a font itself lacks, such as the
check mark ✓, falls back all the same; the script names any the page uses.

Usage, from the repository root, with fontTools and Brotli installed (`pip install fonttools brotli`):
    python3 assets/fonts/make_page_font.py flex RobotoFlex.ttf
    python3 assets/fonts/make_page_font.py slab RobotoSlab.ttf
    python3 assets/fonts/make_page_font.py mono RobotoMono.ttf
where each .ttf is the variable font from github.com/google/fonts: ofl/robotoflex (version 3.200 when
this was written), apache/robotoslab and ofl/robotomono. Their licences are `assets/fonts/OFL.txt`
(Roboto Flex, SIL Open Font License 1.1), `LICENSE-RobotoSlab.txt` (Apache License 2.0) and
`OFL-RobotoMono.txt` (SIL Open Font License 1.1).
"""
import html
import os
import re
import sys
import tempfile

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

PAGES = ['index.html']
ASCII = set(range(0x20, 0x7f))
EXTRA = ASCII | set(range(0xa0, 0x100)) | set(range(0x2010, 0x2027))
FONTS = {
    'flex': ('assets/fonts/RobotoFlex-page.woff2', {'wght': (400, 700), 'wdth': (88, 100), 'opsz': (8, 144)}, 'page'),
    'slab': ('assets/fonts/RobotoSlab-page.woff2', {'wght': (350, 700)}, 'page'),
    'mono': ('assets/fonts/RobotoMono-page.woff2', {'wght': 500}, 'chips'),
}
CHIP = re.compile(r'class="cite[ "][^>]*>([^<]*)<')

if len(sys.argv) != 3 or sys.argv[1] not in FONTS:
    sys.exit(__doc__)
out, axes, scope = FONTS[sys.argv[1]]
font = TTFont(sys.argv[2])
font = instancer.instantiateVariableFont(
    font, {a.axisTag: axes.get(a.axisTag, a.defaultValue) for a in font['fvar'].axes})
# The subsetter fails on an instanced font held in memory, so it goes through a file.
with tempfile.TemporaryDirectory() as tmp:
    path = os.path.join(tmp, 'instance.ttf')
    font.save(path)
    font = TTFont(path)
    pages = [open(p, encoding='utf-8').read() for p in PAGES]
    if scope == 'page':
        text = ''.join(html.unescape(p) for p in pages)
        used = {ord(c) for c in text if ord(c) >= 0x20}
        chars = used | EXTRA
    else:
        # The chips' labels are checked, not added: the copy keeps ASCII only.
        labels = ''.join(html.unescape(m) for p in pages for m in CHIP.findall(p))
        used = {ord(c) for c in labels if ord(c) >= 0x20}
        chars = ASCII
    opts = subset.Options()
    opts.layout_features = ['*']
    opts.name_IDs = ['*']
    opts.notdef_outline = True
    opts.flavor = 'woff2'
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=sorted(chars))
    # Missing from the copy: a character the font lacks, or (for the chips) one outside what it keeps.
    missing = sorted(used - (set(font.getBestCmap()) & chars))
    sub.subset(font)
    subset.save_font(font, out, opts)
print(f'{out}: {os.path.getsize(out)} bytes, {len(chars)} characters')
if missing:
    if scope == 'chips':
        where = 'outside the ASCII the Mono copy keeps (widen `chars` in the chips branch above)'
    else:
        where = 'not in the font'
    print(f'{where}, so drawn by a fallback font: ' + ' '.join(f'{chr(c)} U+{c:04X}' for c in missing))
