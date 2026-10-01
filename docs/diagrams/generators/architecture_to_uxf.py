"""Generates the UMLet layered-architecture overview (uxf/architecture.uxf).

Same content as src/architecture.puml: five packages with their key classes,
the external systems, and dependency arrows between layers.

Usage:  python3 architecture_to_uxf.py
"""
import os

from uxf_common import Diagram, UXF, text_box

BOX_H, GAP = 60, 20


def package(d, name, x, y, classes, cols):
    """Draws a UML package with name-only class boxes laid out in a grid; returns their rectangles."""
    widths = [text_box([c], 0, 150)[0] for c in classes]
    cw = max(widths)
    rows = (len(classes) + cols - 1) // cols
    w = cols * cw + (cols + 1) * GAP
    h = 40 + rows * BOX_H + (rows + 1) * GAP
    rects = {}
    for i, c in enumerate(classes):
        cx = x + GAP + (i % cols) * (cw + GAP)
        cy = y + 40 + GAP + (i // cols) * (BOX_H + GAP)
        d.add("UMLClass", cx, cy, cw, BOX_H - 10, c)
        rects[c.split("\n")[-1]] = (cx, cy, cw, BOX_H - 10)
    # UMLet draws earlier elements on top, so the package goes after its contents
    d.add("UMLPackage", x, y, w, h, f"{name}\n--\nbg=white")
    rects[name] = (x, y, w, h)
    return rects


def bottom(r, f=0.5):
    x, y, w, h = r
    return (x + w * f, y + h)


def top(r, f=0.5):
    x, y, w, h = r
    return (x + w * f, y)


def left(r, f=0.5):
    x, y, w, h = r
    return (x, y + h * f)


def right(r, f=0.5):
    x, y, w, h = r
    return (x + w, y + h * f)


def dep(d, a, b, label, via=None):
    pts = [b] + (via or []) + [a]  # arrowhead at b (first point)
    d.relation(pts, "lt=<.\n" + label)


def main():
    d = Diagram()
    d.add("Text", 10, 0, 800, 40, "fontsize=16\n*DetourTO — Layered Architecture (package overview)*")
    R = {}
    R.update(package(d, "Presentation", 300, 210,
                     ["DetourCli", "BasePanel + 6 panels", "MainWindow"], 3))
    R.update(package(d, "Application", 40, 420,
                     ["«Facade»\nTripController", "EventBus", "CommandHistory", "TripMonitorService",
                      "CommuteScheduler", "AlertService", "BenchmarkRunner"], 3))
    R.update(package(d, "Infrastructure", 860, 420,
                     ["RealtimeFeed (+ decorators)", "GtfsStaticLoader", "SQLite repositories"], 3))
    R.update(package(d, "Agent", 40, 800,
                     ["TransitAgent + 4 agents", "ToolRegistry", "LLMClient", "GroundingValidator"], 2))
    R.update(package(d, "Domain", 560, 1080,
                     ["JourneyPlanner", "TransitNetwork", "RouteScoringStrategy", "Itinerary", "Disruption"], 3))
    ext = {
        "KUMA": ("KUMA harness\n(Python, Stage 3)", 360, 40),
        "BR": ("«external system»\nAmazon Bedrock (Claude)", 120, 1330),
        "RT": ("«external system»\nTTC GTFS-Realtime\nbustime.ttc.ca/gtfsrt", 880, 700),
        "OD": ("«external system»\nCity of Toronto Open Data\nTTC GTFS schedule zip", 1180, 700),
    }
    for k, (txt, x, y) in ext.items():
        lines = txt.split("\n")
        w, h = text_box(lines, 0, 200)
        d.add("UMLClass", x, y, w, h, txt)
        R[k] = (x, y, w, h)

    dep(d, bottom(R["KUMA"], 0.4), top(R["DetourCli"]), "subprocess: detour ... --json\n+ trace.jsonl")
    dep(d, bottom(R["Presentation"], 0.3), top(R["Application"], 0.75), "calls facade /\nsubscribes to events")
    dep(d, bottom(R["Application"], 0.3), top(R["Agent"], 0.3), "runs agents")
    a, b = bottom(R["Application"], 0.8), top(R["Domain"], 0.2)
    dep(d, a, b, "plans journeys", via=[(b[0], a[1] + 330)])
    a, b = right(R["Agent"], 0.75), left(R["Domain"], 0.4)
    dep(d, a, b, "tools call planner,\nvalidator reads network")
    a, b = right(R["Application"], 0.3), left(R["Infrastructure"], 0.5)
    dep(d, a, b, "reads feeds,\nsaves profile")
    dep(d, bottom(R["LLMClient"]), top(R["BR"], 0.3), "Converse API (HTTPS)")
    dep(d, bottom(R["RealtimeFeed (+ decorators)"]), top(R["RT"], 0.4), "HTTPS (protobuf)")
    dep(d, bottom(R["GtfsStaticLoader"]), top(R["OD"], 0.4), "download zip")

    d.write(os.path.join(UXF, "architecture.uxf"))
    print("architecture.uxf")


if __name__ == "__main__":
    main()
