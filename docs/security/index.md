---
# Security checks (#217), at `/docs/security/`: what Sleevesight is checked for, how, and the result
# for the latest release. The full analysis (threat model, findings) is private while its main
# findings are open; this page is its public summary. *Results* is brought up to each release.
# Linked from the docs index (How it is made) and `SECURITY.md`.
title: Security checks
permalink: /docs/security/
docs_version: 0.9.0
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

## Results for 0.9.0

Checked on 18 November 2026, on the code of release 0.9.0.

- **Libraries:** no known vulnerability in any of the libraries the app and its command-line tool use, nor in the tools that build the app; none of them changed since 0.8.0.
- **Secrets:** no API key or other credential in the code or anywhere in its history.
- **Code checks:** Android lint's security checks found nothing, in the app or in the modules beside it.
- **Security review:** no way found to reach your keys or your data in the new code. Signing in with OpenRouter is back: OpenRouter now returns to an address on sleevesight.app, which Android opens in the app, and the code it brings back is of no use without a one-time secret that stays on the phone; if a browser opens that address instead, the page there runs nothing and sends the code nowhere. An insight for a track's further artists is a request of its own, made only when you tap it, and the privacy policy names it. A genre's reason comes in the same AI request as the genres, and a genre that only MusicBrainz gives never reaches the AI. The slowdown found in 0.7.2 on long AI replies is fixed. The two slowdowns found in 0.8.0 on unusual text are still left: one when showing an unusual AI reply, one on an unusual artist name. One nuisance is also left: another app on the phone can cancel a sign-in while it is under way. None of these reaches keys or data.

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
