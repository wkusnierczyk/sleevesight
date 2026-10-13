#!/usr/bin/env python3
"""Turn the HTTP-RECORD lines of a recording run into recorded responses (see README.md).

Usage, from the repository root:

    SLEEVESIGHT_RECORD_HTTP=musicbrainz,wikidata ./gradlew :sources:test --tests '*RecordHttpFixtures*' --rerun
    python3 -I fixtures/http/record.py sources/build/test-results/test

Each exchange goes to fixtures/http/<source>/<name>.json, and its request into that directory's
index.json, replacing an entry for the same request. MusicBrainz responses lose every `tags` and
`genres` field (CC BY-NC-SA 3.0 supplementary data, which a search returns unasked); nothing else
is changed. A run that recorded nothing is an error, so a skipped recording is never mistaken for
an empty one.
"""

import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ElementTree
from urllib.parse import urlsplit

PREFIX = "HTTP-RECORD "
HERE = os.path.dirname(os.path.abspath(__file__))
SOURCES = {
    "musicbrainz.org": "musicbrainz",
    "www.wikidata.org": "wikidata",
    "en.wikipedia.org": "wikipedia",
}
# Query parameters that are the same on every request of a source, left out of file names.
CONSTANT = {"fmt", "format", "formatversion", "limit", "inc", "props", "languages", "sitefilter", "redirects", "prop",
            "ppprop", "exintro", "explaintext", "srnamespace", "srlimit", "origin", "utf8"}


def records(results_dir):
    for path in sorted(glob.glob(os.path.join(results_dir, "*.xml"))):
        root = ElementTree.parse(path).getroot()
        for out in root.iter("system-out"):
            for line in (out.text or "").splitlines():
                if line.startswith(PREFIX):
                    yield json.loads(line[len(PREFIX):])


def strip_tags(value):
    if isinstance(value, dict):
        return {k: strip_tags(v) for k, v in value.items() if k not in ("tags", "genres")}
    if isinstance(value, list):
        return [strip_tags(v) for v in value]
    return value


def name_for(record):
    parts = urlsplit(record["url"])
    path = [p for p in parts.path.split("/") if p and p not in ("ws", "2", "w", "api.php")]
    values = [v for k, v in sorted(record["query"].items()) if k not in CONSTANT]
    slug = re.sub(r"[^a-z0-9]+", "-", " ".join(path + values).lower()).strip("-")
    return (slug or "root")[:100].rstrip("-")


def main(results_dir):
    found = list(records(results_dir))
    if not found:
        sys.exit("No HTTP-RECORD lines in " + results_dir + ": was SLEEVESIGHT_RECORD_HTTP=musicbrainz,wikidata set, with --rerun?")
    indexes = {}
    for record in found:
        host = urlsplit(record["url"]).hostname
        source = SOURCES.get(host)
        if source is None:
            sys.exit("Unknown host " + str(host))
        directory = os.path.join(HERE, source)
        os.makedirs(directory, exist_ok=True)
        if source not in indexes:
            index_path = os.path.join(directory, "index.json")
            indexes[source] = json.load(open(index_path)) if os.path.exists(index_path) else []
        index = indexes[source]
        key = (record["method"], record["url"], record["query"])
        for old in [e for e in index if (e["method"], e["url"], e["query"]) == key]:
            os.remove(os.path.join(directory, old["file"]))
        index[:] = [e for e in index if (e["method"], e["url"], e["query"]) != key]
        body = json.loads(record["body"])
        if source == "musicbrainz":
            body = strip_tags(body)
        taken = {e["file"] for e in index}
        base = name_for(record)
        name, n = base + ".json", 2
        while name in taken:
            name, n = "%s-%d.json" % (base, n), n + 1
        with open(os.path.join(directory, name), "w") as out:
            json.dump(body, out, ensure_ascii=False, indent=1, sort_keys=False)
            out.write("\n")
        index.append({"method": record["method"], "url": record["url"], "query": record["query"],
                      "status": record["status"], "file": name})
    for source, index in indexes.items():
        index.sort(key=lambda e: e["file"])
        with open(os.path.join(HERE, source, "index.json"), "w") as out:
            json.dump(index, out, ensure_ascii=False, indent=1)
            out.write("\n")
    print("Recorded %d responses" % len(found))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "sources/build/test-results/test")
