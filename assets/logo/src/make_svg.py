"""The sleeve-to-eye animation as one self-contained SVG: CSS keyframes, a 10 s loop, light and dark.

The timeline and geometry come from timeline.py, shared with render.py, which makes the video. From
assets/logo:

    python3 src/make_svg.py sleeve-to-eye.svg

The sleeve art, sundown.json, is coverFor("Sundown|Gordon Lightfoot") from design/simulator.html.
"""
import json, os, sys
from timeline import (VB, S, X0, OFF, HALF, EYE_IN, EXIT, LOGO, RETURN, EYE_OUT, END,
                      B1, B2, CL, SH, OP, SHUT_ON, SHUT_OFF, CLOSED, SPIN_S, SPIN_D, TURNS, EASE)

pc = lambda s: f'{s / END * 100:.2f}%'                        # a time as a keyframe percentage
num = lambda v: (f'{v:g}'[1:] if 0 < v < 1 else f'{v:g}')     # CSS's short form: .45, 0, 1
ease = lambda k: 'cubic-bezier(' + ','.join(map(num, EASE[k])) + ')'
vb = ' '.join(map(str, VB))
at = lambda ms: LOGO + ms / 1000                              # ms from LOGO, as seconds from the start

cover = json.load(open(os.path.join(os.path.dirname(__file__) or '.', 'sundown.json')))['svg']
cover_inner = cover[cover.index('>') + 1: cover.rindex('</svg>')]

# The blinks: CL ms closing, SH ms shut, OP ms opening; the closed lid's line shows across the shut.
CE, OE = ease('close'), ease('open')
blink, shut = ['0%{transform:scaleY(1);animation-timing-function:' + CE + '}'], ['0%{opacity:0}']
for b in (B1, B2):
    blink += [f'{pc(at(b))}{{transform:scaleY(1);animation-timing-function:{CE}}}',
              f'{pc(at(b + CL))},{pc(at(b + CL + SH))}{{transform:scaleY({num(CLOSED)});animation-timing-function:{OE}}}',
              f'{pc(at(b + CL + SH + OP))}{{transform:scaleY(1);animation-timing-function:{CE}}}']
    shut += [f'{pc(at(b + CL * SHUT_ON))}{{opacity:1}}', f'{pc(at(b + CL + SH + OP * SHUT_OFF))}{{opacity:0}}']
blink.append('100%{transform:scaleY(1)}')
spin = (at(SPIN_S), at(SPIN_S + SPIN_D))
turns = {1: 'One turn', 2: 'Two turns'}.get(TURNS, f'{TURNS} turns')

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}" role="img" aria-label="Sleevesight: a record slides out of its sleeve and becomes an eye">
<!-- A record slides out of its sleeve (the simulator's generated cover for Sundown by Gordon Lightfoot),
     the eye forms around it, blinks, the record turns twice, the eye blinks again, and the sleeve
     comes back. A {END:g} s loop. -->
<style>
  .bg {{ fill: #fafbfc }}
  .eye {{ fill: none; stroke: #1f2328 }}
  .core {{ display: none }}
  .shut {{ stroke: #1f2328 }}
  /* On dark, the eye is filled white and outlined in grey; the record's rim takes the outline's grey,
     merging with it as the black rim does on light, and the groove gives way to a wider black disc
     (.core), so the grey meets the black directly. The disc is drawn only on dark: on light, its edge
     would darken the rim's antialiased edge where the lids clip it. */
  @media (prefers-color-scheme: dark) {{
    .bg {{ fill: #0d1015 }}
    .eye {{ fill: #ffffff; stroke: #8b949e }}
    .rim {{ fill: #8b949e }}
    .groove {{ display: none }}
    .core {{ display: inline }}
    .shut {{ stroke: #8b949e }}
    .shadow {{ opacity: .55 }}
  }}
  .lid, .clip, .disc {{ transform-box: view-box; transform-origin: 16px 16px }}
  .sleeve {{ animation: sleeve {END:g}s infinite }}
  .eyes {{ animation: eye {END:g}s infinite linear }}
  .lid, .clip {{ animation: blink {END:g}s infinite }}
  .shut {{ opacity: 0; animation: shut {END:g}s infinite steps(1, end) }}
  .disc {{ animation: spin {END:g}s infinite }}
  /* Slides left by half ({HALF[0]:g}–{HALF[1]:g} s), leaves ({EXIT[0]:g}–{EXIT[1]:g} s), comes back ({RETURN[0]:g}–{RETURN[1]:g} s). */
  @keyframes sleeve {{
    0%, {pc(HALF[0])} {{ transform: translateX(0); animation-timing-function: {ease('settle')} }}
    {pc(HALF[1])}, {pc(EXIT[0])} {{ transform: translateX({-S / 2}px); animation-timing-function: {ease('leave')} }}
    {pc(EXIT[1])}, {pc(RETURN[0])} {{ transform: translateX({OFF - X0:.2f}px); animation-timing-function: {ease('arrive')} }}
    {pc(RETURN[1])}, 100% {{ transform: translateX(0) }}
  }}
  /* The eye: its right corner appears at {EYE_IN[0]:g}–{EYE_IN[1]:g} s (the left one is under the sleeve), gone by {EYE_OUT[1]:g} s. */
  @keyframes eye {{ 0%, {pc(EYE_IN[0])} {{ opacity: 0 }} {pc(EYE_IN[1])}, {pc(EYE_OUT[0])} {{ opacity: 1 }} {pc(EYE_OUT[1])}, 100% {{ opacity: 0 }} }}
  @keyframes blink {{ {' '.join(blink)} }}
  @keyframes shut {{ {' '.join(shut)} }}
  /* {turns}, {spin[0]:g}–{spin[1]:g} s, starting and stopping like a turntable. */
  @keyframes spin {{
    0%, {pc(spin[0])} {{ transform: rotate(0); animation-timing-function: {ease('turntable')} }}
    {pc(spin[1])}, 100% {{ transform: rotate({360 * TURNS}deg) }}
  }}
  @media (prefers-reduced-motion: reduce) {{ .sleeve, .eyes, .lid, .clip, .shut, .disc {{ animation: none }} .sleeve {{ display: none }} }}
</style>
<defs>
  <clipPath id="eyeclip"><path class="clip" d="M1.5 16 Q16 -.25 30.5 16 Q16 32.25 1.5 16 Z"/></clipPath>
  <filter id="soft" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation=".35"/></filter>
</defs>
<rect class="bg" x="{VB[0]}" y="{VB[1]}" width="{VB[2]}" height="{VB[3]}"/>
<g class="eyes">
  <path class="eye lid" d="M1.5 16 Q16 3 30.5 16 Q16 29 1.5 16 Z" stroke-width="2" stroke-linejoin="round"/>
</g>
<g clip-path="url(#eyeclip)"><g class="disc">
  <circle class="rim" cx="16" cy="16" r="7.5" fill="#1f2328"/>
  <circle class="core" cx="16" cy="16" r="5.7" fill="#1f2328"/>
  <circle class="groove" cx="16" cy="16" r="5.4" fill="none" stroke="#57606a" stroke-width=".8"/>
  <circle cx="16" cy="16" r="3" fill="#cf222e"/>
  <path d="M14.4 14.9 A2 2 0 0 1 17.6 14.9" fill="none" stroke="#fff" stroke-width=".42" stroke-linecap="round"/>
  <path d="M15.1 18.05 H16.9" fill="none" stroke="#fff" stroke-width=".42" stroke-linecap="round"/>
  <circle cx="16" cy="16" r=".9" fill="#fff"/>
</g></g>
<path class="shut" d="M1.5 16 H30.5" stroke-width="2" stroke-linecap="round" fill="none"/>
<g class="sleeve">
  <!-- Background left of the sleeve: the returning sleeve wipes the eye's left corner away. -->
  <rect class="bg" x="{X0 - 200:.2f}" y="{VB[1]}" width="200" height="{VB[3]}"/>
  <rect class="shadow" x="{X0 + .25:.2f}" y="{X0 + .4:.2f}" width="{S}" height="{S}" rx=".25" fill="#000" opacity=".22" filter="url(#soft)"/>
  <svg x="{X0:.2f}" y="{X0:.2f}" width="{S}" height="{S}" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice">{cover_inner}</svg>
</g>
</svg>
'''
open(sys.argv[1], 'w').write(svg)
