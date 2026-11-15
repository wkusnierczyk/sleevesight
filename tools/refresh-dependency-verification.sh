#!/usr/bin/env bash
# Rewrites the lockfiles and the dependency verification metadata after a dependency changes (#1034;
# `design/development.md`, *Dependencies*): a bump by hand, or one carried over from Dependabot.
#
# 1. `gradle/verification-metadata.xml` starts again from its `<configuration>`, less the keys, so
#    entries for versions no longer used go with them; the key servers are on while it runs.
# 2. The main build, over the tasks CI and the release run less the tests (whose classpaths
#    `resolveAndLockAll` resolves): a first pass trusts each signed file's PGP key, records a SHA-256
#    checksum for each file with no signature or no key on the key servers, exports the keys to
#    `gradle/verification-keyring.keys` and writes the lockfiles; a second adds a checksum for every
#    other file.
# 3. The build logic's functional tests, whose nested TestKit builds download the Kotlin compiler
#    and libraries for themselves: the same two passes, each nested build writing into a copy of the
#    metadata that the next one builds on (`build-logic/src/functionalTest/.../Probe.kt`).
# 4. `tools/dependency-verification.py finish`: each trusted key narrowed to the groups it signed,
#    the keyring's keys the metadata no longer names dropped, the checksums of the `aapt2` jars of
#    the platforms this run is not on, and the key servers off.
#
# On a failure, the verification files are put back as they were. It needs the network: Maven
# Central, Google's Maven, the Gradle Plugin Portal and the key servers. Review the diff before
# committing it: a new trusted key or a key trusted for more groups, a file that lost its signature,
# or a checksum that changed for a version that did not.
#
# Usage: tools/refresh-dependency-verification.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
files=(gradle/verification-metadata.xml gradle/verification-keyring.keys)
tasks=(
  resolveAndLockAll build checkDependencies checkDependencyPage checkManifest checkModuleGraph
  :app:assembleDebugAndroidTest :app:bundleRelease -x test
)

backup=$(mktemp -d)
for file in "${files[@]}"; do cp "$file" "$backup/"; done
restore() {
  status=$?
  if [ "$status" -ne 0 ]; then
    for file in "${files[@]}"; do cp "$backup/$(basename "$file")" "$file"; done
    echo "Failed: ${files[*]} put back as they were; the lockfiles are as Gradle left them (git restore them if need be)." >&2
  fi
  rm -rf "$backup"
}
trap restore EXIT

python3 -I tools/dependency-verification.py reset

./gradlew --console=plain --write-verification-metadata pgp,sha256 --export-keys --write-locks "${tasks[@]}"
./gradlew --console=plain --write-verification-metadata sha256 --write-locks "${tasks[@]}"

# `--rerun`: the task would otherwise be up to date, and its nested builds would not run.
SLEEVESIGHT_WRITE_VERIFICATION_METADATA=pgp,sha256 ./gradlew --console=plain :build-logic:functionalTest --rerun
SLEEVESIGHT_WRITE_VERIFICATION_METADATA=sha256 ./gradlew --console=plain :build-logic:functionalTest --rerun

python3 -I tools/dependency-verification.py finish

echo "Done: review the changes to the lockfiles, ${files[*]}."
