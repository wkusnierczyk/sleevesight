# Releasing Sleevesight

**Date:** 2026-10-16  
**Builds on:** [architecture.md](architecture.md), *CI*; D7 (distribution through Google Play)  
**Tracking:** [#56](https://github.com/wkusnierczyk/sleevesight/issues/56)

How a release is built and signed for Google Play, and the one-time setup it needs outside the repository. Play's review, the testing tracks and the store listing are [#58](https://github.com/wkusnierczyk/sleevesight/issues/58) and [#57](https://github.com/wkusnierczyk/sleevesight/issues/57).

## Contents

- [The release workflow](#the-release-workflow)
- [Versioning](#versioning)
- [R8](#r8)
- [Signing](#signing)
- [One-time setup](#one-time-setup)
- [A lost or leaked upload key](#a-lost-or-leaked-upload-key)

## The release workflow

`.github/workflows/release.yml` runs on a pushed tag `v<major>.<minor>.<patch>`, for example `v0.1.0`. Its one job runs in the GitHub environment `release`, which holds the upload key, so it waits for a reviewer's approval before it starts. It then:

1. decodes the upload key from the environment's secrets into the runner's temporary directory;
2. builds `./gradlew :app:bundleRelease -Psleevesight.version=<the tag without its v>`, shrunk by R8 and signed with the upload key;
3. removes the key file, checks that the bundle is signed, and prints the signing certificate's SHA-256 fingerprint;
4. uploads the bundle and the R8 mapping as the workflow artifact `sleevesight-<version>`, kept 90 days.

It does not run the tests: the tagged commit has passed `build` on its pull request and on its branch. Tag a commit on `main`. The bundle is uploaded to Play Console by hand; uploading through the Play Developer API can come later.

## Versioning

The tag gives both versions, so a release needs no commit to bump them:

- `versionName` is the tag without its `v`: `v1.2.3` gives `1.2.3`.
- `versionCode` is `major × 1 000 000 + minor × 1 000 + patch`: `1.2.3` gives `1002003`.

Google Play needs every upload's `versionCode` higher than any before it. This code rises with the version, can be read back as the version, and needs no state such as a counter. Minor and patch stay below 1000, and the code at or below Play's limit, 2 100 000 000. A tag is never moved or reused: when a bundle is refused, or never uploaded, the next attempt is a new patch version. The workflow's run number was the alternative; it rises too, but says nothing about the version, and a rerun of the same tag would get a new code.

Gradle reads the version from the property `sleevesight.version`, checks its form (`build-logic/src/main/kotlin/sleevesight/release/ReleaseVersion.kt`), and fails on anything else. Without the property, as in every build but a release, the version is `0.1.0`.

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

### 3. The secrets

```sh
base64 < sleevesight-upload.p12 | tr -d '\n' | gh secret set UPLOAD_KEYSTORE_BASE64 --env release
gh secret set UPLOAD_STORE_PASSWORD --env release   # asks for the value
gh secret set UPLOAD_KEY_PASSWORD --env release     # the same password
gh secret set UPLOAD_KEY_ALIAS --env release --body upload
gh secret list --env release
```

### 4. Play App Signing

In Play Console, once the developer account exists:

1. *Create app*: name *Sleevesight*, an app, free, with the declarations it asks for. The package name, `app.sleevesight`, is fixed by the first upload and cannot change afterwards.
2. Push a tag (`git tag v0.1.0 && git push origin v0.1.0` on `main`), approve the run, and download the artifact.
3. *Test and release*, *Testing*, *Internal testing*, *Create new release*. Play App Signing is on for a new app: keep *Use Google-generated key* for the app signing key. Upload `app-release.aab`. The key that signed this first upload becomes the upload key.
4. *Test and release*, *App integrity*, *App signing*: the *Upload key certificate*'s SHA-256 fingerprint must equal the one the run printed in *Check the signature*.

A bundle Play Console takes there closes [#56](https://github.com/wkusnierczyk/sleevesight/issues/56); rolling it out to testers is [#58](https://github.com/wkusnierczyk/sleevesight/issues/58).

## A lost or leaked upload key

The app signing key stays with Google, so the upload key can be replaced. Generate a new key as in step 1, export its certificate, and request the reset in Play Console (*App integrity*, *App signing*, *Request upload key reset*):

```sh
keytool -export -rfc -keystore sleevesight-upload-new.p12 -alias upload -file upload_certificate.pem
```

Once Play has accepted it, replace the four secrets. A leaked key's secrets are removed at once (`gh secret delete <name> --env release`), so no run can sign with it while the reset is pending.
