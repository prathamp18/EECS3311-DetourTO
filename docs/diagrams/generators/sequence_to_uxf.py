"""Converts the PlantUML sequence diagrams (src/SD*.puml) into UMLet
"UML Sequence - All in one" elements (uxf/SD*.uxf).

Mapping (PlantUML -> UMLet all-in-one syntax):
  actor "X" as A                -> obj=X~A ACTOR
  participant "__:C__" as P     -> obj=_:C_~P   (underlined lifeline name, as in the slides)
  A -> B : m(x)                 -> A->>>B : m(x)     synchronous call, filled arrowhead
  A --> B : r                   -> A.>B : r          return, dashed open arrowhead
  A -> A : m()                  -> A->A + : m()      self call
  A -> B ** : <<create>>        -> B is CREATED_LATER, A->>>B : «create»
  activate / deactivate X       -> on=X / off=X
  loop / alt / else / opt / end -> combinedFragment / .. / --
  == Section ==                 -> a named combined fragment around the section
Notes are dropped (UMLet's all-in-one element has no note syntax); the report
text beside each diagram carries that information.

Usage:  python3 sequence_to_uxf.py
"""
import glob
import os
import re

from uxf_common import Diagram, SRC, UXF, guillemets


def convert(path):
    lines = open(path, encoding="utf-8").read().splitlines()
    title, objs, order, created = "", [], [], set()
    body = []                     # (kind, data)
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if s.startswith("title "):
            title = s[6:]
        elif s.startswith("actor ") or s.startswith("participant "):
            m = re.match(r'(actor|participant) "(.*)" as (\w+)', s)
            kind, label, pid = m.groups()
            label = label.replace("__", "_")
            label = label.replace("<<", "«").replace(">>", "»")
            objs.append((pid, label, kind == "actor"))
            order.append(pid)
        elif s.startswith("note "):
            if not (":" in s and s.split(":")[0].strip().startswith("note")):
                while lines[i].strip() != "end note":
                    i += 1
        elif s.startswith("== "):
            body.append(("section", s.strip("= ").strip()))
        elif re.match(r"^(loop|alt|opt)\b", s):
            op, _, guard = s.partition(" ")
            body.append(("frag", (op, guard)))
        elif s.startswith("else"):
            body.append(("else", s[4:].strip()))
        elif s == "end":
            body.append(("end", None))
        elif s.startswith("activate "):
            body.append(("on", s.split()[1]))
        elif s.startswith("deactivate "):
            body.append(("off", s.split()[1]))
        else:
            m = re.match(r"^(\w+)\s*(-->|->)\s*(\w+)\s*(\*\*)?\s*(?::\s*(.*))?$", s)
            if m:
                a, arrow, b, create, text = m.groups()
                text = guillemets((text or "").replace("\\n", " "))
                if create:
                    created.add(b)
                body.append(("msg", (a, arrow, b, text)))
        i += 1

    out = [f"title={title}"]
    for pid, label, is_actor in objs:
        flags = " ACTOR" if is_actor else ""
        flags += " CREATED_LATER" if pid in created else ""
        out.append(f"obj={label}~{pid}{flags}")

    # find the lifeline span of every fragment so the frame covers its messages
    stack, spans, fid = [], {}, 0
    frag_ids = []
    for kind, data in body:
        if kind in ("frag", "section"):
            if kind == "section" and stack and stack[-1][1] == "section":
                stack.pop()
            fid += 1
            stack.append((fid, kind))
            spans[fid] = set()
            frag_ids.append(fid)
        elif kind == "end" and stack:
            stack.pop()
        elif kind == "msg":
            for f, _ in stack:
                spans[f].update({data[0], data[2]})

    def span(f):
        used = [p for p in order if p in spans[f]] or order[:2]
        return used[0], used[-1]

    stack, nxt = [], iter(frag_ids)
    for kind, data in body:
        if kind == "msg":
            a, arrow, b, text = data
            text = text.replace(";", ",")
            if a == b:
                out.append(f"{a}->{b} + : {text}")
            elif arrow == "->":
                out.append(f"{a}->>>{b} : {text}")
            else:
                out.append(f"{a}.>{b} : {text}")
        elif kind == "on":
            out[-1] += f"; on={data}"
        elif kind == "off":
            out[-1] += f"; off={data}"
        elif kind == "section":
            if stack and stack[-1][1] == "section":
                out.append(f"--=f{stack.pop()[0]}")
            f = next(nxt)
            first, last = span(f)
            out.append(f"combinedFragment={data}~f{f} {first} {last}")
            stack.append((f, "section"))
        elif kind == "frag":
            op, guard = data
            f = next(nxt)
            first, last = span(f)
            out.append(f"combinedFragment={op}~f{f} {first} {last}")
            out.append(f"{first}:[{guard}]")
            stack.append((f, op, first))
        elif kind == "else":
            f, _, first = stack[-1]
            out.append(f"..=f{f}")
            out.append(f"{first}:[{data}]")
        elif kind == "end":
            out.append(f"--=f{stack.pop()[0]}")
    while stack:
        out.append(f"--=f{stack.pop()[0]}")

    ticks = sum(1 for l in out if not l.startswith(("obj=", "title=")))
    width = max(1100, 190 * len(objs))
    height = 230 + 34 * ticks
    d = Diagram()
    d.add("UMLSequenceAllInOne", 10, 10, width, height, "\n".join(out))
    name = os.path.splitext(os.path.basename(path))[0]
    d.write(os.path.join(UXF, name + ".uxf"))
    return name, len(objs), ticks


def main():
    for p in sorted(glob.glob(os.path.join(SRC, "SD*.puml"))):
        name, n, t = convert(p)
        print(f"{name}.uxf  ({n} lifelines, {t} steps)")


if __name__ == "__main__":
    main()
