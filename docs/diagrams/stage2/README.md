# Stage 2 class diagrams (as implemented)

Generated from the Stage 2 source code with Graphviz, so they match the implementation exactly.

- `stage2-class-overview`: the key classes and the 9 design patterns
- one diagram per layer: `presentation` (CLI), `presentation-gui`, `application`, `agent`, `domain`, `infrastructure`

Notation:

- hollow triangle with a solid line: `extends`
- hollow triangle with a dashed line: `implements`
- plain arrow: an association from a field, labelled with its multiplicity

Each class lists up to 6 fields and its public methods.

The `.dot` files are the sources. To regenerate one:

```
unflatten -l 4 -f -c 5 X.dot | dot -Tpng -o X.png
```

The Stage 1 UMLet diagrams are kept in `../uxf`, `../svg` and `../png`.
