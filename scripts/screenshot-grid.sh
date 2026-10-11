#!/bin/sh
# Lays out the screenshots of the coverage matrix (UI specification part 4) as one HTML page, so
# every state can be checked on every window size, theme, and language at a glance.
#
# Usage: scripts/screenshot-grid.sh
# Output: build/screenshot-grid.html, next to the build output, which Git ignores.
#
# The images are renovo-ui/src/test/screenshots/matrix_<state>_<width>_<theme>_<language>.png.
# Record them first with: ./gradlew :renovo-ui:recordRoborazziDebug

set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
shots="$root/renovo-ui/src/test/screenshots"
out="$root/build/screenshot-grid.html"
# The page lives in build/, so the images are one level up.
rel="../renovo-ui/src/test/screenshots"

mkdir -p "$root/build"

# The file name part and the heading of each state, in the order of the coverage table.
states="
update_available|1. Update available
downloading|2. Downloading
ready_to_install|3. Ready to install
permission|4. First-time permission
update_failed|5. Update not installed
forced_waiting_for_user|6. Forced update, waiting for the user
forced_downloading|7. Forced update, downloading
forced_waiting_for_network|8. Forced update, waiting for a network
forced_installing|9. Forced update, installing
forced_failed|10. Forced update, failed
forced_permission_needed|11. Forced update, permission needed
forced_unsupported|12. Forced update, unsupported device
"

# One cell: the image scaled to the width of its column. A missing file is written into the page
# and counted at the end, so a gap in the matrix cannot pass unnoticed.
cell() {
  file="matrix_$1_$2_$3_$4.png"
  if [ -f "$shots/$file" ]; then
    printf '<td><a href="%s/%s"><img src="%s/%s" alt="%s" loading="lazy"></a></td>\n' "$rel" "$file" "$rel" "$file" "$file"
  else
    printf '<td class="none">missing: %s</td>\n' "$file"
  fi
}

{
  cat <<'HEAD'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Renovo screenshot grid</title>
<style>
  body { font-family: sans-serif; margin: 16px; background: #fafafa; color: #1c1b1f; }
  h1 { font-size: 1.4em; }
  h2 { font-size: 1.1em; margin-top: 2em; }
  table { border-collapse: collapse; table-layout: fixed; width: 100%; background: #fff; }
  th, td { border: 1px solid #c9c5ca; padding: 4px; vertical-align: top; text-align: center; }
  th { background: #ece6f0; font-weight: normal; }
  th.row { width: 7%; }
  th.compact { width: 17%; }
  th.medium { width: 28%; }
  th.expanded { width: 36%; }
  th.landscape { width: 12%; }
  img { width: 100%; height: auto; display: block; }
  .none { color: #888; }
</style>
</head>
<body>
<h1>Renovo screenshot grid</h1>
<p>Each table is one state. Rows are the theme and the language. Columns are the window sizes:
compact 390 x 844 dp, medium 700 x 960 dp, expanded 1000 x 640 dp, and compact landscape 844 x 390 dp
(light theme and English only). Click an image to see it at full size.</p>
HEAD

  echo "$states" | while IFS='|' read -r state title; do
    [ -n "$state" ] || continue
    echo "<h2>$title</h2>"
    echo '<table>'
    echo '<tr><th class="row"></th><th class="compact">Compact</th><th class="medium">Medium</th><th class="expanded">Expanded</th><th class="landscape">Compact landscape</th></tr>'
    for theme in light dark; do
      for language in en az; do
        echo "<tr><th>$theme, $language</th>"
        for width in compact medium expanded; do
          cell "$state" "$width" "$theme" "$language"
        done
        if [ "$theme" = light ] && [ "$language" = en ]; then
          cell "$state" compact_landscape light en
        else
          echo '<td class="none">-</td>'
        fi
        echo '</tr>'
      done
    done
    echo '</table>'
  done

  echo '</body>'
  echo '</html>'
} > "$out"

images=$(grep -c '<img ' "$out" || true)
absent=$(grep -c 'missing:' "$out" || true)
echo "Wrote $out"
echo "Images referenced: $images, missing: $absent"
[ "$absent" -eq 0 ] || exit 1
