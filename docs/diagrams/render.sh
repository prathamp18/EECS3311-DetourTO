#!/usr/bin/env bash
# Regenerates every diagram image from its source.
# Needs: Java 17+, Graphviz (dot), PlantUML 1.2025+ (set PLANTUML_JAR), Python 3 + cairosvg.
set -euo pipefail
cd "$(dirname "$0")/src"
PLANTUML_JAR="${PLANTUML_JAR:-plantuml.jar}"
export PLANTUML_LIMIT_SIZE=16384
java -jar "$PLANTUML_JAR" -tpng -o ../png architecture.puml class-*.puml pattern-*.puml SD*.puml
python3 usecase_layout.py
echo "Diagrams written to docs/diagrams/png"
