# Diagrams

| Folder | Contents |
|---|---|
| `src/` | Sources. `model.iuml` is the single class model; `class-*.puml` and `pattern-*.puml` are views cut from it; `SD*.puml` are sequence diagrams; `usecase_layout.py` draws the use case diagram with a fixed layout. |
| `png/` | Rendered images used by the report. |
| `svg/` | Vector copy of the use case diagram. |

Regenerate with `./render.sh` (needs Java 17+, Graphviz, PlantUML 1.2025+ via `PLANTUML_JAR`, and Python 3 with `cairosvg`).

Notation follows the EECS 3311 UML slides: three-compartment classes with `+ - #` visibility, «interface» and italic abstract names, hollow-triangle inheritance and realization, filled and hollow diamonds for composition and aggregation, dashed «use»/«create» dependencies, multiplicities on association ends; stick-figure actors, a system boundary and «include»/«extend» for use cases; underlined `:Class` lifelines, activation bars, dashed returns and loop/alt/opt frames for sequence diagrams.
