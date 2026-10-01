#!/usr/bin/env bash
# Regenerates every UMLet diagram from the sources, then exports them with UMLet.
set -euo pipefail
cd "$(dirname "$0")"
python3 class_to_uxf.py          # 6 class views + 9 pattern views from src/model.iuml
python3 sequence_to_uxf.py       # 11 sequence diagrams from src/SD*.puml
python3 usecase_to_uxf.py        # use case diagram from src/usecase_layout.py
python3 architecture_to_uxf.py   # layered architecture overview
./export.sh                      # UMLet: uxf -> svg -> png
