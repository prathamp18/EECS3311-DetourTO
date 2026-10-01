"""Generates the UMLet use case diagram (uxf/usecase.uxf).

Positions, actors, use cases and relationships are read from
src/usecase_layout.py (the same hand-made layout), so the diagram keeps a
clean layout: stick-figure actors, a system boundary, one solid line per
actor-use case association, dashed «include»/«extend» arrows and hollow-
triangle generalization, as in the EECS 3311 use case slides.

Usage:  python3 usecase_to_uxf.py
"""
import os

from uxf_common import Diagram, SRC, UXF

src = open(os.path.join(SRC, "usecase_layout.py"), encoding="utf-8").read()
cut = src.index("\nsvg = [")
ns = {"__file__": os.path.join(SRC, "usecase_layout.py")}
exec(src[:cut], ns)  # loads UC, ACTORS, ASSOC, INCLUDE, EXTEND, GENERAL, BOX, EW, EH, C1, ell_point
UC, ACTORS, ASSOC, INCLUDE, EXTEND, GENERAL = (ns[k] for k in ("UC", "ACTORS", "ASSOC", "INCLUDE", "EXTEND", "GENERAL"))
BOX, EW, EH, C1, ell_point = ns["BOX"], ns["EW"], ns["EH"], ns["C1"], ns["ell_point"]

DY = 20  # room for the title
S = 1.3  # UMLet's font is larger than the preview's, so the whole layout is scaled up


def main():
    d = Diagram()
    d.add("Text", 10, 0, 600, 40, "fontsize=16\n*Use Case Diagram — DetourTO*")
    x1, y1, x2, y2 = BOX
    d.add("UMLGeneric", x1 * S, y1 * S + DY, (x2 - x1) * S, (y2 - y1) * S, "*DetourTO (JavaFX GUI and CLI)*\nhalign=left\nvalign=top")

    for key, (cx, cy, label) in UC.items():
        parts = label.split("|")
        if len(parts) == 2 and parts[1].startswith("ext. point"):
            text, h = f"{parts[0]}\n--\n{parts[1]}\nvalign=top", 2 * EH + 22
        else:
            text, h = "\n".join(parts), (2 * EH + 2) * S
        d.add("UMLUseCase", (cx - EW - 1) * S, cy * S - h / 2 + DY, (2 * EW + 2) * S, h, text)

    for name, (ax, ay, lines) in ACTORS.items():
        w = max(100, int(max(len(l) for l in lines) * 8.2) + 10)
        h = 70 + 18 * len(lines)
        d.add("UMLActor", ax * S - w / 2, (ay - 42) * S + DY, w, h, "\n".join(lines))

    def actor_pt(name, tx):
        ax, ay, _ = ACTORS[name]
        return (ax + (16 if tx > ax else -16), ay - 12)

    for act, u in ASSOC:
        ux, uy, _ = UC[u]
        ax, ay = actor_pt(act, ux)
        if ax < ux and ux == C1:
            ex, ey = ux - EW, uy
        else:
            ex, ey = ell_point(ux, uy, ax, ay)
        d.relation([(ax * S, ay * S + DY), (ex * S, ey * S + DY)], "lt=-")

    def between(a, b):
        ax, ay, _ = UC[a]
        bx, by, _ = UC[b]
        p = ell_point(ax, ay, bx, by)
        q = ell_point(bx, by, ax, ay)
        return (p[0] * S, p[1] * S + DY), (q[0] * S, q[1] * S + DY)

    for a, b in INCLUDE:
        p, q = between(a, b)
        d.relation([p, q], "lt=.>\n«include»")
    for a, b, cond in EXTEND:
        p, q = between(a, b)
        d.relation([p, q], f"lt=.>\n«extend»\n{cond}")
    for child, parent in GENERAL:
        p, q = between(child, parent)
        d.relation([q, p], "lt=<<-")

    d.write(os.path.join(UXF, "usecase.uxf"))
    print("usecase.uxf  (%d use cases, %d actors)" % (len(UC), len(ACTORS)))


if __name__ == "__main__":
    main()
