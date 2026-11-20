# On-device bench (spike #272, part 2)

A stand-alone Android app that measures what an on-device model costs on a phone: Gemma 4 E2B and
E4B through LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android:0.19.1`), on the GPU or the CPU,
with the app's real insight prompts. It is its own Gradle build, not part of the repository's, and
never ships.

- **Prompts:** `app/src/main/assets/prompts.json`, the insight prompt of every fixture track as
  `InsightPrompt` builds it today (version 13: system prompt plus user message, model knowledge
  off). 77 prompts; track 61 has nothing to narrate. Rebuild with `mac/bench.sh prompts`.
- **Sampling:** temperature 0.6, top_k 64, top_p 0.95, seed 272, at most 4,096 output tokens;
  context (`maxNumTokens`) 8,192. One fresh conversation per prompt.
- **Runs:** *Load only* (weight cache cleared, then a cold and a warm load), *One prompt* (the
  longest), *Set of 10* (spread evenly by length, shortest to longest), *Set of 10, three times*
  (heat), *All 77* (replies for the grounding check).
- **Measured:** load time and the weight cache's size; per reply, prefill and decode tokens and
  tokens/s, time to first token (the runtime's and the wall clock's), total time, battery % and
  charge counter, battery temperature, thermal status and headroom; every two seconds, the same plus
  RSS, peak RSS (`VmHWM`), PSS, native PSS, graphics memory and the native heap.
- **Output**, per run, in `/sdcard/Android/data/app.sleevesight.bench/files/results/<time>-<model>-<backend>-<set>/`:
  `device.json` (phone, settings, brightness), `runs.csv` (one row per reply), `samples.csv`,
  `replies.jsonl` (each reply's text), `summary.json`.

## Build

```
cd tools/ondevice-bench
./gradlew --no-daemon assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

## Run sheet

1. **Model files** (not gated, Apache-2.0 per their model cards), on the Mac:
   - `https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm`,
     3,659,530,240 bytes, SHA-256 `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0`
   - `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm`,
     2,588,147,712 bytes, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`
2. **Install and open once** (opening creates the folder the models go in):
   ```
   adb install -r bench.apk
   adb shell am start -n app.sleevesight.bench/.MainActivity
   ```
3. **Push the models** (a few minutes over USB):
   ```
   adb push gemma-4-E4B-it.litertlm /sdcard/Android/data/app.sleevesight.bench/files/models/
   adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/app.sleevesight.bench/files/models/
   ```
   Reopen the app: its header lists both files with their sizes.
4. **Prepare the phone:** charged to 90 % or more, then **unplugged** (a USB cable charges it, and
   the app warns when it is charging); auto-brightness off and brightness at a fixed middle level;
   other apps closed; the phone at room temperature, out of its case if it has a thick one.
5. **Runs, in this order.** Close the app from Recents between runs, and after each heat run let the
   phone cool until the battery temperature is back near its start (5 to 10 minutes):

   | # | Model | Backend | Run | Rough time |
   |---|---|---|---|---|
   | 1 | E4B | GPU | Load only | 1 to 2 min |
   | 2 | E4B | GPU | Set of 10, three times | 3 to 6 min |
   | 3 | E4B | GPU | All 77 | 6 to 12 min |
   | 4 | E2B | GPU | Load only | 1 min |
   | 5 | E2B | GPU | Set of 10, three times | 2 to 4 min |
   | 6 | E4B | CPU | Load only | 1 to 2 min |
   | 7 | E4B | CPU | Set of 10 | 4 to 10 min |
   | 8 (optional) | E2B | GPU | All 77 | 4 to 8 min |

   The times are guesses from the Mac's speeds; the phone's are what the bench measures.
6. **Pull the results** (plug in again):
   ```
   adb pull /sdcard/Android/data/app.sleevesight.bench/files/results ~/dev/sleevesight/.dev/scratchpad/272/phone-results
   ```
7. **Grounding check** of an *All 77* run, on the Mac, from a worktree:
   ```
   tools/ondevice-bench/mac/bench.sh check ~/dev/sleevesight/.dev/scratchpad/272/phone-results/<run folder>
   ```
   It runs the app's `GroundingCheck` on every reply and writes `checked.jsonl` and
   `checked-summary.txt` into that folder.
8. **Clean up:** `adb uninstall app.sleevesight.bench` removes the app, the models and the weight
   caches.
