# Releasing Sleevesight

**Date:** 2026-10-16  
**Builds on:** [architecture.md](architecture.md), *CI*; D7 (distribution through Google Play)  
**Tracking:** [#56](https://github.com/wkusnierczyk/sleevesight/issues/56), [#249](https://github.com/wkusnierczyk/sleevesight/issues/249) (the upload to Play)

How a release is built, signed and uploaded to Google Play, and the one-time setup it needs outside the repository. Play's review, the testing tracks and the store listing are [#58](https://github.com/wkusnierczyk/sleevesight/issues/58) and [#57](https://github.com/wkusnierczyk/sleevesight/issues/57).

## Contents

- [The release workflow](#the-release-workflow)
- [Test and production builds](#test-and-production-builds)
- [Release notes](#release-notes)
- [Store listing](#store-listing)
- [Versioning](#versioning)
- [R8](#r8)
- [Signing](#signing)
- [One-time setup](#one-time-setup)
- [A lost or leaked upload key](#a-lost-or-leaked-upload-key)

## The release workflow

`.github/workflows/release.yml` runs on a pushed tag `v<major>.<minor>.<patch>`, for example `v0.1.0`. Its one job runs in the GitHub environment `release`, which holds the upload key, so it waits for a reviewer's approval before it starts. It then:

1. checks that the tagged commit is on `main`, and stops if it is not;
2. reads the track the bundle is for, and with it whether the build starts in developer mode, and stops a production tag that is not the tested code, or a track id it does not take ([Test and production builds](#test-and-production-builds));
3. checks that the version has its [release notes](#release-notes), and stops if it has none;
4. decodes the upload key from the environment's secrets into the runner's temporary directory;
5. builds `./gradlew :app:bundleRelease -Psleevesight.version=<the tag without its v> -Psleevesight.developerMode=<true or false>`, shrunk by R8 and signed with the upload key, from a clean state: no Gradle cache is restored, and the build cache is off; then checks that the build took the developer-mode value it was given (`.github/scripts/check-developer-mode.sh`);
6. removes the key file, checks that the bundle is signed, and checks the signer: its certificate's SHA-256 fingerprint must equal the environment variable `UPLOAD_CERT_SHA256` once that is set (step 5 of [Play App Signing](#5-play-app-signing)); until then the run prints the fingerprint;
7. uploads the bundle and the R8 mapping as the workflow artifact `sleevesight-<version>`, kept 90 days;
8. uploads the bundle to Google Play through the Play Developer API (`androidpublisher` v3), with `.github/scripts/play-upload.sh`, in one edit: it opens the edit, uploads the bundle, checks that Play read the `versionCode` the tag gives, sets the track to a release named for the version (`0.1.1`, not Play's default `1001 (0.1.1)`) with its notes and the status `completed` (or `draft`, below), and commits the edit. A failure discards the edit, so nothing reaches the track.

The track is the environment variable `PLAY_TRACK`, set to `alpha`: Play's closed testing track, where the closed test ([#58](https://github.com/wkusnierczyk/sleevesight/issues/58)) runs. Since `v0.1.13` each tag goes straight there ([#749](https://github.com/wkusnierczyk/sleevesight/issues/749)), with no internal testing first and no promotion by hand in Play Console; a build to check before it reaches the testers is installed on a phone over `adb`. Each release on the track waits for Play's review before the testers get it. A `completed` release on a track replaces the one before it there, for all of the track's testers at once. Unset, `PLAY_TRACK` falls back to `internal`, a track no longer used, so keep it set.

Two refusals Play may give at the track update or the commit, with the remedy:

- *Only releases with status draft may be created on draft app*: Play still treats the app as a draft, which it may do until a release has been reviewed. Set the environment variable `PLAY_RELEASE_STATUS` to `draft` (`gh variable set PLAY_RELEASE_STATUS --env release --repo wkusnierczyk/sleevesight --body draft`); the run then leaves the release, with its name and notes, as a draft in Play Console, to be rolled out there with one click. A status other than `completed` or `draft` stops the run before the edit is opened. Once Play Console no longer lists the app as a draft (its status under *All apps*), delete the variable: `gh variable delete PLAY_RELEASE_STATUS --env release --repo wkusnierczyk/sleevesight`.
- *Changes cannot be sent for review automatically* (`changesNotSentForReview`): Play has rejected an earlier change, and until it is fixed, changes go for review only from Play Console. The failed run has discarded its edit, so upload the bundle from the run's artifact by hand, as in step 3 of [Play App Signing](#5-play-app-signing) but on the run's track (`PLAY_TRACK`: for `alpha`, *Closed testing*, *Alpha*, rather than *Internal testing*), with the release's name and notes, and send it for review there. The script commits without `changesNotSentForReview=true`: Play refuses that flag while changes go for review automatically, and with it a run would only leave the release in Play Console to be sent for review there. A run in this state therefore fails, and the bundle is uploaded by hand as above. The job works again once the rejection is resolved.

It does not run the tests: the tagged commit has passed `build` on its pull request and on `main`. Its actions are pinned to commits, not tags, since the job holds the upload key. The API is called with `curl` from the repository's own script rather than through an action, so no third-party code sees the access token; the token comes from Google's own `google-github-actions/auth`, pinned like the others. Since the bundle is kept as an artifact before step 8, a failed upload can still be finished by hand in Play Console (as in step 3 of [Play App Signing](#5-play-app-signing), on the run's track), with the release's name and notes typed in.

The first bundle of a new app goes through Play Console, since the API cannot create an app; for Sleevesight that was `v0.1.0`. The API also cannot manage testers given by email address (its testers resource takes Google Groups only), so the testers' lists stay in Play Console.

## Test and production builds

A bundle for one of Play's testing tracks (internal, closed or open testing) starts in developer mode, so the settings show their *Developer* section, and *Diagnostics* in it, without seven taps on *Version* ([#451](https://github.com/wkusnierczyk/sleevesight/issues/451)); the testers' brief (`docs/tester/alpha/index.md`) relies on it. A bundle for production does not. The workflow decides by the track (step 2, `.github/scripts/release-track.sh`): `-Psleevesight.developerMode=true` for any track but `production`, `false` for `production`. The track is `PLAY_TRACK` trimmed of white space at either end; a value other than a track id of lower-case letters, digits and hyphens stops the run, as does any other spelling of `production` (`Production`), so a typo cannot build a test bundle for a track meant as production. Gradle turns the property into `BuildConfig.DEVELOPER_MODE_DEFAULT` (`app/build.gradle.kts`; without the property, as in every build but a release, it is `false`, and a value other than `true` or `false` fails the build), and the settings take it as developer mode's default (`SettingsStore.BUILD_DEFAULTS`).

It is only the default. As with every setting, only a value the user moved off its default is stored (`SettingsCodec`), so:

- a tester who turns developer mode off keeps it off, through updates, and in the production build too; one who turns it on in the production build keeps it on;
- where no choice is stored, each build reads its own default: a tester who never touched it and then installs the production build has developer mode off there, unless they had turned *Diagnostics* on, which keeps developer mode on (as for a settings file from before developer mode);
- clearing the app's data returns it to the build's default.

**The production bundle is a different build from the one tested.** Play promotes a release from one track to the next as it is, the same bundle, so a release promoted from a testing track to production in Play Console would ship with developer mode on. Never promote to production; build the production bundle on its own instead:

1. The tested bundle came from a tag, say `v0.3.2`, on a commit of `main`. The production bundle needs a higher `versionCode` ([Versioning](#versioning)), so a new patch version: `v0.3.3`.
2. Its tag goes on a commit whose code is the tested one: the same commit, if it already holds `release-notes/0.3.3.txt`, or a later commit of `main` that adds only that file. The workflow holds a production tag to this: it stops unless the tag differs from the release tag before it on its history (the highest version tag the tagged commit contains, `v0.3.2` here) in `release-notes/` only. So a `PLAY_TRACK` left at `production` stops the next test tag, which brings new code, rather than send that code to production; it also means the first production release follows a tested one.
3. Set the environment variable `PLAY_TRACK` to `production` (`gh variable set PLAY_TRACK --env release --repo wkusnierczyk/sleevesight --body production`), push the tag, and approve the run. The approval comes before the job's steps, so the approver does not see the track; the run then warns *Production release* and its summary names the `production` track with developer mode `false`. The check above is what stops a mistaken production run. The upload to production needs the service account's *Release to production* permission ([Google Play Developer API](#6-google-play-developer-api)); without it, the upload fails and the bundle, from the run's artifact, is uploaded to the production track by hand in Play Console.
4. Set `PLAY_TRACK` back to `alpha` at once (`gh variable set PLAY_TRACK --env release --repo wkusnierczyk/sleevesight --body alpha`), so the next tag does not go to production. Deleting the variable would send the next tag to `internal` instead.

The two builds differ in the flag alone: the same code, signed with the same key, and only the default of one setting apart. The production bundle is still a new build, so it is installed and opened on a phone before its rollout is widened. CI checks the flag under both values on every pull request it builds: it reads the flag back from each build's `BuildConfig`, and runs the settings' test of the default (`DeveloperModeDefaultTest`) under each (`ci.yml`, *Developer mode by default*).

## Release notes

Each release's notes are in `release-notes/<version>.txt`, for example `release-notes/0.1.1.txt`, committed with the change that ships, so they are reviewed with it. They are in the listing's language, `en-US`, plain text, at most 500 characters (Play's limit per language); trailing white space is dropped. CI checks each file in `release-notes/` for its name and length on every pull request (`.github/scripts/check-release-notes.sh`), so a misnamed, empty or overlong file fails the pull request that adds it. A tag that is no release version, or whose version has no notes file or an empty or longer one, stops the run before the build, so its version is spent: add the notes before tagging.

## Store listing

The Play listing's text is in `listing/en-US/`, one plain-text file per field, in the listing's language, so it is reviewed like the code it describes: `title.txt` (at most 30 characters), `short-description.txt` (80) and `full-description.txt` (4,000), Play's limits. It is entered in Play Console by hand (*Store presence → Main store listing*; the console moves its menus, so look there first), which refuses text over a limit. A change to what the app does, or to what leaves the phone, changes the listing with the README and the [privacy policy](../privacy.md). The listing describes the build on Play, not the roadmap.

The listing's graphics are in `listing/graphics/`, uploaded in Play Console by hand in the same place: the app icon, `icon-512.png`, and the feature graphic, `feature-graphic.png`; the screenshots come later. The icon is 512 × 512, a 32-bit PNG, a full-bleed square, since Play rounds its corners and adds the shadow itself ([Play's icon specification](https://developer.android.com/distribute/google-play/resources/icon-design-specifications)). It and the launcher icon's layers, the debug build's too, are generated from one drawing, the record of the brand sheet (`design/brand.html`), by `python3 assets/logo/src/make_icon.py` from the repository root, which needs `rsvg-convert` and ImageMagick (`brew install librsvg imagemagick`). After a change to the record (`<symbol id="lp">`) or to `.lt`'s `--rim`, `--groove` or `--tile-bg` in `design/brand.html`, or to the script, run it again; it writes every file again. `--label plain --out <directory>` builds the variant with a plain label, as the favicon draws it, without touching the committed files.

Play's [preview assets](https://support.google.com/googleplay/android-developer/answer/9866151) page sets the feature graphic at 1024 × 500 px, JPEG or 24-bit PNG with no alpha. Play shows it as the cover of a preview video (where a video does not play by itself, "a play button is overlaid on your feature graphic"), and in large-format collections, recommendations and ads. The same page advises:

- the focal point towards the middle, and the logo, the app's name and its slogan out of the zones some formats crop or overlay: here the words and the tagline stay towards the middle, and only the record runs off the edges;
- no prominent branding that resembles the app icon, since the two are often shown together: the record is also the app icon ([#379](https://github.com/wkusnierczyk/sleevesight/issues/379)), but here it stands beside the words, large and cropped by the edges, not as an icon's tile;
- vivid colours rather than white, black or dark grey, which merge with the store's own background: the ground is the label's red;
- no wording such as `Best`, `#1` or `Free`.

The graphic is the label's red, with "Sleeve" over "sight" as the logo sets them, the tagline under them, and the record beside "sight", large and running off the edge. It is built from the logo's sources by `assets/logo/src/make_feature_graphic.py`, which writes `feature-graphic.svg` (every letter outlined, so it needs no font) and the PNG rendered from it; its header says what it needs and where the font comes from, and it stops on another version of the font. After a change to the logo's words (`assets/logo/src/tile.json`), or to the record or the light colourway's red and ink (both in `design/brand.html`), run it again from the repository root:

```sh
python3 assets/logo/src/make_feature_graphic.py RobotoFlex.ttf
```

With `--design all`, it also rebuilds the other designs tried for the graphic, kept in `assets/logo/feature-graphic/`; the [brand sheet](brand.html) shows the one in use. The product page's link preview, `assets/og-image.png` (its `og:image`), is built from the same sources by `assets/logo/src/make_og_image.py`, with the same font and code it imports from `make_feature_graphic.py`: after such a change, or to `make_feature_graphic.py`, run it too (`python3 assets/logo/src/make_og_image.py RobotoFlex.ttf`), and `assets/logo/src/make_mark.py`, which writes the README's logo, `assets/logo/mark-light.svg` and `mark-dark.svg`, and needs no font (`python3 assets/logo/src/make_mark.py`).

Play Console asks for alt text with the graphic, at most 140 characters. Play's advice: "Don't use "photo of" or "image of"; screen readers already provide this information." It is: *The Sleevesight logo on red: the words Sleeve and sight beside a large vinyl record, and the tagline Insights on what's playing*.

## Versioning

The tag gives both versions, so a release needs no commit to bump them:

- `versionName` is the tag without its `v`: `v1.2.3` gives `1.2.3`.
- `versionCode` is `major × 1 000 000 + minor × 1 000 + patch`: `1.2.3` gives `1002003`.

Google Play needs every upload's `versionCode` higher than any before it. This code rises with the version, so releases go out in version order only: a patch to an older line, `1.1.4` after `1.2.0`, gets a lower code, and Play refuses it. The code can be read back as the version, and needs no state such as a counter. Minor and patch stay below 1000, and the code at or below Play's limit, 2 100 000 000. A tag is never moved or reused (the [tag rulesets](#3-tag-rulesets) block moving or deleting one): when a bundle is refused, or never uploaded, the next attempt is a new patch version. The workflow's run number was the alternative; it rises too, but says nothing about the version.

Gradle reads the version from the property `sleevesight.version`, checks its form (`build-logic/src/main/kotlin/sleevesight/release/ReleaseVersion.kt`), and fails on anything else. Builds other than a release take the version of the release they lead to ([#614](https://github.com/wkusnierczyk/sleevesight/issues/614)):

- CI's builds for an integration branch, `dev-<version>`, pass that version (`.github/workflows/ci.yml`, *Version from the branch*): a push to `dev-0.1.7`, and a pull request into it, build `0.1.7`. CI's other builds, of `main` and of pull requests into it, pass none.
- Without the property, as there and in a local build, the version is that of the newest release notes, `release-notes/<version>.txt` (the highest version, not the latest file), or `0.1.0` when there are none. On `main` that is the latest release; on an integration branch, until its release notes are written as its last pull request, the release before.

A debug build adds `-debug` to the name (`versionNameSuffix`), so a debug APK of `dev-0.1.7` is `0.1.7-debug` in *About → Version* and the tester report, with the code of `0.1.7`. It is also named *Sleevesight Debug* in the launcher, App info and the notification-access list (the string `app_label`, which the debug build sets in `app/src/debug/res/values/strings.xml`; the app's own screens still say *Sleevesight*), and its icon carries the tester report button's bug (made by `assets/logo/src/make_icon.py` with the release icon), as does Home's one-line logo, which draws the bug (`ic_bug_report`) in the record's place, in the words' ink (`LOGO_BUG`, `BuildConfig.DEBUG`; the intro's splash keeps the record, which fades into the bug as it flies onto the header, so the landed logo is the header's, #652; the guide's picture of Home shows the bug too), so that it can be told from the Play build on a phone that has both. CI checks both APKs' labels and version names (`.github/scripts/check-build-identity.sh`): the debug APK's, and the release APK's, still *Sleevesight* and the bare version.

## R8

The release build is minified, optimized and obfuscated by R8, and unused resources are removed (`isMinifyEnabled`, `isShrinkResources`, in the `sleevesight.android-app` convention plugin). The rules are `proguard-android-optimize.txt` and those the libraries ship in their jars:

- kotlinx.serialization keeps each `@Serializable` class's generated serializer, its `Companion` and `serializer()`, an object's `INSTANCE`, and the annotations, in R8's full mode as well. That covers the Navigation routes in `:app` and the wire types of `:ai` and `:sources`.
- kotlinx.coroutines and Ktor keep their atomically updated `volatile` fields, and Ktor keeps its engine containers for `ServiceLoader`.

`app/proguard-rules.pro` holds what those miss, with the reason; it has no rules yet. When it was set up (#56), the release build was checked with the pipeline modules (`:core`, `:net`, `:sources`, `:ai`) wired into `:app` for the test: R8 reported no missing classes, and its `seeds.txt` and `mapping.txt` showed the serializers and their `Companion` lookups of the routes and of Anthropic's wire types kept, and Ktor's OkHttp engine container kept under its own name.

`./gradlew build` assembles the release variant, so every pull request runs R8, and a missing class fails it. A failure only at run time, a lookup by reflection that R8 cannot see, does not: after adding a library or a serializable type that is looked up dynamically, install a release build on a phone and exercise it. The R8 output is in `app/build/outputs/mapping/release/`: `seeds.txt` lists what the rules kept, `usage.txt` what R8 removed, and `mapping.txt` the renaming, which Play Console reads from the bundle to deobfuscate crash reports.

## Signing

Google Play signs the app delivered to phones with the app signing key, which Google keeps (Play App Signing). The bundle is signed with the upload key, which only proves to Play that the upload is ours. Gradle signs a release build when the environment names the upload key in all four of these variables, and leaves it unsigned when none is set, so `./gradlew build` works anywhere; some of them without the others fail the build:

| Variable | Value | In the workflow, from |
|---|---|---|
| `SLEEVESIGHT_UPLOAD_STORE_FILE` | Path to the upload keystore (PKCS12) | The secret `UPLOAD_KEYSTORE_BASE64`, decoded to a file |
| `SLEEVESIGHT_UPLOAD_STORE_PASSWORD` | The keystore's password | The secret `UPLOAD_STORE_PASSWORD` |
| `SLEEVESIGHT_UPLOAD_KEY_ALIAS` | The key's alias, `upload` | The secret `UPLOAD_KEY_ALIAS` |
| `SLEEVESIGHT_UPLOAD_KEY_PASSWORD` | The key's password; for PKCS12, the keystore's | The secret `UPLOAD_KEY_PASSWORD` |

The four secrets are secrets of the `release` environment, not of the repository: only a job that names the environment can read them, only a `v*` tag may run such a job, and each run waits for approval.

## One-time setup

None of this is in the repository, and none of it can be done from a pull request.

### 1. The upload key

Generate it on a trusted machine, outside the repository:

```sh
keytool -genkeypair -v \
  -keystore sleevesight-upload.p12 -storetype PKCS12 \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Sleevesight upload key"
```

`keytool` asks for the keystore's password; a PKCS12 keystore uses the same password for the key. 10 000 days is about 27 years. Keep the `.p12` file and its password in a password manager: the secrets cannot be read back from GitHub. Delete any other copy once the secrets are set.

### 2. The `release` environment

Create it with a required reviewer and a deployment rule that admits only `v*` tags (in the web interface: *Settings*, *Environments*, *New environment*; then *Required reviewers*, and under *Deployment branches and tags*, *Selected branches and tags* with a tag rule `v*`):

```sh
gh api -X PUT repos/wkusnierczyk/sleevesight/environments/release --input - <<EOF
{
  "reviewers": [{"type": "User", "id": $(gh api users/wkusnierczyk --jq .id)}],
  "prevent_self_review": false,
  "deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}
}
EOF
gh api -X POST repos/wkusnierczyk/sleevesight/environments/release/deployment-branch-policies \
  -f name='v*' -f type=tag
```

`prevent_self_review` is off so that the reviewer may approve a run of a tag they pushed. Check the result with `gh api repos/wkusnierczyk/sleevesight/environments/release`.

### 3. Tag rulesets

The environment admits any `v*` tag, and the workflow checks that it is on `main`. That check is a guard against mistakes: it runs from the tagged commit's own workflow, so a tag elsewhere is stopped by the creation ruleset and the environment's approval, not by the check. Two rulesets keep a release tag where it was pushed. The first blocks moving or deleting a `v*` tag, for everyone; the second lets only an administrator create one (in the web interface: *Settings*, *Rules*, *Rulesets*, *New tag ruleset*):

```sh
gh api -X POST repos/wkusnierczyk/sleevesight/rulesets --input - <<'EOF'
{
  "name": "Release tags stay put",
  "target": "tag",
  "enforcement": "active",
  "conditions": {"ref_name": {"include": ["refs/tags/v*"], "exclude": []}},
  "rules": [{"type": "update"}, {"type": "deletion"}]
}
EOF
gh api -X POST repos/wkusnierczyk/sleevesight/rulesets --input - <<'EOF'
{
  "name": "Release tags by administrators",
  "target": "tag",
  "enforcement": "active",
  "conditions": {"ref_name": {"include": ["refs/tags/v*"], "exclude": []}},
  "rules": [{"type": "creation"}],
  "bypass_actors": [{"actor_type": "RepositoryRole", "actor_id": 5, "bypass_mode": "always"}]
}
EOF
```

There are two rulesets because a bypass applies to every rule of its ruleset, and the administrator who may create a tag should still not move one. Role 5 is the repository's *Admin* role. Check them with `gh api repos/wkusnierczyk/sleevesight/rulesets`.

### 4. The secrets

The commands run where the `.p12` is, outside the repository, so each names the repository.

```sh
base64 < sleevesight-upload.p12 | tr -d '\n' | gh secret set UPLOAD_KEYSTORE_BASE64 --env release --repo wkusnierczyk/sleevesight
gh secret set UPLOAD_STORE_PASSWORD --env release --repo wkusnierczyk/sleevesight   # asks for the value
gh secret set UPLOAD_KEY_PASSWORD --env release --repo wkusnierczyk/sleevesight     # the same password
gh secret set UPLOAD_KEY_ALIAS --env release --repo wkusnierczyk/sleevesight --body upload
gh secret list --env release --repo wkusnierczyk/sleevesight
```

### 5. Play App Signing

In Play Console, once the developer account exists:

1. *Create app*: name *Sleevesight*, an app, free, with the declarations it asks for. The package name, `app.sleevesight`, is fixed by the first upload and cannot change afterwards.
2. Tag the remote `main`, not a local branch: `git fetch origin && git tag v0.1.0 origin/main && git push origin v0.1.0`. Approve the run and download the artifact. A tag on a commit that is not on `main`, `dev-*` included (`main` takes squash merges), is rejected by the workflow, and once the rulesets exist it cannot be deleted, so its version is spent.
3. *Test and release*, *Testing*, *Internal testing*, *Create new release*. Play App Signing is on for a new app: keep *Use Google-generated key* for the app signing key. Upload `app-release.aab`. The key that signed this first upload becomes the upload key.
4. *Test and release*, *App integrity*, *App signing*: the *Upload key certificate*'s SHA-256 fingerprint must equal the one the run printed in *Check the signature*.
5. Record it as a variable of the environment, not a secret (a certificate's fingerprint is public), so that every later run fails if the bundle is signed with any other key. Colons and case do not matter:

   ```sh
   gh variable set UPLOAD_CERT_SHA256 --env release --repo wkusnierczyk/sleevesight --body '<the SHA-256 fingerprint>'
   ```

A bundle Play Console takes there closes [#56](https://github.com/wkusnierczyk/sleevesight/issues/56); rolling it out to testers is [#58](https://github.com/wkusnierczyk/sleevesight/issues/58).

### 6. Google Play Developer API

Every bundle after the first is uploaded by the workflow, which acts as a Google service account. GitHub and Google trust each other through Workload Identity Federation: the job's OIDC token, which names the repository and the environment, is exchanged for an access token valid for 15 minutes, so no Google key is stored anywhere. Only a job in the `release` environment of this repository can act as the account. The repository is identified by its numeric id (`1415440615`), which a deleted and recreated repository of the same name would not have. The condition reads the token's `repository_id` and `environment` claims, not its `sub`, whose form depends on the repository's setting: this one uses GitHub's immutable subject, `repo:wkusnierczyk@2981661/sleevesight@1415440615:environment:release`, not `repo:wkusnierczyk/sleevesight:environment:release` (`gh api repos/wkusnierczyk/sleevesight/actions/oidc/customization/sub`).

The pool holds this one provider and serves only the release: the account is granted to every identity in the pool whose environment is `release`, so a provider added to it later would widen that grant. Another use of GitHub's tokens gets a pool of its own.

In a Google Cloud project (any, or a new one; it costs nothing), with `gcloud` signed in:

```sh
PROJECT=<the project id>
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT" --format 'value(projectNumber)')
ACCOUNT=sleevesight-release@$PROJECT.iam.gserviceaccount.com

gcloud services enable androidpublisher.googleapis.com iamcredentials.googleapis.com sts.googleapis.com --project "$PROJECT"
gcloud iam service-accounts create sleevesight-release --project "$PROJECT" --display-name "Sleevesight release"

gcloud iam workload-identity-pools create sleevesight-release --project "$PROJECT" --location global --display-name "Sleevesight release"
gcloud iam workload-identity-pools providers create-oidc sleevesight --project "$PROJECT" --location global \
  --workload-identity-pool sleevesight-release --display-name GitHub \
  --issuer-uri https://token.actions.githubusercontent.com \
  --attribute-mapping 'google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.environment=assertion.environment' \
  --attribute-condition "assertion.repository_id == '1415440615' && assertion.environment == 'release'"

gcloud iam service-accounts add-iam-policy-binding "$ACCOUNT" --project "$PROJECT" \
  --role roles/iam.workloadIdentityUser \
  --member "principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/sleevesight-release/attribute.environment/release"
```

The service account needs no role in the Cloud project; its rights are in Play Console. There, *Users and permissions*, *Invite new users*: the account's address, `$ACCOUNT`, with no account permissions, and under *App permissions* Sleevesight only, with *Release apps to testing tracks*. The production release ([#58](https://github.com/wkusnierczyk/sleevesight/issues/58)) adds *Release to production* when it comes to that.

Then the two variables of the environment (both public: neither grants anything outside the conditions above):

```sh
gh variable set GCP_WORKLOAD_IDENTITY_PROVIDER --env release --repo wkusnierczyk/sleevesight \
  --body "projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/sleevesight-release/providers/sleevesight"
gh variable set PLAY_SERVICE_ACCOUNT --env release --repo wkusnierczyk/sleevesight --body "$ACCOUNT"
```

Until both are set, a run stops after the artifact with an error naming them. A service account's JSON key, kept as a secret of the environment, was the alternative: simpler to set up, but a key that does not expire, and Google's organization policy may forbid creating one.

## A lost or leaked upload key

The app signing key stays with Google, so the upload key can be replaced. Generate a new key as in step 1, export its certificate, and request the reset in Play Console (*App integrity*, *App signing*, *Request upload key reset*):

```sh
keytool -export -rfc -keystore sleevesight-upload-new.p12 -alias upload -file upload_certificate.pem
```

Once Play has accepted it, replace the four secrets and set `UPLOAD_CERT_SHA256` to the new certificate's fingerprint. A leaked key's secrets are removed at once (`gh secret delete <name> --env release --repo wkusnierczyk/sleevesight`), so no run can sign with it while the reset is pending.
