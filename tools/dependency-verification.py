#!/usr/bin/env python3
"""The steps of `tools/refresh-dependency-verification.sh` that edit the verification files (#1034).

  reset   Keeps `gradle/verification-metadata.xml`'s configuration (the settings, the trusted
          artifacts) and drops its keys and entries, with the key servers on, for Gradle to write
          the rest again.
  finish  Narrows each trusted key to the groups it signed (and fails on a key trusted for a group
          none of whose files it signed), drops the keyring's keys the metadata no longer names, adds the
          checksums of the `aapt2` jars of the platforms the refresh did not run on, and turns the
          key servers off.

Gradle's regular-expression trust (`^com[.]google($|([.].*))`) lets a key sign any group under a
prefix, so a key that signs one library would be trusted for every other publisher's under it, for
any file with no checksum recorded. `finish` replaces each such rule with the groups the key's
signatures cover, read from the `.asc` files in the Gradle caches (the main build's and the
functional tests' TestKit home). `aapt2` comes as one jar per platform, and the refresh downloads
only its own: the others are fetched from Google's Maven repository over HTTPS and their SHA-256
recorded, so CI (Linux) and the release check them by checksum too, not by signature alone.

The file is edited as text, in the layout Gradle writes, so no XML library is needed.
"""

import base64
import hashlib
import os
import pathlib
import re
import subprocess
import sys
import tempfile

METADATA = pathlib.Path("gradle/verification-metadata.xml")
KEYRING = pathlib.Path("gradle/verification-keyring.keys")
CACHES = [
    pathlib.Path(os.environ.get("GRADLE_USER_HOME", pathlib.Path.home() / ".gradle")),
    pathlib.Path("build-logic/build/testkit"),
]
AAPT2_PLATFORMS = ["linux", "osx", "windows"]
GOOGLE_MAVEN = "https://dl.google.com/dl/android/maven2"
KEY_SERVERS_OFF = '      <key-servers enabled="false"/>\n'
KEYRING_FORMAT = "      <keyring-format>armored</keyring-format>\n"


def fail(message):
    sys.exit(f"{METADATA}: {message}")


def reset(text):
    for pattern in (
        r"\n[ \t]*<ignored-keys>.*?</ignored-keys>",
        r"\n[ \t]*<trusted-keys>.*?</trusted-keys>",
        r"\n[ \t]*<key-servers enabled=\"false\"/>",
    ):
        text = re.sub(pattern, "", text, flags=re.S)
    text, count = re.subn(r"<components>.*?</components>|<components/>", "<components/>", text, flags=re.S)
    if count != 1 or "</configuration>" not in text:
        fail("no <configuration> and <components> found; check the file by hand")
    return text


# Signers.


def primary_keys():
    """Each key id in the keyring (primary or subkey, 16 hex digits) to its primary's."""
    primaries, primary = {}, None
    for line in KEYRING.read_text(encoding="utf-8").splitlines():
        match = re.match(r"(pub|sub)\s+([0-9A-F]{16})$", line.strip())
        if match:
            if match.group(1) == "pub":
                primary = match.group(2)
            primaries[match.group(2)] = primary
    return primaries


def subpackets(data):
    i = 0
    while i < len(data):
        first = data[i]
        if first < 192:
            length, i = first, i + 1
        elif first < 255:
            length, i = ((first - 192) << 8) + data[i + 1] + 192, i + 2
        else:
            length, i = int.from_bytes(data[i + 1 : i + 5], "big"), i + 5
        yield data[i] & 0x7F, data[i + 1 : i + length]
        i += length


def issuer(asc):
    """The id (16 hex digits) of the key that made the signature in the armored `.asc` [asc]."""
    lines = asc.read_text(encoding="ascii", errors="replace").splitlines()
    try:
        start = next(i for i, line in enumerate(lines) if line.startswith("-----BEGIN PGP SIGNATURE"))
        body_start = next(i for i in range(start, len(lines)) if not lines[i].strip()) + 1
    except StopIteration:
        return None
    body = "".join(l.strip() for l in lines[body_start:] if l.strip() and not l.startswith(("=", "-----")))
    data = base64.b64decode(body)
    tag = data[0]
    if tag & 0x40:  # New packet format.
        first = data[1]
        offset = 2 if first < 192 else 3 if first < 224 else 6
    else:
        offset = 1 + {0: 1, 1: 2, 2: 4}.get(tag & 0x03, 0)
    packet = data[offset:]
    if packet[0] == 3:  # Version 3: the key id after the type and the creation time.
        return packet[7:15].hex().upper()
    if packet[0] != 4:
        return None
    hashed_length = int.from_bytes(packet[4:6], "big")
    hashed = packet[6 : 6 + hashed_length]
    unhashed_length = int.from_bytes(packet[6 + hashed_length : 8 + hashed_length], "big")
    unhashed = packet[8 + hashed_length : 8 + hashed_length + unhashed_length]
    for kind, value in list(subpackets(hashed)) + list(subpackets(unhashed)):
        if kind == 33:  # Issuer fingerprint: a version, then the fingerprint.
            return value[1:].hex().upper()[-16:]
        if kind == 16:  # Issuer key id.
            return value.hex().upper()
    return None


def signed_groups(text):
    """Each key id (the signing key's and its primary's) to the groups whose recorded files it signed,
    by the caches' `.asc` files."""
    primaries = primary_keys()
    components = set(re.findall(r'<component group="([^"]+)" name="([^"]+)" version="([^"]+)">', text))
    groups = {}
    for group, name, version in components:
        for cache in CACHES:
            for asc in (cache / "caches/modules-2/files-2.1" / group / name / version).glob("*/*.asc"):
                # A trusted key's id is the fingerprint of the key that signs or of its primary.
                signer = issuer(asc)
                for key in {signer, primaries.get(signer or "")} - {None}:
                    groups.setdefault(key, set()).add(group)
    return groups


def narrow(text, signed):
    """Replaces each regular-expression trust with the groups its key signed."""
    report = []

    def groups_for(key, pattern, extra):
        groups = sorted(g for g in signed.get(key[-16:], ()) if re.match(pattern, g))
        report.append(f"{key[-16:]}: {pattern} -> {', '.join(groups) or '(nothing signed: dropped)'}")
        return [f'group="{g}"{extra}' for g in groups]

    def one_line(match):
        indent, key, pattern, extra = match.group(1), match.group(2), match.group(3), match.group(4) or ""
        groups = groups_for(key, pattern, extra)
        if len(groups) == 1:
            return f'{indent}<trusted-key id="{key}" {groups[0]}/>\n'
        inner = "".join(f"{indent}   <trusting {g}/>\n" for g in groups)
        return f'{indent}<trusted-key id="{key}">\n{inner}{indent}</trusted-key>\n' if groups else ""

    text = re.sub(r'([ \t]*)<trusted-key id="([0-9A-F]+)" group="([^"]+)" regex="true"( [^/>]*)?/>\n', one_line, text)

    def block(match):
        indent, key, body = match.group(1), match.group(2), match.group(3)

        def trusting(entry):
            groups = groups_for(key, entry.group(2), entry.group(3) or "")
            return "".join(f"{entry.group(1)}<trusting {g}/>\n" for g in groups)

        body = re.sub(r'([ \t]*)<trusting group="([^"]+)" regex="true"( [^/>]*)?/>\n', trusting, body)
        lines = sorted(set(body.splitlines(keepends=True)), key=lambda line: line.strip())
        if not lines:
            return ""
        return f'{indent}<trusted-key id="{key}">\n{"".join(lines)}{indent}</trusted-key>\n'

    text = re.sub(r'([ \t]*)<trusted-key id="([0-9A-F]+)">\n(.*?)[ \t]*</trusted-key>\n', block, text, flags=re.S)
    keys = text.split("<trusted-keys>", 1)[-1].split("</trusted-keys>", 1)[0]
    if 'regex="true"' in keys:
        fail("a regular-expression trust is left; check the file by hand")
    return text, report


# aapt2.


def sha256_of(url):
    with tempfile.TemporaryDirectory() as directory:
        target = pathlib.Path(directory) / "download"
        subprocess.run(["curl", "-fsSL", "--proto", "=https", "-o", str(target), url], check=True)
        return hashlib.sha256(target.read_bytes()).hexdigest()


def aapt2(text):
    added = []
    pattern = r'(<component group="com[.]android[.]tools[.]build" name="aapt2" version="([^"]+)">\n)(.*?)([ \t]*</component>)'

    def component(match):
        version, body = match.group(2), match.group(3)
        entries = re.findall(r'([ \t]*<artifact name="([^"]+)">.*?</artifact>\n)', body, flags=re.S)
        artifacts = {name: entry for entry, name in entries}
        for platform in AAPT2_PLATFORMS:
            name = f"aapt2-{version}-{platform}.jar"
            if name in artifacts:
                continue
            digest = sha256_of(f"{GOOGLE_MAVEN}/com/android/tools/build/aapt2/{version}/{name}")
            artifacts[name] = (
                f'         <artifact name="{name}">\n'
                f'            <sha256 value="{digest}" origin="Google Maven, for the platforms the refresh did not run on"/>\n'
                "         </artifact>\n"
            )
            added.append(name)
        return match.group(1) + "".join(artifacts[n] for n in sorted(artifacts)) + match.group(4)

    text, count = re.subn(pattern, component, text, flags=re.S)
    if count == 0:
        fail("no com.android.tools.build:aapt2 component; the refresh did not build the app")
    return text, added


def unsigned_trusts(text, signed):
    """Each trust whose key signed none of the recorded files of its group."""
    keys = text.split("<trusted-keys>", 1)[-1].split("</trusted-keys>", 1)[0]
    entries = re.finditer(r'<trusted-key id="([0-9A-F]+)"( group="([^"]+)")?[^>]*?(/>|>(.*?)</trusted-key>)', keys, flags=re.S)
    return [
        f"{entry.group(1)[-16:]} {group}"
        for entry in entries
        for group in ([entry.group(3)] if entry.group(3) else re.findall(r'<trusting group="([^"]+)"', entry.group(5) or ""))
        if group not in signed.get(entry.group(1)[-16:], ())
    ]


def prune_keyring(text):
    """Drops the keyring's keys that the metadata no longer names, by the key or one of its subkeys,
    as a trusted key or as the signer of one file (`<pgp value=...>`): Gradle exports a refresh's
    keys on top of the keyring it found, so a key no longer used (a library removed, a key rotated)
    would stay. Returns the ids dropped."""
    named = re.findall(r'<trusted-key id="([0-9A-F]+)"', text) + re.findall(r'<pgp value="([0-9A-F]+)"', text)
    trusted = {key[-16:] for key in named}
    keyring = KEYRING.read_text(encoding="utf-8")
    blocks = re.split(r"(?m)^(?=pub\s)", keyring)
    kept, dropped = [], []
    for block in blocks:
        ids = re.findall(r"(?m)^(?:pub|sub)\s+([0-9A-F]{16})$", block)
        if not ids or trusted & set(ids):
            kept.append(block)
        else:
            dropped.append(ids[0])
    missing = trusted - set(re.findall(r"(?m)^(?:pub|sub)\s+([0-9A-F]{16})$", "".join(kept)))
    if missing:
        fail(f"keys the metadata names but {KEYRING} lacks: {', '.join(sorted(missing))}")
    KEYRING.write_text("".join(kept), encoding="utf-8")
    return dropped


def finish(text):
    signed = signed_groups(text)
    text, report = narrow(text, signed)
    unbacked = unsigned_trusts(text, signed)
    if unbacked:
        fail("keys trusted for groups none of whose files they signed: " + "; ".join(unbacked))
    text, added = aapt2(text)
    if text.count(KEYRING_FORMAT) != 1:
        fail(f"no {KEYRING_FORMAT.strip()} line to put the key-server setting after")
    if KEY_SERVERS_OFF not in text:
        text = text.replace(KEYRING_FORMAT, KEYRING_FORMAT + KEY_SERVERS_OFF)
    for key in prune_keyring(text):
        print(f"Key dropped from the keyring, no longer named: {key}")
    for line in report:
        print(f"Trust narrowed: {line}")
    for name in added:
        print(f"Checksum added: {name}")
    return text


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("reset", "finish"):
        sys.exit(f"usage: {sys.argv[0]} reset|finish")
    text = METADATA.read_text(encoding="utf-8")
    text = reset(text) if sys.argv[1] == "reset" else finish(text)
    METADATA.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
