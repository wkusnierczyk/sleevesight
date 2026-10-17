"""The sleeve-to-eye animation as one self-contained SVG: CSS keyframes, a 10 s loop, light and dark.

The timeline matches render.py, which makes the video. From assets/logo:

    python3 src/make_svg.py sleeve-to-eye.svg

The sleeve art, sundown.json, is coverFor("Sundown|Gordon Lightfoot") from design/simulator.html.
"""
import json, os, sys

S = 16.4
X0 = 16 - S / 2
OFF = -16 - S - 2
END = 10.0
pc = lambda s: f'{s / END * 100:.2f}%'

cover = json.load(open(os.path.join(os.path.dirname(__file__) or '.', 'sundown.json')))['svg']
cover_inner = cover[cover.index('>') + 1: cover.rindex('</svg>')]

# Blinks at 4.4 s and 7.1 s: 100 ms closing, 40 ms shut, 140 ms opening.
CE, OE = 'cubic-bezier(.5,0,1,1)', 'cubic-bezier(0,0,.4,1)'
blink, shut = ['0%{transform:scaleY(1);animation-timing-function:' + CE + '}'], ['0%{opacity:0}']
for s in (4.4, 7.1):
    blink += [f'{pc(s)}{{transform:scaleY(1);animation-timing-function:{CE}}}',
              f'{pc(s + .1)},{pc(s + .14)}{{transform:scaleY(.02);animation-timing-function:{OE}}}',
              f'{pc(s + .28)}{{transform:scaleY(1);animation-timing-function:{CE}}}']
    shut += [f'{pc(s + .08)}{{opacity:1}}', f'{pc(s + .168)}{{opacity:0}}']
blink.append('100%{transform:scaleY(1)}')

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="-16 -2 64 36" role="img" aria-label="Sleevesight: a record slides out of its sleeve and becomes an eye">
<!-- A record slides out of its sleeve (the simulator's generated cover for Sundown by Gordon Lightfoot),
     the eye forms around it, blinks, the record turns twice, the eye blinks again, and the sleeve
     comes back. A 10 s loop. -->
<style>
  .bg {{ fill: #fafbfc }}
  .eye {{ fill: none; stroke: #1f2328 }}
  .shut {{ stroke: #1f2328 }}
  @media (prefers-color-scheme: dark) {{
    .bg {{ fill: #0d1015 }}
    .eye {{ fill: #ffffff; stroke: #8b949e }}
    .rim {{ fill: #8b949e }}
    .groove {{ display: none }}
    .shut {{ stroke: #8b949e }}
    .shadow {{ opacity: .55 }}
  }}
  .lid, .clip, .disc {{ transform-box: view-box; transform-origin: 16px 16px }}
  .sleeve {{ animation: sleeve 10s infinite }}
  .eyes {{ animation: eye 10s infinite linear }}
  .lid, .clip {{ animation: blink 10s infinite }}
  .shut {{ opacity: 0; animation: shut 10s infinite steps(1, end) }}
  .disc {{ animation: spin 10s infinite }}
  /* Slides left by half (0.8–1.7 s), leaves (2.8–3.8 s), comes back (8.4–9.4 s). */
  @keyframes sleeve {{
    0%, {pc(.8)} {{ transform: translateX(0); animation-timing-function: cubic-bezier(.45,0,.2,1) }}
    {pc(1.7)}, {pc(2.8)} {{ transform: translateX({-S / 2}px); animation-timing-function: cubic-bezier(.55,0,1,.45) }}
    {pc(3.8)}, {pc(8.4)} {{ transform: translateX({OFF - X0:.2f}px); animation-timing-function: cubic-bezier(0,.55,.45,1) }}
    {pc(9.4)}, 100% {{ transform: translateX(0) }}
  }}
  /* The eye: its right corner appears at 1.8–2.3 s (the left one is under the sleeve), gone by 9.3 s. */
  @keyframes eye {{ 0%, {pc(1.8)} {{ opacity: 0 }} {pc(2.3)}, {pc(8.6)} {{ opacity: 1 }} {pc(9.3)}, 100% {{ opacity: 0 }} }}
  @keyframes blink {{ {' '.join(blink)} }}
  @keyframes shut {{ {' '.join(shut)} }}
  /* Two turns, 4.9–6.9 s, starting and stopping like a turntable. */
  @keyframes spin {{
    0%, {pc(4.9)} {{ transform: rotate(0); animation-timing-function: cubic-bezier(.45,0,.55,1) }}
    {pc(6.9)}, 100% {{ transform: rotate(720deg) }}
  }}
  @media (prefers-reduced-motion: reduce) {{ .sleeve, .eyes, .lid, .clip, .shut, .disc {{ animation: none }} .sleeve {{ display: none }} }}
</style>
<defs>
  <clipPath id="eyeclip"><path class="clip" d="M1.5 16 Q16 -.25 30.5 16 Q16 32.25 1.5 16 Z"/></clipPath>
  <filter id="soft" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation=".35"/></filter>
</defs>
<rect class="bg" x="-16" y="-2" width="64" height="36"/>
<g class="eyes">
  <path class="eye lid" d="M1.5 16 Q16 3 30.5 16 Q16 29 1.5 16 Z" stroke-width="2" stroke-linejoin="round"/>
</g>
<g clip-path="url(#eyeclip)"><g class="disc">
  <circle class="rim" cx="16" cy="16" r="7.5" fill="#1f2328"/>
  <circle cx="16" cy="16" r="5.7" fill="#1f2328"/>
  <circle class="groove" cx="16" cy="16" r="5.4" fill="none" stroke="#57606a" stroke-width=".8"/>
  <circle cx="16" cy="16" r="3" fill="#cf222e"/>
  <path d="M14.4 14.9 A2 2 0 0 1 17.6 14.9" fill="none" stroke="#fff" stroke-width=".42" stroke-linecap="round"/>
  <path d="M15.1 18.05 H16.9" fill="none" stroke="#fff" stroke-width=".42" stroke-linecap="round"/>
  <circle cx="16" cy="16" r=".9" fill="#fff"/>
</g></g>
<path class="shut" d="M1.5 16 H30.5" stroke-width="2" stroke-linecap="round" fill="none"/>
<g class="sleeve">
  <!-- Background left of the sleeve: the returning sleeve wipes the eye's left corner away. -->
  <rect class="bg" x="{X0 - 200:.2f}" y="-2" width="200" height="36"/>
  <rect class="shadow" x="{X0 + .25:.2f}" y="{X0 + .4:.2f}" width="{S}" height="{S}" rx=".25" fill="#000" opacity=".22" filter="url(#soft)"/>
  <svg x="{X0:.2f}" y="{X0:.2f}" width="{S}" height="{S}" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice">{cover_inner}</svg>
</g>
</svg>
'''
open(sys.argv[1], 'w').write(svg)
