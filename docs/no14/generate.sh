#!/usr/bin/env bash
# Uploads every zip in $1 to the app at $2 and saves the outputs under $3 (same steps as the baseline).
set -u
ZIPS=$1
BASE=$2
OUT=$3
mkdir -p "$OUT"
: > "$OUT/projects.tsv"
for z in "$ZIPS"/*.zip; do
  n=$(basename "$z" .zip)
  d="$OUT/$n"
  mkdir -p "$d/pages"
  loc=$(curl -s -o /dev/null -w '%{redirect_url}' -F "file=@$z;type=application/zip" "$BASE/upload")
  id=${loc##*/projects/}
  printf "%s\t%s\n" "$n" "$id" >> "$OUT/projects.tsv"
  curl -s -o "$d/documents.md" "$BASE/projects/$id/export/documents.md"
  for f in documents.docx basic-design.docx documents.xlsx; do
    curl -s -o "$d/$f" --form-string dummy=1 "$BASE/projects/$id/export/$f"
  done
  curl -s -o "$d/pages/project.html" -w "$n project %{http_code}\n" "$BASE/projects/$id" | grep -v " 200$"
  for p in architecture apis er classes issues mybatis configuration views export; do
    curl -s -o "$d/pages/$p.html" -w "$n $p %{http_code}\n" "$BASE/projects/$id/$p" | grep -v " 200$"
  done
done
cat "$OUT/projects.tsv"
