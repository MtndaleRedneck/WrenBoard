#!/usr/bin/env bash
# Artifact gate for the parity workflow. Fail-closed by construction:
#   filter_artifacts.sh SRC DEST
# SRC is the raw output directory and is never uploaded. DEST must not exist yet; it is the ONLY
# directory any upload step points at. Allowlisted regular files (png jpg mp4 webm txt log json xml)
# are checked first; if every one passes, they are copied into a private staging directory which is
# then renamed to DEST. On any violation (a zip container under any name, i.e. an APK in disguise, or
# a file over 50 MB) the script exits 1 and DEST is never created, so nothing can be uploaded even if
# a later step ran. The workflow additionally gates every upload on this step's outcome == success.
set -euo pipefail
SRC=${1:?usage: filter_artifacts.sh SRC DEST}
DEST=${2:?usage: filter_artifacts.sh SRC DEST}
MAX=52428800
[ -d "$SRC" ] || { echo "no output directory $SRC" >&2; exit 1; }
[ ! -e "$DEST" ] || { echo "upload directory $DEST already exists; refusing" >&2; exit 1; }
bad=0
kept=()
while IFS= read -r -d '' f; do
  rel=${f#"$SRC"/}
  if [ -L "$f" ] || [ ! -f "$f" ]; then echo "excluded (not a regular file): $rel"; continue; fi
  case "${f,,}" in
    *.png|*.jpg|*.mp4|*.webm|*.txt|*.log|*.json|*.xml) ;;
    *) echo "excluded (type not allowed): $rel"; continue ;;
  esac
  if [ "$(head -c 2 "$f" | od -An -c | tr -d ' \n')" = "PK" ]; then echo "VIOLATION zip container: $rel" >&2; bad=1; continue; fi
  if [ "$(stat -c%s "$f")" -gt "$MAX" ]; then echo "VIOLATION too large: $rel" >&2; bad=1; continue; fi
  kept+=("$rel")
done < <(find "$SRC" -mindepth 1 \( -type f -o -type l \) -print0)
if [ "$bad" -ne 0 ]; then echo "artifact gate FAILED: nothing staged for upload" >&2; exit 1; fi
[ "${#kept[@]}" -gt 0 ] || { echo "artifact gate FAILED: no allowed files" >&2; exit 1; }
STAGE=$(mktemp -d "$(dirname "$DEST")/.stage.XXXXXX")
for rel in "${kept[@]}"; do
  mkdir -p "$STAGE/$(dirname "$rel")"
  cp --no-dereference "$SRC/$rel" "$STAGE/$rel"
done
mv -T "$STAGE" "$DEST"
echo "ARTIFACT FILES: ${#kept[@]}"
find "$DEST" -type f -printf '%s\t%P\n' | sort -k2
