# Diagrams

Every diagram is delivered in UMLet format plus UMLet's own export, all sharing one name:

| Folder | Contents |
|---|---|
| `uxf/` | **UMLet 15.1 files** (28). Open them in UMLet or at [umletino.com](https://www.umletino.com/umletino.html). |
| `svg/` | UMLet's SVG export of each `.uxf` |
| `png/` | that SVG rasterised at 2x: the images the report displays |
| `src/` | the sources the `.uxf` files are generated from (build input): `model.iuml` is the single class model, `class-*.puml` / `pattern-*.puml` are views cut from it, `SD*.puml` are the sequence diagrams, `usecase_layout.py` holds the use case layout |
| `generators/` | the scripts that turn those sources into UMLet XML and run UMLet's exporter (see `generators/README.md`) |

So `uxf/class-domain.uxf`, `svg/class-domain.svg` and `png/class-domain.png` are the same diagram.

| Diagram | Name |
|---|---|
| Layered architecture overview | `architecture` |
| Class diagram views (presentation, application, agent core, agent tools, domain, infrastructure) | `class-*` |
| One class diagram per design pattern (9) | `pattern-*` |
| Use case diagram | `usecase` |
| Sequence diagrams SD01–SD11 | `SD01-…` to `SD11-…` |

Every `.uxf` was opened and exported with UMLet 15.1 (headless, `-action=convert`) to confirm it loads without errors; those exports are the committed SVG and PNG files. Regenerate everything with `./render.sh`.

Notation follows the EECS 3311 UML slides: three-compartment classes with `+ - #` visibility, «interface» and italic abstract names, underlined static members, hollow-triangle inheritance and realization, filled and hollow diamonds for composition and aggregation, dashed «use»/«create» dependencies, multiplicities on association ends; stick-figure actors, a system boundary and «include»/«extend» for use cases; underlined `:Class` lifelines, activation bars, dashed returns and loop/alt/opt frames for sequence diagrams.
