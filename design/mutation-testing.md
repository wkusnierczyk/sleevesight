# Mutation testing of the key code

**Date:** 2026-11-20  
**Builds on:** [development.md](development.md), *Dependencies*; [architecture.md](architecture.md), *Testing*  
**Tracking:** `wkusnierczyk/sleevesight-dev#9`; the security milestone, [#217](https://github.com/wkusnierczyk/sleevesight/issues/217)

Sleevesight stores and moves the users' API keys: the CLI's keys file, the setup code the CLI draws (`keys-qr`) and the API keys page makes, and its sealed form, which the phone opens. Tests that only mirror that code would pass whatever it did. Mutation testing ([Pitest](https://pitest.org)) changes the code on purpose, a condition flipped, a bound moved, a call dropped, and checks that some test fails each time: a change no test notices (a *surviving mutant*) is behaviour the tests do not hold.

## What it covers

The convention plugin `sleevesight.mutation` (`build-logic/`) applies Pitest with its JUnit 5 runner, writes HTML and XML reports to `<module>/build/reports/pitest/`, and fails a run whose targets match no class. Each module names its classes and the tests that cover them:

- `:core`: `Secret`; `KeyName`, `KeyShape`, `KeySetup`, `SetupRead` (`core.keys`, `KeySetup.kt`); `SealedSetup` and `Opened`; and `KeyWords`.
- `:cli`: `Keys` (the environment and the keys file), `KeysQr` (`keys-qr`, with `readable`), and `WordsReader` (the words a code is sealed with); and `:core`'s `JvmKeyCrypto` (`jvmMain`), the PBKDF2 and AES-GCM the sealed code uses. Its tests (`JvmKeyCryptoTest`) are in `:cli` because they need JVM APIs, which `:core`'s tests may not use (`config/detekt/pipeline.yml`): one puts a security provider ahead of the JDK's, to check that the password spec PBKDF2 is handed is cleared.

The tests run with `user.home` set to an empty directory under `cli/build/`, under Gradle and under Pitest alike, so that no test, nor a mutant that takes another path through `KeysQr` or `Keys`, can read the developer's keys file (`~/.config/sleevesight/keys.env`).

Left out, for now:

- **`:app`'s key code** (`app.sleevesight.app.secrets`: `SecretStore`, `SecretCipher`, `KeyGate`, `OpenRouterLogin`, `SignInCallbacks`; the QR decoder in `app.ui.scan`). `:app` is an Android module, and Pitest's Gradle plugin needs the Java plugin; the Android variant of the plugin is another dependency and another setup, for a module whose key code sits on the Android Keystore, `SharedPreferences` and Robolectric. Its pure parts (`KeyGate`, the sign-in's PKCE) could move to a JVM module later, which would bring them in.
- **The API keys page** (`assets/api-keys/`), JavaScript: Pitest reads JVM bytecode. The app's tests check it against `:core` by the page's vectors.

## When it runs

`./gradlew :core:pitest :cli:pitest` (a few minutes); not part of `check`, since it runs the tests once per mutant. `.github/workflows/mutation.yml` runs it nightly on `main` and by hand (*Run workflow*), not on pull requests, uploads the reports as the artifact `mutation-reports`, and writes each module's score to the run's summary. Each module has a `mutationThreshold`, its score of 2026-11-20 rounded down (`:core` 97 %, `:cli` 93 %), which Pitest compares with the share of mutants killed: a new mutant that survives fails the run, and the report says which. A test that kills it mends the run, or, for an equivalent one, its reason under *Equivalent mutants* and the threshold set again. A change to the key code adds or removes mutants, and so moves the score: the threshold is checked with it.

## The first run

On 2026-11-20, before the tests added with the plugin, 282 of 334 mutants were killed (84 %): `:core` 134 of 166, `:cli` 148 of 168. The survivors showed gaps: OpenRouter's key shape untested, no key at any length bound, `SealedSetup.isSealed` never false, the wiping of the derived key and the setup text unchecked, AES-GCM's associated data never checked, the CLI's usage, message and flush unchecked, and the word reader's first word, its third try and its read failure. With the tests for them, and with `JvmKeyCrypto` moved to `:cli`'s run and its password spec's clearing tested, 319 of 334 (95.5 %): `:core` 146 of 149, `:cli` 173 of 185. Every mutant left is equivalent, below.

## Equivalent mutants

A mutant is equivalent when no input can tell it from the code: no test can kill it, and none is added. Pitest reports them as surviving or as not covered; each run's report should show only these.

- **Inlined standard library code.** Kotlin inlines `any`, `all` and `count`, so Pitest mutates their bodies inside the caller, at line numbers past the file's end. Each one's `if (this is Collection && isEmpty())` shortcut gives what its loop gives, and `count`'s overflow check needs more than `Int.MAX_VALUE` elements: in `KeyShape.shaped` (`GOOGLE_PREFIXES.any`), the `KeyWords` constructor (`words.all`), and `KeysQr.check` (`from.values.count`, five mutants).
- **A progression's own checks.** `KeysQr.draw`'s `for (y in 0 until matrix.height step 2)` builds a progression whose step is always 2: the mutants of its `step > 0` test, and of the branch for a negative step, which never runs.
- **Wiping a copy no one else holds.** `SealedSetup.openBytes` wipes the decoded code (`bytes.fill(0)`) after reading copies of it, and `JvmKeyCrypto.derive` the password's characters (`chars.fill`), of which `PBEKeySpec` keeps a copy of its own. Each is a local array that nothing returns or keeps, so dropping the call changes no result. The wipes that can be seen are tested: the derived key and the setup code's text (`SealedSetupTest`), and the spec, which a provider is handed and may keep (`JvmKeyCryptoTest`).
- **`Keys.parse`'s empty line.** `isNotEmpty()` with its bound moved lets an empty line through, which the next check, a `=` in the line, drops all the same.
