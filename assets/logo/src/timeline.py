"""The sleeve-to-eye animation's timeline and geometry, shared by make_svg.py (the SVG) and render.py
(the video), so the two cannot drift apart. Times are in seconds from the loop's start, except the
logo animation's, which are in ms from LOGO, as in the explorer's "As now" preset.

index.html's `sp-*` keyframes are a third copy, adapted to the page (its sleeve waits at `--sw`), and
are not generated from this module.
"""

# ---- geometry (logo units) ----
VB = (-16, -2, 64, 36)        # view box; the logo's centre (16, 16) is the frame's centre
S = 16.4                      # sleeve side: a 12 3/8" jacket around a 12" record of diameter 15
X0 = 16 - S / 2               # sleeve's left edge, centred over the record
OFF = VB[0] - S - 2           # left edge once out of frame (the shadow included)

# ---- the sleeve and the eye (seconds) ----
HALF = (.8, 1.7)              # slides left by half, uncovering the record's right half
EYE_IN = (1.8, 2.3)           # the eye's right corner appears
EXIT = (2.8, 3.8)             # the sleeve leaves to the left; the record's left half and the left corner show
LOGO = 4.0                    # the logo animation: blink, two turns, blink (as on the page)
RETURN = (8.4, 9.4)           # a second after it ends, the sleeve comes back over the record
EYE_OUT = (8.6, 9.3)
END = 10.0

# ---- the logo animation (ms from LOGO) ----
B1, B2 = 400, 3100            # the blinks' starts
CL, SH, OP = 100, 40, 140     # each blink: closing, shut, opening
SHUT_ON, SHUT_OFF = .8, .2    # the closed lid's line shows from this far into closing to this far into opening
CLOSED = .02                  # the lid's vertical scale while the eye is shut
SPIN_S, SPIN_D, TURNS = 900, 2000, 2

# ---- easing: cubic-bezier control points (x1, y1, x2, y2) ----
EASE = {
    'settle': (.45, 0, .2, 1),      # the sleeve slides by half
    'leave': (.55, 0, 1, .45),      # the sleeve leaves
    'arrive': (0, .55, .45, 1),     # the sleeve comes back
    'close': (.5, 0, 1, 1),         # a lid closes
    'open': (0, 0, .4, 1),          # a lid opens
    'turntable': (.45, 0, .55, 1),  # the record's turns start and stop
}
