#!/usr/bin/env bash
# Rewrites the lockfiles and the dependency verification metadata after a dependency changes (#1034;
# `design/development.md`, *Dependencies*): a bump by hand, or one carried over from Dependabot.
#
# - `gradle/verification-metadata.xml` starts again from its `<configuration>`, less the keys, so
#   entries for versions no longer used go with them.
# - A first pass trusts each signed artifact's PGP key, for its group where Gradle can, records a
#   SHA-256 checksum for each artifact with no signature or no key on the key servers, and exports
#   the keys to `gradle/verification-keyring.keys`.
# - A second pass adds a SHA-256 checksum for every other artifact, so each one's bytes are pinned
#   as well as its signer.
# - The key servers are turned off again, so that builds read only the committed keyring.
#
# Both passes run the tasks CI and the release run, less the tests (whose classpaths
# `resolveAndLockAll` resolves), and write the lockfiles too. They need the network: Maven Central,
# Google's Maven, the Gradle Plugin Portal and the key servers. Review the diff before committing it:
# a new trusted key, an artifact that lost its signature, or a checksum that changed for a version
# that did not.
#
# Usage: tools/refresh-dependency-verification.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
metadata=gradle/verification-metadata.xml
tasks=(
  resolveAndLockAll build checkDependencies checkDependencyPage checkManifest checkModuleGraph
  :app:assembleDebugAndroidTest :app:bundleRelease -x test
)

# Keeps the configuration (the settings, the trusted artifacts) and drops the rest; the key servers on.
python3 -I - "$metadata" <<'EOF'
import re, sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
before = text
for pattern in (
    r"\n[ \t]*<ignored-keys>.*?</ignored-keys>",
    r"\n[ \t]*<trusted-keys>.*?</trusted-keys>",
    r"\n[ \t]*<key-servers enabled=\"false\"/>",
):
    text = re.sub(pattern, "", text, flags=re.S)
text, count = re.subn(r"<components>.*?</components>|<components/>", "<components/>", text, flags=re.S)
if count != 1 or "</configuration>" not in text:
    sys.exit(f"{path}: no <configuration> and <components> found; check the file by hand")
open(path, "w", encoding="utf-8").write(text)
EOF

./gradlew --console=plain --write-verification-metadata pgp,sha256 --export-keys --write-locks "${tasks[@]}"
./gradlew --console=plain --write-verification-metadata sha256 --write-locks "${tasks[@]}"

# The key servers off again: builds verify against `gradle/verification-keyring.keys` alone.
python3 -I - "$metadata" <<'EOF'
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
anchor = "<keyring-format>armored</keyring-format>\n"
if text.count(anchor) != 1:
    sys.exit(f"{path}: no {anchor.strip()} line to put the key-server setting after")
text = text.replace(anchor, anchor + '      <key-servers enabled="false"/>\n')
open(path, "w", encoding="utf-8").write(text)
EOF

echo "Done: review the changes to the lockfiles, $metadata and gradle/verification-keyring.keys."
