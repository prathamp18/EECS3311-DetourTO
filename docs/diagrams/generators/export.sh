#!/usr/bin/env bash
# Opens every .uxf with UMLet 15.1 (headless) and exports it.
#   uxf/<name>.uxf  ->  svg/<name>.svg  (UMLet's own SVG export)
#                   ->  png/<name>.png  (that SVG rasterised at 2x for crisp images in the report)
# Needs: UMLet 15.1 (set UMLET_CP to its classpath, or UMLET_JAR to umlet-standalone.jar), Python 3 + cairosvg.
set -euo pipefail
cd "$(dirname "$0")/.."
CP="${UMLET_CP:-${UMLET_JAR:-umlet-standalone.jar}}"
for f in ${@:-uxf/*.uxf}; do
  for g in $f; do
    java -Djava.awt.headless=true -cp "$CP" com.baselet.standalone.MainStandalone \
      -action=convert -format=svg -filename="$g" >/dev/null
    mv "${g%.uxf}.svg" "svg/$(basename "${g%.uxf}").svg"
  done
done
python3 - <<'PY'
import glob, os, cairosvg
for s in sorted(glob.glob("svg/*.svg")):
    name = os.path.splitext(os.path.basename(s))[0]
    if os.path.exists(f"uxf/{name}.uxf"):
        import re
        head = open(s, encoding="utf-8").read(2000)
        width = float(re.search(r'width="([0-9.]+)"', head).group(1))
        scale = min(2.0, 7000 / width)   # 2x for crispness, capped so very wide diagrams stay a sane size
        cairosvg.svg2png(url=s, write_to=f"png/{name}.png", scale=scale, background_color="white")
        print("exported", name)
PY
