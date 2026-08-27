#!/usr/bin/env bash
set -euo pipefail

notices="${1:-target/generated-sources/license/THIRD-PARTY-NOTICES.txt}"
licenses_xml="${2:-target/generated-resources/licenses.xml}"
licenses_dir="${3:-target/generated-resources/licenses}"

for path in "$notices" "$licenses_xml" "$licenses_dir"; do
  if [[ ! -e "$path" ]]; then
    echo "Missing generated license artifact: $path" >&2
    exit 1
  fi
done

if [[ ! -s "$notices" ]]; then
  echo "Generated third-party notices are empty: $notices" >&2
  exit 1
fi

if [[ ! -s "$licenses_xml" ]]; then
  echo "Generated dependency license summary is empty: $licenses_xml" >&2
  exit 1
fi

if ! find "$licenses_dir" -type f -size +0c -print -quit | grep -q .; then
  echo "No dependency license texts were downloaded into $licenses_dir" >&2
  exit 1
fi

# Strong/reciprocal copyleft is not expected in the shipped runtime dependency graph.
# Fail closed if Maven metadata reports one so it gets an explicit review instead of
# silently becoming part of a native binary/container release.
if grep -Eiq '(GNU (Affero )?General Public License|AGPL|LGPL|GPL[- v0-9.]|Mozilla Public License|MPL[- v0-9.])' \
    "$notices" "$licenses_xml"; then
  echo "Unexpected copyleft license detected in runtime dependencies:" >&2
  grep -Ein '(GNU (Affero )?General Public License|AGPL|LGPL|GPL[- v0-9.]|Mozilla Public License|MPL[- v0-9.])' \
    "$notices" "$licenses_xml" >&2 || true
  exit 1
fi
