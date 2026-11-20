---
# Security checks (#217), at `/docs/security/`: what Sleevesight is checked for, how, and the result
# for the latest release. The full analysis (threat model, findings) is private while its main
# findings are open; this page is its public summary. *Results* is brought up to each release.
# Linked from the docs index (How it is made) and `SECURITY.md`.
title: Security checks
permalink: /docs/security/
docs_version: 0.10.0
nav:
  - id: results-for-090
    label: Results
  - id: what-is-checked
    label: Checks
  - id: reporting-a-problem
    label: Reporting
provenance: [ai-wrote, human-reviewed]
---

# Security checks

Sleevesight keeps your API keys on your phone and sends each only to its own service. This page says how the app and its code are checked for security problems, and what the checks found for the latest release.

## Results for 0.10.0

Checked on 20 November 2026, on the code of release 0.10.0.

- **Libraries:** no known vulnerability in any of the libraries the app and its command-line tool use, nor in the tools that build the app; none of them changed since 0.9.2.
- **Secrets:** no API key or other credential in the code or anywhere in its history.
- **Code checks:** Android lint's security checks found nothing, in the app or in the modules beside it.
- **Security review:** no way found to reach your keys or your data in the new code. The OpenRouter sign-in now also takes an answer whose state was lost on the way back, but its code is worthless without the secret that stays on the phone, so another app or a web page can at most cancel a sign-in under way, as before. A settings file you load is read with size limits, can never hold or change your API keys or delete your history, and shows every change before it is made. Three small points the review found were fixed before the release: a chosen read-aloud voice is now spoken on the engine's server only when it is marked as better online, a saved settings file holds only the players you follow, and the load names a voice as the phone knows it, not as the file calls it. The two slowdowns found in 0.8.0 on unusual text are still left; neither reaches keys or data.

## What is checked

| Check | What it finds | When |
|---|---|---|
| [OSV-Scanner](https://google.github.io/osv-scanner/) over the lockfiles, which list every library and version the app and its command-line tool use | A library with a published vulnerability, checked against the [OSV database](https://osv.dev) | Each change to the libraries, and weekly, since a vulnerability can be published for a library that has not changed |
| GitHub's Dependabot, over the dependency graph the build reports to GitHub | A library with a published vulnerability, in the app or in the tools that build it; and newer versions of the app's libraries and of the build's actions, proposed as pull requests | Each change to `main`, and weekly |
| GitHub's secret scanning and push protection | An API key or other credential pushed to the repository; push protection refuses most before they land | Every push |
| Android lint's security checks, which fail the build | Weakened TLS, a component other apps can start, an intent launched unchecked, files other apps can read | Every change |
| The build's own allowlists | A new permission, or a library from a group not reviewed before | Every change |
| A security review: the threat model, and each path your keys and outside text take through the code | Design problems no scanner sees, such as an unbounded cost or text from a web page taken as fact | Once in full (November 2026); each release's new code before it ships |

## Reporting a problem

If you find a security problem, please tell us privately first: see [SECURITY.md](https://github.com/wkusnierczyk/sleevesight/blob/main/SECURITY.md) for how.
