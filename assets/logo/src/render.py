"""The sleeve-to-eye animation as video: each frame drawn as SVG, rasterised by rsvg-convert.

The timeline and geometry come from timeline.py, shared with make_svg.py, which makes the vector
version. From assets/logo:

    python3 src/render.py light frames-light          # 600 PNG frames, 1920x1080
    ffmpeg -framerate 60 -i frames-light/f%05d.png -c:v libx264 -profile:v main -level 4.2 \
        -pix_fmt yuv420p -crf 16 -preset slow -movflags +faststart sleeve-to-eye-light.mp4

and the same for dark. `python3 src/render.py light stills 60 2.5` renders one still at 2.5 s.
The sleeve art, sundown.json, is coverFor("Sundown|Gordon Lightfoot") from design/simulator.html.
"""
import json, os, subprocess, sys
from timeline import (VB, S, X0, OFF, HALF, EYE_IN, EXIT, LOGO, RETURN, EYE_OUT, END,
                      B1, B2, CL, SH, OP, SHUT_ON, SHUT_OFF, SPIN_S, SPIN_D, TURNS, EASE)

theme, out = sys.argv[1], sys.argv[2]
FPS = int(sys.argv[3]) if len(sys.argv) > 3 else 60
STILL = float(sys.argv[4]) if len(sys.argv) > 4 else None
W, H = 1920, 1080

BG = {'light': '#fafbfc', 'dark': '#0d1015'}[theme]
EYE_FILL, EYE_STROKE = {'light': ('none', '#1f2328'), 'dark': ('#ffffff', '#8b949e')}[theme]
SHADOW = {'light': .22, 'dark': .55}[theme]
# On dark, the record's rim merges with the eye's outline, and the groove gives way to a wider black
# disc. The disc is drawn only on dark: on light, its edge would darken the rim's antialiased edge.
RIM = {'light': '#1f2328', 'dark': '#8b949e'}[theme]
MIDDLE = {'light': '<circle cx="16" cy="16" r="5.4" fill="none" stroke="#57606a" stroke-width=".8"/>',
          'dark': '<circle cx="16" cy="16" r="5.7" fill="#1f2328"/>'}[theme]

# ---- easing ----
def bezier(x1, y1, x2, y2):
    def f(x):
        if x <= 0: return 0.0
        if x >= 1: return 1.0
        t = x
        for _ in range(30):
            cx = 3 * x1 * t * (1 - t) ** 2 + 3 * x2 * t ** 2 * (1 - t) + t ** 3 - x
            d = 3 * x1 * (1 - t) ** 2 + 6 * (x2 - x1) * t * (1 - t) + 3 * (1 - x2) * t ** 2
            if abs(cx) < 1e-7 or d == 0: break
            t = min(1, max(0, t - cx / d))
        return 3 * y1 * t * (1 - t) ** 2 + 3 * y2 * t ** 2 * (1 - t) + t ** 3
    return f
CLOSE, OPEN, TURNTABLE = (bezier(*EASE[k]) for k in ('close', 'open', 'turntable'))
SETTLE, LEAVE, ARRIVE = (bezier(*EASE[k]) for k in ('settle', 'leave', 'arrive'))
LINEAR = lambda x: x

def seg(t, a, b, ease=LINEAR):
    """Progress 0..1 of t through [a, b]."""
    return ease(min(1, max(0, (t - a) / (b - a))))

# ---- the timeline (timeline.py) at time t ----
def sleeve_x(t):
    if t < EXIT[0]:
        return X0 - S / 2 * seg(t, *HALF, SETTLE)
    if t < RETURN[0]:
        a = X0 - S / 2
        return a + (OFF - a) * seg(t, *EXIT, LEAVE)
    return OFF + (X0 - OFF) * seg(t, *RETURN, ARRIVE)

def eye_opacity(t):
    return seg(t, *EYE_IN) * (1 - seg(t, *EYE_OUT))

# The logo animation, in ms from LOGO.
def lid(ms):
    for s in (B1, B2):
        if s <= ms < s + CL: return 1 - .98 * CLOSE((ms - s) / CL)
        if s + CL <= ms < s + CL + SH: return .02
        if s + CL + SH <= ms < s + CL + SH + OP: return .02 + .98 * OPEN((ms - s - CL - SH) / OP)
    return 1.0
def shut(ms):
    return any(s + CL * SHUT_ON <= ms < s + CL + SH + OP * SHUT_OFF for s in (B1, B2))
def angle(ms):
    return 360 * TURNS * TURNTABLE(min(1, max(0, (ms - SPIN_S) / SPIN_D)))

# ---- the sleeve art: the simulator's generated cover for Sundown by Gordon Lightfoot ----
cover = json.load(open(os.path.join(os.path.dirname(__file__) or '.', 'sundown.json')))['svg']
cover_inner = cover[cover.index('>') + 1: cover.rindex('</svg>')]

def frame(t):
    ms = (t - LOGO) * 1000
    k = lid(ms) if 0 <= ms else 1.0
    rot = angle(ms) if 0 <= ms else 0.0
    sx = sleeve_x(t)
    eo = eye_opacity(t)
    sy = lambda k: f'translate(0 16) scale(1 {k:.4f}) translate(0 -16)'
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="{' '.join(map(str, VB))}">
<defs>
  <clipPath id="eyeclip"><path d="M1.5 16 Q16 -.25 30.5 16 Q16 32.25 1.5 16 Z" transform="{sy(k)}"/></clipPath>
  <filter id="soft" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation=".35"/></filter>
</defs>
<rect x="{VB[0]}" y="{VB[1]}" width="{VB[2]}" height="{VB[3]}" fill="{BG}"/>
<g opacity="{eo:.4f}">
  <path d="M1.5 16 Q16 3 30.5 16 Q16 29 1.5 16 Z" transform="{sy(k)}" fill="{EYE_FILL}" stroke="{EYE_STROKE}" stroke-width="2" stroke-linejoin="round"/>
</g>
<g clip-path="url(#eyeclip)"><g transform="rotate({rot:.3f} 16 16)">
  <circle cx="16" cy="16" r="7.5" fill="{RIM}"/>
  {MIDDLE}
  <circle cx="16" cy="16" r="3" fill="#cf222e"/>
  <path d="M14.4 14.9 A2 2 0 0 1 17.6 14.9" fill="none" stroke="#fff" stroke-width=".42" stroke-linecap="round"/>
  <path d="M15.1 18.05 H16.9" fill="none" stroke="#fff" stroke-width=".42" stroke-linecap="round"/>
  <circle cx="16" cy="16" r=".9" fill="#fff"/>
</g></g>
{f'<path d="M1.5 16 H30.5" stroke="{EYE_STROKE}" stroke-width="2" stroke-linecap="round" opacity="{eo:.4f}"/>' if shut(ms) else ''}
<rect x="{sx - 200:.4f}" y="{VB[1]}" width="200" height="{VB[3]}" fill="{BG}"/>
<rect x="{sx + .25:.4f}" y="{X0 + .4:.4f}" width="{S}" height="{S}" rx=".25" fill="#000" opacity="{SHADOW}" filter="url(#soft)"/>
<svg x="{sx:.4f}" y="{X0:.4f}" width="{S}" height="{S}" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice">{cover_inner}</svg>
</svg>'''

os.makedirs(out, exist_ok=True)
if STILL is not None:
    p = os.path.join(out, f'still-{theme}-{STILL:.2f}.svg')
    open(p, 'w').write(frame(STILL))
    subprocess.run(['rsvg-convert', '-w', str(W // 2), '-o', p[:-4] + '.png', p], check=True)
    sys.exit(0)
n = int(END * FPS)
for i in range(n):
    p = os.path.join(out, f'f{i:05d}.svg')
    open(p, 'w').write(frame(i / FPS))
    subprocess.run(['rsvg-convert', '-o', p[:-4] + '.png', p], check=True)
    os.remove(p)
