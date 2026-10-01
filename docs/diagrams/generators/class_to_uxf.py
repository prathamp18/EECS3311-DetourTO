"""Generates UMLet class diagrams (.uxf) from the single PlantUML class model.

Input:  src/model.iuml plus every src/class-*.puml and src/pattern-*.puml view.
        A view keeps the same `remove * / restore $tag / restore Name /
        hide Name members / note` lines PlantUML uses, so the PlantUML and
        UMLet versions of a view show exactly the same classes.
Layout: Graphviz `dot` (box sizes are what UMLet needs for the text).
Output: uxf/<view>.uxf

Usage:  python3 class_to_uxf.py
"""
import glob
import os
import re
import subprocess

from uxf_common import Diagram, SRC, UXF, text_box, guillemets, snap

CLASS_RE = re.compile(r'^(abstract class|class|interface|enum)\s+(\w+)\s+\$(\w+)(?:\s+<<(.+?)>>)?\s*\{?\s*$')
REL_RE = re.compile(
    r'^(\w+)\s*(?:"([^"]+)")?\s*(<\|--|<\|\.\.|\*-->|\*--|o-->|o--|-->|\.\.>)\s*(?:"([^"]+)")?\s*(\w+)\s*(?::\s*(.+))?$')


def parse_model(path):
    classes, rels = {}, []
    lines = open(path, encoding="utf-8").read().splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        m = CLASS_RE.match(line)
        if m:
            kind, name, tag, stereo = m.groups()
            body = []
            if line.endswith("{"):
                i += 1
                while lines[i].strip() != "}":
                    if lines[i].strip():
                        body.append(lines[i].strip())
                    i += 1
            classes[name] = dict(kind=kind, name=name, tag=tag, stereo=stereo, body=body)
        else:
            r = REL_RE.match(line)
            if r:
                a, ma, op, mb, b, label = r.groups()
                rels.append(dict(a=a, ma=ma, op=op, mb=mb, b=b, label=label))
        i += 1
    return classes, rels


def parse_view(path):
    title, restores, removes, hidden, notes, links = "", [], set(), set(), {}, []
    lines = open(path, encoding="utf-8").read().splitlines()
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if s.startswith("title "):
            title = s[6:]
        elif s.startswith("restore "):
            restores.append(s.split()[1])
        elif s.startswith("remove ") and s != "remove *":
            removes.add(s.split()[1])
        elif s.startswith("hide ") and s.endswith(" members"):
            hidden.add(s.split()[1])
        elif s.startswith("note \""):
            m = re.match(r'note "(.*)" as (\w+)', s)
            notes[m.group(2)] = m.group(1).replace("\\n", "\n")
        elif s.startswith("note as "):
            nid = s.split()[2]
            body = []
            i += 1
            while lines[i].strip() != "end note":
                body.append(re.sub(r"</?b>", "", lines[i]))
                i += 1
            notes[nid] = "\n".join(body)
        elif re.match(r"^N\d+ \.\. \w+$", s):
            n, _, target = s.split()
            links.append((n, target))
        i += 1
    return title, restores, removes, hidden, notes, links


def class_panel(c, compact):
    head = []
    if c["kind"] == "interface":
        head = ["«interface»", f"/{c['name']}/"]
    elif c["kind"] == "enum":
        head = ["«enumeration»", c["name"]]
    elif c["kind"] == "abstract class":
        head = ([f"«{c['stereo']}»"] if c["stereo"] else []) + [f"/{c['name']}/"]
    else:
        head = ([guillemets(f"«{c['stereo']}»")] if c["stereo"] else []) + [c["name"]]
    head = [guillemets(h) for h in head]
    if compact:
        return head, 0
    if c["kind"] == "enum":
        return head + ["--"] + c["body"], 1
    attrs, ops = [], []
    for m in c["body"]:
        txt = m
        is_op = "(" in m
        if "{abstract}" in m:
            txt = "/" + m.replace("{abstract} ", "").replace("{abstract}", "") + "/"
        if "{static}" in m:
            txt = "_" + m.replace("{static} ", "").replace("{static}", "") + "_"
        (ops if is_op else attrs).append(txt)
    return head + ["--"] + attrs + ["--"] + ops, 2


# UMLet line types; the first relation point carries the arrowhead/diamond.
LT = {
    "<|--": ("lt=<<-", "a"),        # a is the parent: triangle at a
    "<|..": ("lt=<<.", "a"),        # a is the interface
    "-->": ("lt=<-", "b"),          # directed association: arrow at b
    "..>": ("lt=<.", "b"),          # dependency: open arrow at b
    "*--": ("lt=<<<<<-", "a"),      # composition: filled diamond at a (whole)
    "*-->": ("lt=<<<<<-", "a"),
    "o--": ("lt=<<<<-", "a"),       # aggregation: hollow diamond at a (whole)
    "o-->": ("lt=<<<<-", "a"),
}


def layout(nodes, edges):
    """nodes: {id: (w, h)}; edges: [(tail, head, attrs)] -> positions, edge polylines."""
    dot = ["digraph G {", 'graph [rankdir=TB, nodesep=0.55, ranksep=0.9, splines=ortho, margin=0];',
           'node [shape=box, fixedsize=true, label=""];', 'edge [arrowhead=none, arrowtail=none];']
    for n, (w, h) in nodes.items():
        dot.append(f'"{n}" [width={w / 72:.3f}, height={h / 72:.3f}];')
    for k, (t, hd, extra) in enumerate(edges):
        dot.append(f'"{t}" -> "{hd}" [id="e{k}"{extra}];')
    dot.append("}")
    import json
    src = "\n".join(dot)
    if len(nodes) > 14:
        # stagger long rows of leaf classes so wide layers wrap onto extra ranks
        src = subprocess.run(["unflatten", "-l", "4", "-f", "-c", "5"], input=src,
                             capture_output=True, text=True, check=True).stdout
    try:
        out = subprocess.run(["dot", "-Tjson"], input=src, capture_output=True, text=True, check=True).stdout
    except subprocess.CalledProcessError:
        # Graphviz's orthogonal router occasionally aborts; fall back to straight polylines
        out = subprocess.run(["dot", "-Tjson"], input=src.replace("splines=ortho", "splines=polyline"),
                             capture_output=True, text=True, check=True).stdout
    g = json.loads(out)
    gh = float(g["bb"].split(",")[3])
    pos, by_pair = {}, {}
    names = {}
    for o in g.get("objects", []):
        x, y = map(float, o["pos"].split(","))
        pos[o["name"]] = (x, gh - y)
        names[o["_gvid"]] = o["name"]
    for e in sorted(g.get("edges", []), key=lambda e: e["_gvid"]):
        pts = []
        for tok in e["pos"].split():
            if tok[:2] in ("s,", "e,"):
                continue
            x, y = map(float, tok.split(","))
            pts.append((x, gh - y))
        n = len(pts)
        corners = [pts[0]] + [pts[j] for j in range(3, n - 1, 3)] + [pts[-1]]
        clean = []
        for c in corners:
            if not clean or abs(c[0] - clean[-1][0]) > 0.5 or abs(c[1] - clean[-1][1]) > 0.5:
                clean.append(c)
        tail, head = names[e["tail"]], names[e["head"]]
        def d(p, n):  # distance from point p to the border box of node n
            (cx, cy), (w, h) = pos[n], nodes[n]
            dx = max(abs(p[0] - cx) - w / 2, 0)
            dy = max(abs(p[1] - cy) - h / 2, 0)
            return dx * dx + dy * dy
        if d(clean[0], head) < d(clean[0], tail):
            clean = clean[::-1]          # ortho routes can come back head-first
        by_pair.setdefault((tail, head), []).append(clean)
    # dot does not keep input order for edges, so match them back by endpoints
    polys = [by_pair[(t, h)].pop(0) for t, h, _ in edges]
    return pos, polys


def build_view(view_path, classes, rels):
    title, restores, removes, hidden, notes, links = parse_view(view_path)
    visible = []
    for r in restores:
        if r.startswith("$"):
            visible += [n for n, c in classes.items() if c["tag"] == r[1:]]
        elif r in classes:
            visible.append(r)
    visible = [v for v in dict.fromkeys(visible) if v not in removes]
    vis = set(visible)

    nodes, panels = {}, {}
    for n in visible:
        lines, seps = class_panel(classes[n], n in hidden)
        body = [l for l in lines if l != "--"]
        w, h = text_box(body, seps, min_w=110)
        nodes[n], panels[n] = (w, h), "\n".join(lines)
    for nid, text in notes.items():
        nodes[nid] = text_box(text.split("\n"), 0, min_w=120)
        panels[nid] = text

    edges, meta = [], []
    for r in rels:
        if r["a"] in vis and r["b"] in vis:
            weight = ', weight=3' if r["op"].startswith("<|") else ''
            edges.append((r["a"], r["b"], weight))
            meta.append(("rel", r))
    for n, t in links:
        if t in vis:
            edges.append((n, t, ", weight=0"))
            meta.append(("note", None))

    pos, polys = layout(nodes, edges)
    d = Diagram()
    top = 60
    d.add("Text", 10, 10, 1200, 40, f"fontsize=16\n*{title}*")
    for n, (w, h) in nodes.items():
        cx, cy = pos[n]
        kind = "UMLNote" if n in notes else "UMLClass"
        d.add(kind, cx - w / 2 + 20, cy - h / 2 + top, w, h, panels[n])
    for (k, r), poly in zip(meta, polys):
        pts = [(x + 20, y + top) for x, y in poly]
        if k == "note":
            d.relation(pts, "lt=.")
            continue
        lt, head_end = LT[r["op"]]
        # dot draws tail=a -> head=b; flip when the arrowhead belongs at b
        if head_end == "b":
            pts = pts[::-1]
            m1, m2 = r["mb"], r["ma"]
        else:
            m1, m2 = r["ma"], r["mb"]
        attrs = [lt]
        if m1:
            attrs.append(f"m1={m1}")
        if m2:
            attrs.append(f"m2={m2}")
        if r["label"]:
            attrs.append(guillemets(r["label"]))
        d.relation(pts, "\n".join(attrs))
    name = os.path.splitext(os.path.basename(view_path))[0]
    d.write(os.path.join(UXF, name + ".uxf"))
    return name, len(visible)


def main():
    classes, rels = parse_model(os.path.join(SRC, "model.iuml"))
    views = sorted(glob.glob(os.path.join(SRC, "class-*.puml")) + glob.glob(os.path.join(SRC, "pattern-*.puml")))
    for v in views:
        name, n = build_view(v, classes, rels)
        print(f"{name}.uxf  ({n} classes)")
    print(f"model: {len(classes)} classifiers, {len(rels)} relationships")


if __name__ == "__main__":
    main()
