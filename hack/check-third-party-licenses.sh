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

# Runtime dependencies are expected to be permissive or weakly reciprocal.
# Jakarta / Eclipse artifacts often declare EPL-2.0 together with GPL-2.0 +
# Classpath Exception as alternative licenses; those are acceptable here.
# Fail only on licenses that would need an explicit compatibility decision.
problematic='(GNU Affero General Public License|AGPL|GNU Lesser General Public License|LGPL|Server Side Public License|SSPL|Commons Clause)'
if grep -Eiq "$problematic" "$notices" "$licenses_xml"; then
  echo "Unexpected runtime dependency license detected:" >&2
  grep -Ein "$problematic" "$notices" "$licenses_xml" >&2 || true
  exit 1
fi

# Plain GPL without the Classpath Exception is not expected. Check notice lines
# dependency-by-dependency so alternative EPL-2.0 + GPL-with-CPE metadata passes.
if grep -Ei '(^|[^A-Za-z])GPL|GNU General Public License' "$notices" \
    | grep -Eiv 'Classpath Exception|CPE|Eclipse Public License|EPL[- .]?2\.0' >/tmp/unexpected-gpl.txt; then
  echo "Unexpected GPL runtime dependency without an approved alternative/exception:" >&2
  cat /tmp/unexpected-gpl.txt >&2
  exit 1
fi
