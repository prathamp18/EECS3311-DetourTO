"""Shared helpers for writing UMLet 15.1 (.uxf) files.

UMLet has no PlantUML import, so every .uxf in docs/diagrams/uxf is written by
these generator scripts from the sources in docs/diagrams/src and then opened
and exported by UMLet itself (see export.sh).
"""
import html
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.normpath(os.path.join(HERE, "..", "src"))
UXF = os.path.normpath(os.path.join(HERE, "..", "uxf"))

# Measured from UMLet 15.1 exports at zoom level 10 (default font).
CHAR_W = 8.0
LINE_H = 16.5
SEP_H = 8.0
PAD_W = 22
PAD_H = 14


def esc(text):
    return html.escape(text, quote=False)


def snap(v, grid=10):
    return int(math.ceil(v / grid) * grid)


def text_box(lines, separators=0, min_w=80):
    """Width and height UMLet needs to show `lines` without clipping."""
    longest = max((len(l) for l in lines), default=4)
    w = max(min_w, snap(longest * CHAR_W + PAD_W))
    h = snap(len(lines) * LINE_H + separators * SEP_H + PAD_H)
    return w, h


class Diagram:
    def __init__(self):
        self.elements = []

    def add(self, kind, x, y, w, h, attrs, additional=""):
        self.elements.append((kind, int(round(x)), int(round(y)), int(round(w)), int(round(h)), attrs, additional))

    def relation(self, points, attrs):
        """points: absolute (x, y) list; the first point is where the arrowhead/diamond is drawn."""
        pad = 10
        xs = [p[0] for p in points]
        ys = [p[1] for p in points]
        x0, y0 = min(xs) - pad, min(ys) - pad
        w = max(xs) - min(xs) + 2 * pad
        h = max(ys) - min(ys) + 2 * pad
        rel = ";".join(f"{px - x0:.1f};{py - y0:.1f}" for px, py in points)
        self.add("Relation", x0, y0, w, h, attrs, rel)

    def write(self, path):
        out = ['<?xml version="1.0" encoding="UTF-8" standalone="no"?>',
               '<diagram program="umlet" version="15.1">',
               "  <zoom_level>10</zoom_level>"]
        for kind, x, y, w, h, attrs, add in self.elements:
            out.append("  <element>")
            out.append(f"    <id>{kind}</id>")
            out.append(f"    <coordinates><x>{x}</x><y>{y}</y><w>{w}</w><h>{h}</h></coordinates>")
            out.append(f"    <panel_attributes>{esc(attrs)}</panel_attributes>")
            out.append(f"    <additional_attributes>{add}</additional_attributes>" if add else "    <additional_attributes/>")
            out.append("  </element>")
        out.append("</diagram>")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(out) + "\n")


def guillemets(s):
    return s.replace("<<", "«").replace(">>", "»")
