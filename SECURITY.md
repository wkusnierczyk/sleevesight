# Security

Sleevesight keeps your API keys on your phone and sends each only to its own service. If you find a
way around that, or any other security problem, please tell us privately first.

## Reporting a vulnerability

- **Preferred:** [report it privately on GitHub](https://github.com/wkusnierczyk/sleevesight/security/advisories/new).
- **Or by email:** support@sleevesight.app, with "Security" in the subject.

Please do not open a public issue for a problem someone could exploit before it is fixed. We reply
within 7 days, and tell you when a fix is released. With your agreement, we credit you in the
release notes.

## In scope

- The Android app, in the latest release on Google Play.
- The developer tool in `cli/`, run from source.
- The pages on sleevesight.app that take your keys: [API keys](https://sleevesight.app/api-keys.html)
  and the keyboard page.
- The build and release workflows in this repository.

Out of scope: the services Sleevesight calls (MusicBrainz, Wikipedia, Wikidata, Last.fm, LRCLIB, Exa, the AI
services), and attacks that need a rooted phone or a debug build.

## Security checks

What the app and its code are checked for, how, and the result for the latest release:
[Security checks](https://sleevesight.app/docs/security/).
