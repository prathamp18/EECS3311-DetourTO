"""Draws the DetourTO use case diagram with a fixed layout.

Graphviz scatters 19 use cases and 6 actors badly, so this script places every
element by hand and emits plain UML notation (stick-figure actors, a system
boundary rectangle, ellipses, solid associations, dashed <<include>> /
<<extend>> dependencies with open arrowheads, and generalization with a hollow
triangle), matching the EECS 3311 UML slides.

Usage:  python3 usecase_layout.py   ->  ../png/usecase.png and ../svg/usecase.svg
"""
import math
import os

import cairosvg

W, H = 1500, 1190
BOX = (190, 70, 1170, 1125)  # x1, y1, x2, y2 of the system boundary
EW, EH = 104, 25              # ellipse radii

C1, C2, C3 = 345, 690, 1015    # column centres

UC = {
    "UC04": (C1, 120, "UC04 Set Route Preference"),
    "UC10": (C1, 190, "UC10 Manage Saved Places|and Preferences"),
    "UC07": (C1, 260, "UC07 Compare and|Explain Options"),
    "UC05": (C1, 330, "UC05 View Service Alerts"),
    "UC13": (C1, 400, "UC13 Receive Leave-By Alert"),
    "UC11": (C1, 470, "UC11 Ask What-If Follow-up|(undo / redo)"),
    "UC08": (C1, 548, "UC08 Monitor Active Trip|ext. point: trip at risk"),
    "UC06": (C1, 630, "UC06 Re-route Around|Disruption"),
    "UC09": (C1, 700, "UC09 View Stop Arrivals"),
    "UC12": (C1, 770, "UC12 Set Up Commute Watch"),
    "UC01": (C1, 905, "UC01 Plan Trip"),
    "UC14": (C1, 1000, "UC14 Replay Disruption|Scenario and Benchmark"),
    "UC15": (C1, 1075, "UC15 Refresh Transit Data"),
    "UC16": (C2, 605, "UC16 Compute Itineraries"),
    "UC17": (C3, 850, "UC17 Clarify Ambiguous|Request"),
    "UC02": (C2, 850, "UC02 Plan Trip by Conversation|ext. point: ambiguous place"),
    "UC03": (C2, 960, "UC03 Plan Trip by Form"),
    "UC18": (C3, 230, "UC18 Run Agent Task"),
    "UC19": (C3, 440, "UC19 Interpret Service Alert"),
}

ACTORS = {
    "Rider": (80, 470, ["Rider"]),
    "Clock": (80, 250, ["Clock", "«timer»"]),
    "Evaluator": (80, 960, ["Evaluator"]),
    "OD": (80, 1090, ["City of Toronto", "Open Data Portal", "«external system»"]),
    "LLM": (1330, 230, ["Amazon Bedrock", "(Claude LLM)", "«external system»"]),
    "RT": (1330, 560, ["TTC GTFS-Realtime", "Feed", "«external system»"]),
}

ASSOC = [
    ("Rider", u) for u in
    ["UC04", "UC10", "UC07", "UC11", "UC05", "UC13", "UC08", "UC06", "UC09", "UC12", "UC01"]
] + [
    ("Clock", "UC13"), ("Evaluator", "UC14"), ("Evaluator", "UC15"), ("OD", "UC15"),
    ("LLM", "UC18"), ("RT", "UC19"), ("RT", "UC08"), ("RT", "UC09"),
]

# (from, to, label)  arrow points at `to`
INCLUDE = [
    ("UC10", "UC18"), ("UC07", "UC18"), ("UC11", "UC18"), ("UC11", "UC16"),
    ("UC05", "UC19"), ("UC13", "UC19"), ("UC13", "UC16"), ("UC08", "UC19"),
    ("UC06", "UC16"), ("UC06", "UC18"), ("UC01", "UC16"), ("UC02", "UC18"), ("UC19", "UC18"),
    ("UC14", "UC02"),
]
EXTEND = [("UC06", "UC08", "[trip at risk]"), ("UC17", "UC02", "[ambiguous place]")]
GENERAL = [("UC02", "UC01"), ("UC03", "UC01")]


def ell_point(cx, cy, tx, ty):
    """Point where the line from the ellipse centre toward (tx, ty) meets the ellipse."""
    dx, dy = tx - cx, ty - cy
    if dx == dy == 0:
        return cx, cy
    t = 1 / math.sqrt((dx / EW) ** 2 + (dy / EH) ** 2)
    return cx + dx * t, cy + dy * t


def actor_anchor(name, tx, ty):
    ax, ay, _ = ACTORS[name]
    return (ax + (16 if tx > ax else -16), ay)


def text(x, y, s, size=13, anchor="middle", weight="normal", italic=False):
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    st = ' font-style="italic"' if italic else ""
    return (f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" text-anchor="{anchor}" '
            f'font-weight="{weight}"{st}>{s}</text>')


def arrow_open(x1, y1, x2, y2):
    a = math.atan2(y2 - y1, x2 - x1)
    L, s = 11, 0.45
    p1 = (x2 - L * math.cos(a - s), y2 - L * math.sin(a - s))
    p2 = (x2 - L * math.cos(a + s), y2 - L * math.sin(a + s))
    return (f'<polyline points="{p1[0]:.1f},{p1[1]:.1f} {x2:.1f},{y2:.1f} {p2[0]:.1f},{p2[1]:.1f}" '
            f'fill="none" stroke="black" stroke-width="1.2"/>')


def arrow_triangle(x1, y1, x2, y2):
    a = math.atan2(y2 - y1, x2 - x1)
    L, s = 16, 0.5
    p1 = (x2 - L * math.cos(a - s), y2 - L * math.sin(a - s))
    p2 = (x2 - L * math.cos(a + s), y2 - L * math.sin(a + s))
    base = ((p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2)
    return base, (f'<polygon points="{p1[0]:.1f},{p1[1]:.1f} {x2:.1f},{y2:.1f} {p2[0]:.1f},{p2[1]:.1f}" '
                  f'fill="white" stroke="black" stroke-width="1.2"/>')


def dep_line(a, b, label, frac=0.42, dy=-5):
    ax, ay, _ = UC[a]
    bx, by, _ = UC[b]
    x1, y1 = ell_point(ax, ay, bx, by)
    x2, y2 = ell_point(bx, by, ax, ay)
    out = [f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" stroke="black" '
           f'stroke-width="1.1" stroke-dasharray="6,4"/>', arrow_open(x1, y1, x2, y2)]
    lx, ly = x1 + (x2 - x1) * frac, y1 + (y2 - y1) * frac
    for i, part in enumerate(label.split("|")):
        out.append(text(lx, ly + dy + i * 14, part, size=11))
    return out


svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
       f'font-family="Helvetica, Arial, sans-serif">',
       f'<rect width="{W}" height="{H}" fill="white"/>',
       text(W / 2, 32, "Use Case Diagram — DetourTO", size=17, weight="bold")]

x1, y1, x2, y2 = BOX
svg.append(f'<rect x="{x1}" y="{y1}" width="{x2 - x1}" height="{y2 - y1}" fill="none" stroke="black" stroke-width="1.4"/>')
svg.append(text(x1 + 12, y1 + 22, "DetourTO (JavaFX GUI and CLI)", size=14, anchor="start", weight="bold"))

# associations (behind everything)
for act, u in ASSOC:
    ux, uy, _ = UC[u]
    ax, ay = actor_anchor(act, ux, uy)
    if ax < ux and ux == C1:
        ex, ey = ux - EW, uy
    else:
        ex, ey = ell_point(ux, uy, ax, ay)
    svg.append(f'<line x1="{ax}" y1="{ay}" x2="{ex:.1f}" y2="{ey:.1f}" stroke="black" stroke-width="1.1"/>')

for a, b in INCLUDE:
    frac = {("UC06", "UC18"): 0.16, ("UC13", "UC16"): 0.30, ("UC11", "UC18"): 0.22}.get(
        (a, b), 0.30 if b in ("UC18", "UC19") else 0.55)
    svg += dep_line(a, b, "«include»", frac=frac)
for a, b, cond in EXTEND:
    svg += dep_line(a, b, f"«extend»|{cond}", frac=0.5, dy=-2)
for a, b in GENERAL:
    ax, ay, _ = UC[a]
    bx, by, _ = UC[b]
    sx, sy = ell_point(ax, ay, bx, by)
    ex, ey = ell_point(bx, by, ax, ay)
    base, tri = arrow_triangle(sx, sy, ex, ey)
    svg.append(f'<line x1="{sx:.1f}" y1="{sy:.1f}" x2="{base[0]:.1f}" y2="{base[1]:.1f}" stroke="black" stroke-width="1.2"/>')
    svg.append(tri)

# use case ellipses
for key, (cx, cy, label) in UC.items():
    svg.append(f'<ellipse cx="{cx}" cy="{cy}" rx="{EW}" ry="{EH}" fill="white" stroke="black" stroke-width="1.2"/>')
    parts = label.split("|")
    if len(parts) == 2 and parts[1].startswith("ext. point"):
        svg.append(f'<line x1="{cx - EW + 8}" y1="{cy + 1}" x2="{cx + EW - 8}" y2="{cy + 1}" stroke="black" stroke-width="0.8"/>')
        svg.append(text(cx, cy - 6, parts[0], size=11.5))
        svg.append(text(cx, cy + 15, parts[1], size=10.5, italic=True))
    else:
        start = cy + 4.5 - (len(parts) - 1) * 7
        for i, p in enumerate(parts):
            svg.append(text(cx, start + i * 14, p, size=12))

# stick-figure actors
for name, (ax, ay, lines) in ACTORS.items():
    top = ay - 40
    svg.append(f'<circle cx="{ax}" cy="{top + 9}" r="9" fill="white" stroke="black" stroke-width="1.3"/>')
    svg.append(f'<line x1="{ax}" y1="{top + 18}" x2="{ax}" y2="{top + 44}" stroke="black" stroke-width="1.3"/>')
    svg.append(f'<line x1="{ax - 14}" y1="{top + 27}" x2="{ax + 14}" y2="{top + 27}" stroke="black" stroke-width="1.3"/>')
    svg.append(f'<line x1="{ax}" y1="{top + 44}" x2="{ax - 12}" y2="{top + 62}" stroke="black" stroke-width="1.3"/>')
    svg.append(f'<line x1="{ax}" y1="{top + 44}" x2="{ax + 12}" y2="{top + 62}" stroke="black" stroke-width="1.3"/>')
    for i, l in enumerate(lines):
        svg.append(text(ax, top + 80 + i * 15, l, size=12.5 if i == 0 else 11.5))

svg.append("</svg>")
here = os.path.dirname(os.path.abspath(__file__))
os.makedirs(os.path.join(here, "..", "svg"), exist_ok=True)
svg_path = os.path.join(here, "..", "svg", "usecase.svg")
with open(svg_path, "w") as f:
    f.write("\n".join(svg))
cairosvg.svg2png(url=svg_path, write_to=os.path.join(here, "..", "png", "usecase.png"), scale=1.6)
print("wrote usecase.svg and usecase.png")
