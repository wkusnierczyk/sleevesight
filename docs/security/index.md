---
# Security checks (#217), at `/docs/security/`: what Sleevesight is checked for, how, and the result
# for the latest release. The full analysis (threat model, findings) is private while its main
# findings are open; this page is its public summary. *Results* is brought up to each release.
# Linked from the docs index (How it is made) and `SECURITY.md`.
title: Security checks
permalink: /docs/security/
docs_version: 0.6.0
nav:
  - id: results-for-060
    label: Results
  - id: what-is-checked
    label: Checks
  - id: reporting-a-problem
    label: Reporting
provenance: [ai-wrote]
---

# Security checks

Sleevesight keeps your API keys on your phone and sends each only to its own service. This page says how the app and its code are checked for security problems, and what the checks found for the latest release.

## Results for 0.6.0

Checked on 7 November 2026, on the code of release 0.6.0.

- **Libraries:** no known vulnerability in any of the 182 libraries the app and its tools are built with.
- **Secrets:** no API key or other credential in the code or anywhere in its history.
- **Code checks:** Android lint's security checks found nothing.
- **Security review:** no way found to reach your keys or your data in the released app. The review found things to improve, such as a limit on paid calls per hour and a check of what the build downloads; they are tracked on GitHub, in [Security: analysis and defenses](https://github.com/wkusnierczyk/sleevesight/milestone/12). The review's details stay private until the main ones are fixed.

## What is checked

| Check | What it finds | When |
|---|---|---|
| [OSV-Scanner](https://google.github.io/osv-scanner/) over the lockfiles, which list every library and version the app is built with | A library with a published vulnerability, checked against the [OSV database](https://osv.dev) | Each change to the libraries, and weekly, since a vulnerability can be published for a library that has not changed |
| GitHub's secret scanning and push protection | An API key or other credential pushed to the repository; push protection refuses most before they land | Every push |
| Android lint's security checks, which fail the build | Weakened TLS, a component other apps can start, an intent launched unchecked, files other apps can read | Every change |
| The build's own allowlists | A new permission, or a library from a group not reviewed before | Every change |
| A security review: the threat model, and each path your keys and outside text take through the code | Design problems no scanner sees, such as an unbounded cost or text from a web page taken as fact | Once in full (November 2026); each release's new code before it ships |

## Reporting a problem

If you find a security problem, please tell us privately first: see [SECURITY.md](https://github.com/wkusnierczyk/sleevesight/blob/main/SECURITY.md) for how.
