#!/usr/bin/env python3
"""Compare two snapshots of what Sleevesight costs the phone (#626; see snapshot.sh).

Usage, from the repository root:

    tools/perf/snapshot.sh .dev/scratchpad/perf before
    ... listen ...
    tools/perf/snapshot.sh .dev/scratchpad/perf after
    python3 -I tools/perf/report.py <before> <after> [--tracks N]

Prints, for the time between the two: the phone's battery level and whether it was plugged in;
the battery use the system attributes to the app; the app's CPU time; the bytes it sent and
received, by network; and, from the second snapshot, its memory (PSS) and its installed size (the
data and caches are read by hand, from the phone's app info screen). With --tracks, the CPU time
and the bytes are also given per track. A figure that cannot be read from a snapshot is printed as
"not found", never as zero.
"""

import argparse
import os
import re
import sys


def read(snapshot, name):
    with open(os.path.join(snapshot, name + ".txt"), encoding="utf-8", errors="replace") as f:
        return f.read().replace("\r", "")


def meta(snapshot):
    pairs = (line.split("=", 1) for line in read(snapshot, "meta").splitlines() if "=" in line)
    return {k: v for k, v in pairs}


def battery(snapshot):
    text = read(snapshot, "battery")
    level = re.search(r"^\s*level: (\d+)", text, re.M)
    plugged = [k for k in ("AC", "USB", "Wireless", "Dock")
               if re.search(rf"^\s*{k} powered: true", text, re.M)]
    return (int(level.group(1)) if level else None), plugged


def cpu_seconds(snapshot, package):
    """The app's processes' CPU time, by PID, from `ps -o TIME+` (`[H:]M:SS.cc`)."""
    times = {}
    for line in read(snapshot, "ps").splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 5 and (fields[4] == package or fields[4].startswith(package + ":")):
            seconds = 0.0
            for part in fields[2].split(":"):
                seconds = seconds * 60 + float(part)
            times[fields[0]] = seconds
    return times


def pss_kb(snapshot):
    text = read(snapshot, "meminfo")
    found = re.search(r"TOTAL PSS:\s+(\d+)", text) or re.search(r"^\s*TOTAL\s+(\d+)", text, re.M)
    return int(found.group(1)) if found else None


def power_mah(snapshot, uid):
    """The battery use the system estimates for the app's uid since the last full charge."""
    for line in read(snapshot, "batterystats-checkin").splitlines():
        f = line.split(",")
        if len(f) > 5 and f[1] == str(uid) and f[2] == "l" and f[3] == "pwi" and f[4] == "uid":
            return float(f[5])
    app = uid % 100000 - 10000
    found = re.search(rf"^\s*UID u0a{app}: ([\d.]+)", read(snapshot, "batterystats"), re.M)
    return float(found.group(1)) if found else None


def net_bytes(snapshot, uid):
    """Received and sent bytes for the uid, by network type, from `netstats detail`'s UID stats."""
    text = read(snapshot, "netstats")
    section = re.search(r"^\s*UID stats:\n(.*?)(?=^\s*UID tag stats:|\Z)", text, re.M | re.S)
    if not section:
        return None
    totals, kind = {}, None
    for line in section.group(1).splitlines():
        ident = re.search(r"ident=\[(.*?)\]\s+uid=(-?\d+)\s+set=\S+\s+tag=(\S+)", line)
        if ident:
            mine = int(ident.group(2)) == uid and ident.group(3) == "0x0"
            types = re.findall(r"type=(\w+)", ident.group(1))
            kind = ("WIFI" if any(t in ("WIFI", "1") for t in types)
                    else "MOBILE" if any(t in ("MOBILE", "0") for t in types)
                    else "+".join(types) or "other") if mine else None
            continue
        bucket = re.search(r"\brb=(\d+).*?\btb=(\d+)", line)
        if kind and bucket:
            rx, tx = totals.get(kind, (0, 0))
            totals[kind] = (rx + int(bucket.group(1)), tx + int(bucket.group(2)))
    return totals


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if abs(n) < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("before")
    parser.add_argument("after")
    parser.add_argument("--tracks", type=int, help="tracks played between the two snapshots")
    parsed = parser.parse_args(argv)
    before, after, tracks = parsed.before, parsed.after, parsed.tracks
    m0, m1 = meta(before), meta(after)
    package, uid = m1["package"], int(m1["userId"])
    if (m0["package"], m0.get("userId")) != (package, m1["userId"]):
        sys.exit("the two snapshots are of different apps or installs")
    minutes = (int(m1["uptime_ms"]) - int(m0["uptime_ms"])) / 60000
    if minutes <= 0:
        sys.exit("the phone restarted between the snapshots, or they are in the wrong order")

    print(f"{package} {m1.get('versionName', '?')} on {m1['model']}, Android {m1['android']}"
          f" (build {m1['build']})")
    print(f"from {m0['taken_utc']} ({m0['label']}) to {m1['taken_utc']} ({m1['label']}):"
          f" {minutes:.1f} min")

    (l0, p0), (l1, p1) = battery(before), battery(after)
    plugged = sorted(set(p0) | set(p1))
    print(f"phone battery: {l0}% -> {l1}%"
          + (f"  (plugged in: {', '.join(plugged)}; drain figures do not hold)" if plugged else ""))

    w0, w1 = power_mah(before, uid), power_mah(after, uid)
    print("app battery use (system estimate): "
          + (f"{w1 - w0:.2f} mAh, {(w1 - w0) * 60 / minutes:.2f} mAh per hour"
             if w0 is not None and w1 is not None else "not found"))

    c0, c1 = cpu_seconds(before, package), cpu_seconds(after, package)
    if not c1:
        print("app CPU time: not found (the app is not running)")
    else:
        restarted = [pid for pid in c1 if pid not in c0]
        gone = [pid for pid in c0 if pid not in c1]
        used = sum(c1[pid] - c0.get(pid, 0) for pid in c1)
        if gone:
            # A process that ended took its CPU time with it: what is left is a lower bound only.
            line = (f"app CPU time: incomplete, at least {used:.1f} s (process(es) {', '.join(gone)}"
                    " ended in between, their time since the first snapshot not counted)")
        else:
            line = f"app CPU time: {used:.1f} s"
            if tracks:
                line += f", {used / tracks:.2f} s per track"
        if restarted:
            line += f"  (process(es) {', '.join(restarted)} started in between: counted from 0)"
        print(line)

    n0, n1 = net_bytes(before, uid), net_bytes(after, uid)
    if n0 is None or n1 is None:
        print("app network: not found")
    else:
        for kind in sorted(set(n0) | set(n1)) or ["(none)"]:
            rx = n1.get(kind, (0, 0))[0] - n0.get(kind, (0, 0))[0]
            tx = n1.get(kind, (0, 0))[1] - n0.get(kind, (0, 0))[1]
            line = f"app network, {kind}: received {human(rx)}, sent {human(tx)}"
            if tracks:
                line += f"; per track {human(rx / tracks)} / {human(tx / tracks)}"
            print(line)

    pss = pss_kb(after)
    print("app memory (PSS) at the second snapshot: "
          + (f"{pss / 1024:.0f} MB" if pss is not None else "not found (the app is not running)"))

    apk = sum(int(line.split()[0]) for line in read(after, "apk").splitlines() if line.strip())
    print(f"installed APKs: {human(apk)}")


if __name__ == "__main__":
    main(sys.argv[1:])
