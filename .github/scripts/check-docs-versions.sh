#!/usr/bin/env bash
# Checks that every documentation page names the release it was last brought up to (#828;
# `design/release.md`, *Documentation versions*): each Markdown or HTML file under `docs/` opens with
# front matter holding `docs_version: <version>`, for a version that has release notes,
# `release-notes/<version>.txt`, so it is a release and not a typo. The site's layout
# (`_includes/docs-version.html`) shows it, and puts a banner on a page whose version is behind the
# newest release notes'; a page without one would show neither, so it fails here instead. `ci.yml`
# runs it in every CI run, from the repository's root. Every failing page is reported. The site's
# other pages that name the version they were last brought up to in their footer (#1156; the product
# page, the privacy policy, the README page and the two tool pages) are checked the same way: a
# release, not a typo; they have no banner, so being behind is not checked.
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
# `---`), as YAML reads it, which is how Jekyll reads it. Prints nothing when the page has no front
# matter, front matter that is never closed or is not valid YAML, no such key, the key more than
# once (YAML would take the last, so a second, empty one would hide the first from the site), or a
# value that is not a plain string; the caller then checks the value is a release.
docs_version() {
  ruby -ryaml -e '
    text = File.read(ARGV[0])
    m = text.match(/\A---[ \t]*\r?\n(.*?)^---[ \t]*\r?$/m) or exit
    begin
      root = Psych.parse(m[1])&.root
    rescue Psych::SyntaxError
      exit
    end
    exit unless root.is_a?(Psych::Nodes::Mapping)
    pairs = root.children.each_slice(2).select { |k, _| k.is_a?(Psych::Nodes::Scalar) && k.value == "docs_version" }
    exit unless pairs.size == 1
    value = pairs[0][1]
    exit unless value.is_a?(Psych::Nodes::Scalar) && value.tag.nil?
    print value.value
  ' "$1"
}

status=0
pages=0
while IFS= read -r -d '' page; do
  [[ $page == docs/* ]] && pages=$((pages + 1))
  version=$(docs_version "$page")
  if [ -z "$version" ]; then
    error "$page" "$page names no release: its front matter, closed by \`---\`, needs exactly one \`docs_version: <version>\`, the release it was last brought up to (design/release.md, Documentation versions)"
    status=1
  elif [[ ! $version =~ ^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$ ]]; then
    error "$page" "$page: docs_version $version is not a release version, <major>.<minor>.<patch> without leading zeros"
    status=1
  elif [ ! -f "release-notes/$version.txt" ]; then
    error "$page" "$page: docs_version $version has no release notes (release-notes/$version.txt), so it is no release"
    status=1
  fi
done < <(find docs -type f \( -name '*.md' -o -name '*.html' \) -print0
  printf '%s\0' index.html privacy.md readme-page.html api-keys.html keyboard.html)

if [ "$pages" -eq 0 ]; then
  echo "::error::No documentation pages found under docs/: run from the repository's root"
  exit 1
fi
exit "$status"
