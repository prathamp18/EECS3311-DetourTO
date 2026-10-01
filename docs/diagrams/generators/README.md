# Generators

UMLet has no PlantUML import, so the `.uxf` files in `../uxf` are written by these scripts from the sources in `../src`, then opened and exported by UMLet 15.1 itself. Nothing in `uxf/`, `svg/` or `png/` is drawn by hand.

| Script | Input | Output |
|---|---|---|
| `class_to_uxf.py` | `src/model.iuml` + every `src/class-*.puml` and `src/pattern-*.puml` view | 6 class views + 9 pattern views. The script keeps the same classes a view's `restore` / `remove` / `hide` lines keep, lays them out with Graphviz `dot`, and sizes each box to its text. |
| `sequence_to_uxf.py` | `src/SD*.puml` | 11 sequence diagrams as UMLet "Sequence – All in one" elements (lifelines, activation bars, calls, returns, `«create»`, loop/alt/opt frames). |
| `usecase_to_uxf.py` | `src/usecase_layout.py` (hand-made positions) | the use case diagram |
| `architecture_to_uxf.py` | (layout in the script) | the layered architecture overview |
| `export.sh` | `uxf/*.uxf` | UMLet's SVG export in `svg/`, and that SVG rasterised at 2x into `png/` (the images the report shows) |
| `generate_all.sh` | — | runs all of the above |

## Running

Needs Python 3 with `cairosvg`, Graphviz (`dot`, `unflatten`) and UMLet 15.1.

```bash
# with the UMLet standalone download (umlet.jar + lib/ unzipped in ~/umlet):
export UMLET_CP="$HOME/umlet/umlet.jar:$HOME/umlet/lib/*"
./generate_all.sh
```

To edit a diagram, change its source in `src/` and rerun. Hand edits made in UMLet are overwritten the next time the generators run, so use one workflow or the other, not both.
