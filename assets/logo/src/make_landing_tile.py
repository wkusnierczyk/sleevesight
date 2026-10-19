"""design/landing-tile.html: the product page with the text tile as its logo and intro, for testers to
compare with the eye. Built from index.html and src/tile.json (make_tile.py); rerun it after any
change to index.html, since nothing rebuilds it. It fails if a part of index.html it replaces has
changed, or if the page it makes still holds anything of the eye's or anything that would not
resolve from design/. From the repository's root:

    python3 assets/logo/src/make_landing_tile.py

`--seek` adds a hook for checking frames: `?t=<ms>` freezes the intro at that moment.
"""
import json, os, re, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..')
t = json.load(open(os.path.join(ROOT, 'assets', 'logo', 'src', 'tile.json')))
h = open(os.path.join(ROOT, 'index.html')).read()

def sub(old, new, count=1, regex=False):
    """Replaces `old` in the page, which must occur `count` times."""
    global h
    n = len(re.findall(old, h, re.S)) if regex else h.count(old)
    if n != count:
        sys.exit('expected %d of %r in index.html, found %d' % (count, old[:70], n))
    h = re.sub(old, new, h, flags=re.S) if regex else h.replace(old, new)

# ---- the page, moved to design/ ----
sub('---\nlayout: null\n---\n', '')
sub('<link rel="canonical" href="https://sleevesight.app/">\n', '')
sub('<meta property="og:url" content="https://sleevesight.app/">\n', '')
sub("""href="{{ '/assets/favicon.svg' | relative_url }}\"""", 'href="../assets/favicon.svg"')
if re.search(r'\{[{%]', h.replace("{{ '/assets/favicon.svg' | relative_url }}", '', 1)):
    sys.exit('index.html holds Liquid other than the favicon\'s URL; teach the builder to resolve it')
sub(r'href="(?!https?:|mailto:|#|\.\./)([^"]+)"',
    lambda m: 'href="%s"' % (m.group(1)[len('design/'):] if m.group(1).startswith('design/') else '../' + m.group(1)),
    count=len(re.findall(r'href="(?!https?:|mailto:|#|\.\./)[^"]+"', h)), regex=True)
sub('<meta name="color-scheme" content="light dark">\n', '<meta name="color-scheme" content="light dark">\n<meta name="robots" content="noindex">\n')
sub('<!-- The landing page: no theme, no web fonts, no analytics, and short scripts, for the header and the\n'
    '     intro. The one thing it keeps in the browser is a note that the intro has played (`localStorage`).\n',
    '<!-- An alternative to the landing page (`index.html`), for testers to compare: the text tile as its logo\n'
    '     and intro in place of the eye. Built by `assets/logo/src/make_landing_tile.py`; not edited by hand.\n'
    '     Unlike the landing page, it plays the intro on every load and keeps nothing in the browser.\n'
    '     Otherwise as the landing page: no theme, no web fonts, no analytics, and short scripts.\n')
sub(' The one thing it keeps in your browser is a note that the intro has played.</p>', ' This page keeps nothing in your browser.</p>')
sub('  <p class="replay" hidden>', '  <p>An alternative logo and intro, for testers to compare with the <a href="../">product page</a>.</p>\n'
    '  <p class="replay" hidden>')
# Every load plays the intro, and the landing page's note that it has played is neither read nor written.
sub("  try { if (localStorage.getItem('sl-intro-played')) return; } catch (e) {}\n", '')
sub('// Set before the first paint, so that a later visit, which skips the intro, never shows the page hidden.',
    '// Set before the first paint, so the page stays hidden until the intro starts.')
sub("    try { localStorage.setItem('sl-intro-played', '1'); } catch (e) {}\n", '')

# ---- the logo ----
X0, Y0, X1, Y1 = t['ink']
VW, VH = X1 - X0, Y1 - Y0
VB = '%g %g %g %g' % (X0, Y0, round(VW, 2), round(VH, 2))
wpx = lambda hpx: '%.2fpx' % (hpx * VW / VH)
DOT = '%.3f%% %.3f%%' % (-X0 / VW * 100, -Y0 / VH * 100)

sub('<body>\n', '<body>\n<svg width="0" height="0" style="position:absolute" aria-hidden="true"><defs><g id="words">'
    '<path class="t-ink" d="%s"/><path class="t-red" d="%s"/></g></defs></svg>\n' % (t['sleeve'], t['sight']))
sub(r'<div class="splash" aria-hidden="true">.*?</div>\n',
    '<div class="splash" aria-hidden="true"><div class="fly"><div class="spin"><svg viewBox="-72 -72 144 144">'
    '<rect class="t-bg" x="-71.5" y="-71.5" width="143" height="143" rx="31.5" stroke-width="1" vector-effect="non-scaling-stroke"/>'
    '<use href="#words"/></svg></div></div></div>\n', regex=True)
sub(r'    <svg viewBox="0 0 32 32" aria-hidden="true">.*?</svg>\n    <span class="name">Sleeve<span class="sight">sight</span></span>\n',
    '    <svg viewBox="%s" aria-hidden="true"><use href="#words"/></svg>\n' % VB, regex=True)
# The header shows the words alone, their ink box as the view box, so they line up with the page's
# edge, as tall as the eye was (72 px; 36 px compact; 58 px under 370 px). They turn about the dot.
sub('.brand{display:flex;align-items:center;gap:18px;flex:none;font-weight:700;font-stretch:88%;font-size:40px;line-height:1.1;letter-spacing:-.015em;color:var(--ink);text-decoration:none}',
    '.brand{display:flex;align-items:center;flex:none;text-decoration:none}')
sub(r'/\* `top`: flex centring leaves the eye.*?\n\.brand>svg\{width:72px;height:72px;flex:none;display:block;position:relative;top:\.75px\}',
    "/* The logo: the words, with their ink box as the view box, so the S lines up with the page's edge.\n"
    "   It turns about the dot (`transform-origin`). */\n"
    '.brand>svg{width:%s;height:72px;flex:none;display:block;overflow:visible;transform-origin:%s}' % (wpx(72), DOT), regex=True)
sub(r'/\* The name: "Sleeve" bold in ink.*?\n\.brand \.sight\{font-weight:400;color:var\(--sight\)\}\n', '', regex=True)
sub('@media (max-width:370px){.brand{font-size:32px;gap:14px}.brand>svg{width:58px;height:58px}}',
    '@media (max-width:370px){.brand>svg{width:%s;height:58px}}' % wpx(58))
sub('.sl-compact .brand{font-size:20px;gap:10px}\n', '')
sub('.sl-compact .brand>svg{width:36px;height:36px}', '.sl-compact .brand>svg{width:%s;height:36px}' % wpx(36))
sub('  .brand{transition:font-size .25s ease,gap .25s ease}\n', '')
sub(r':is\(\.brand,\.splash\) \.eye\{fill:none;stroke:#1f2328\}\n.*?:is\(\.brand,\.splash\) \.core\{display:inline\}\}\n', '', regex=True)

CSS = r'''/* The logo: "Sleeve" over "sight", right-aligned, Roboto Flex 400 at 88% width, outlined
   (`assets/logo/src/make_tile.py`), so it needs no font. The dot of the i is at (0,0), the tile's
   centre, so it turns about the dot. The tile is the intro's alone; the header shows the words on the
   page. "sight" is `--sight`, 4.5:1 on the page as well as on the tile. */
:root{--tile-bg:#fff;--tile-edge:transparent;--tile-ink:#141a22}
@media (prefers-color-scheme: dark){:root{--tile-bg:#000;--tile-edge:#ffffff26;--tile-ink:#fff}}
.t-bg{fill:var(--tile-bg);stroke:var(--tile-edge)}
.t-ink{fill:var(--tile-ink)}
.t-red{fill:var(--sight)}

/* The intro (`.sl-intro`), set by the scripts when motion is allowed and the address names no
   section. The tile fades in large in the middle of the window, turns twice about the dot as the
   record does in the eye (2 s, with the landing page's easing for it), and shrinks onto the header's
   logo, fading as it goes, so the words land on the page; then the rest of the page fades in.
   Scrolling, a key or a click ends it at once; "Play the intro again" plays it once more. Hovering
   over or focusing the logo (`.play`) turns it once. Nothing moves under "reduce motion". */
.splash{display:none}
.sl-intro .splash{display:block;position:fixed;z-index:30;left:var(--sx);top:var(--sy);pointer-events:none;animation:tl-in 700ms ease-out both,sl-gone 1ms 3950ms forwards}
.splash .fly{width:var(--sb);height:var(--sb);transform-origin:0 0;animation:sl-fly 650ms cubic-bezier(.3,0,.2,1) 3300ms both}
.splash .spin{width:100%;height:100%;animation:tl-spin 2000ms cubic-bezier(.45,0,.55,1) 900ms both}
.splash svg{display:block;width:100%;height:100%;overflow:visible}
.splash .t-bg{animation:tl-bare 650ms cubic-bezier(.3,0,.2,1) 3300ms both}
.sl-wait header.top .brand,.sl-wait header.top nav,.sl-wait main,.sl-wait footer{opacity:0}
.sl-intro .brand>svg{animation:sl-hold 3950ms}
.sl-intro header.top nav,.sl-intro main,.sl-intro footer{animation:sl-arrive 450ms ease-out 3950ms both}
@media (prefers-reduced-motion: no-preference){.brand.play>svg{animation:sl-hover 900ms cubic-bezier(.45,0,.55,1)}}
@keyframes tl-in{from{opacity:0}}
@keyframes tl-bare{to{opacity:0}}
@keyframes tl-spin{from{transform:rotate(0)}to{transform:rotate(720deg)}}
@keyframes sl-fly{to{transform:translate(var(--fx),var(--fy)) scale(var(--fs))}}
@keyframes sl-gone{from,to{visibility:hidden;opacity:0}}
@keyframes sl-hold{from,to{opacity:0}}
@keyframes sl-arrive{from{opacity:0}}
@keyframes sl-hover{to{transform:rotate(360deg)}}
'''
INTRO = r'/\* The intro \(`\.sl-intro`\).*?@keyframes sl-hover-shut\{[^\n]*\n'
EYE_KEYFRAMES = {'sl-fly', 'sl-gone', 'sl-hold', 'sl-arrive', 'sl-name', 'sp-sleeve', 'sp-eye', 'sp-blink', 'sp-shut',
                 'sp-spin', 'sl-out', 'sl-eyes', 'sl-blink', 'sl-blink-shut', 'sl-hover', 'sl-hover-shut'}
m = re.search(INTRO, h, re.S)
found = set(re.findall(r'@keyframes ([\w-]+)', m.group(0))) if m else set()
if found != EYE_KEYFRAMES:
    sys.exit('the intro\'s CSS in index.html has changed: keyframes %s' % sorted(found ^ EYE_KEYFRAMES))
sub(INTRO, lambda m: CSS, regex=True)

# ---- the script ----
# The intro ends once the page has arrived, not on the eye's blink.
sub("""  brand.addEventListener('animationend', function (e) { if (e.animationName === 'sl-blink') end(); });""",
    """  document.querySelector('main').addEventListener('animationend', function (e) { if (e.animationName === 'sl-arrive') end(); });""")
sub(r"    // The splash starts in the middle of the window and shrinks onto the header's logo\..*?logo = brand",
    "    // The splash starts in the middle of the window, as large as fits with room for its corners as it\n"
    "    // turns, and shrinks onto the header's logo.\n"
    "    var big = Math.min(300, innerWidth * .62, innerHeight * .45),\n        logo = brand", regex=True)
sub("    root.style.setProperty('--sw', -wait + 'px');\n", '')
sub('  // it does not blink the logo again.\n', '  // it does not turn the logo again.\n')
# A hover's turn still running would turn the box the flight is measured from, and win over `sl-hold`.
sub('  function intro() {\n', "  function intro() {\n    // A hover's turn still running would turn the logo's box, which the flight is measured from.\n"
    "    brand.classList.remove('play');\n")
sub("""    root.style.setProperty('--fx', logo.left - x + 'px');
    root.style.setProperty('--fy', logo.top - y + 'px');
    root.style.setProperty('--fs', logo.width / big);""",
"""    // The header's logo is the words' ink box (from X0, Y0, VH tall, in tile units), the splash the
    // whole tile (-72 to 72), so the tile lands where the header's words sit in it.
    var X0 = %g, Y0 = %g, VH = %g, u = logo.height / VH;
    root.style.setProperty('--fx', logo.left + (-72 - X0) * u - x + 'px');
    root.style.setProperty('--fy', logo.top + (-72 - Y0) * u - y + 'px');
    root.style.setProperty('--fs', 144 * u / big);""" % (X0, Y0, round(VH, 2)))

if '--seek' in sys.argv[1:]:
    # The screenshot's own resize would end the intro, so the stops are held back.
    sub('</body>', """<script>
(function () {
  var t = new URLSearchParams(location.search).get('t');
  if (t === null) return;
  ['wheel', 'touchstart', 'keydown', 'pointerdown', 'scroll', 'resize', 'focusin'].forEach(function (e) {
    addEventListener(e, function (ev) { ev.stopImmediatePropagation(); }, true);
  });
  document.getAnimations().forEach(function (a) { a.pause(); a.currentTime = +t; });
})();
</script>
</body>""")

LEFT = [r'\{[{%]', 'localStorage', 'sl-intro-played', r'\bsl-blink\b', r'\bsl-name\b', r'\bsp-', '--sw', r'class="eye',
        r'\.eyes?\b', r'\.sleeve\b', r'\.name\b', r'\.sight\b', r'\b(?:src|srcset)="(?!https?:|data:|#|\.\./)',
        r'url\((?![\'"]?(?:#|https?:|data:))', r'href="(?!https?:|mailto:|#|\.\./|[\w-]+\.html")']
for pat in LEFT:
    hit = re.search(pat, h)
    if hit:
        sys.exit('the page would still hold %r: ...%s...' % (pat, h[max(0, hit.start() - 60):hit.end() + 60]))
open(os.path.join(ROOT, 'design', 'landing-tile.html'), 'w').write(h)
