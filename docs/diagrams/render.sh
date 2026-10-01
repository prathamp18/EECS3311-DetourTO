#!/usr/bin/env bash
# Regenerates every diagram: sources (src/) -> UMLet files (uxf/) -> UMLet exports (svg/, png/).
# See generators/README.md for what each step does and what it needs.
exec "$(dirname "$0")/generators/generate_all.sh"
