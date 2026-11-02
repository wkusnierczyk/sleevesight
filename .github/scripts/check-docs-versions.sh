#!/usr/bin/env bash
# Checks that every documentation page names the release it was last brought up to (#828;
# `design/release.md`, *Documentation versions*): each Markdown or HTML file under `docs/` opens with
# front matter holding `docs_version: <version>`, for a version that has release notes,
# `release-notes/<version>.txt`, so it is a release and not a typo. The site's layout
# (`_includes/docs-version.html`) shows it, and puts a banner on a page whose version is behind the
# newest release notes'; a page without one would show neither, so it fails here instead. `ci.yml`
# runs it in every CI run, from the repository's root. Every failing page is reported.
#
# Usage: check-docs-versions.sh
set -euo pipefail

# Workflow commands take `%`, CR and LF escaped in the message, and `:` and `,` as well in a
# property such as `file=`.
escape_message() {
  local s=${1//%/%25}
  s=${s//$'\r'/%0D}
  printf '%s' "${s//$'\n'/%0A}"
}
escape_property() {
  local s
  s=$(escape_message "$1")
  s=${s//:/%3A}
  printf '%s' "${s//,/%2C}"
}
error() {
  echo "::error file=$(escape_property "$1")::$(escape_message "$2")"
}

# The value of `docs_version` in a page's front matter (between a first line `---` and the next
# `---`), quotes and trailing white space removed. Prints nothing when the page has no front matter,
# no such key, a front matter that is never closed, or the key more than once: YAML takes the last
# of repeated keys, so a second, empty `docs_version:` would hide the first from the site.
docs_version() {
  awk '
    NR == 1 { if ($0 !~ /^---[[:space:]]*$/) exit; open = 1; next }
    /^---[[:space:]]*$/ { closed = 1; exit }
    /^docs_version:/ {
      count++
      v = $0
      sub(/^docs_version:[[:space:]]*/, "", v)
      sub(/[[:space:]]*(#.*)?$/, "", v)
      gsub(/^["\047]|["\047]$/, "", v)
    }
    END { if (open && closed && count == 1) print v }
  ' "$1"
}

status=0
pages=0
while IFS= read -r -d '' page; do
  pages=$((pages + 1))
  version=$(docs_version "$page")
  if [ -z "$version" ]; then
    error "$page" "$page names no release: add \`docs_version: <version>\` to its front matter, the release it was last brought up to (design/release.md, Documentation versions)"
    status=1
  elif [[ ! $version =~ ^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$ ]]; then
    error "$page" "$page: docs_version $version is not a release version, <major>.<minor>.<patch> without leading zeros"
    status=1
  elif [ ! -f "release-notes/$version.txt" ]; then
    error "$page" "$page: docs_version $version has no release notes (release-notes/$version.txt), so it is no release"
    status=1
  fi
done < <(find docs -type f \( -name '*.md' -o -name '*.html' \) -print0)

if [ "$pages" -eq 0 ]; then
  echo "::error::No documentation pages found under docs/: run from the repository's root"
  exit 1
fi
exit "$status"
