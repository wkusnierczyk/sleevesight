# Contributing to Sleevesight

Thank you for your interest in Sleevesight. The app is in closed testing, before its launch on Google Play.

## Report a problem or suggest an idea

- In the app: **Report a problem**, on an insight, opens a report in your own mail app, with what the app found for the track; it reaches us only if you send it.
- On GitHub: [open an issue](https://github.com/wkusnierczyk/sleevesight/issues/new/choose) and pick the template that fits (Bug report, Feature request, …).
- By email: [support@sleevesight.app](mailto:support@sleevesight.app).

A security problem goes privately, as [SECURITY.md](SECURITY.md) says, never in a public issue.

## Code contributions

Not yet. Until the launch on Google Play, changes come from the project itself, through its release cycle and reviews. If you would like to work on something, say so in an issue first, and we will answer there.

Contributions are under the project’s licence, [GPL-3.0](LICENSE); there is no contributor agreement to sign.

## Building and testing

How to build the app and run its tests and checks, and how to use the pipeline tool in `cli/`: [README → Building](https://github.com/wkusnierczyk/sleevesight#building).

## How the project works

- Issue titles start with their kind, such as `[BUG]` or `[FEATURE]`, and carry a matching label.
- Each change is one issue, one branch and one pull request. The pull request says `Closes #N`, what changed and why, and how it was verified.
- Pull requests go into the open `dev-<version>` branch and are squash-merged; each release then moves `main` forward to it. Commits are signed.

## Code of conduct

Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md). Report a breach to [support@sleevesight.app](mailto:support@sleevesight.app).
