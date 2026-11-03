# Bayit Voice — working instructions

## Communication
- Always talk to me in Hebrew. Code, identifiers, comments, commit messages and file contents stay in English.
- Keep updates short: what you did, what to test, what's next.

## The project
- The full spec is in `SPEC.md`. Read it at the start of every session before doing anything.
- Work phase by phase as defined in SPEC.md section 8. Do not start a new phase until I confirm the current one passed its acceptance checks.
- If something in the spec turns out to be wrong or impractical (an API that changed, a better approach), stop and tell me, and propose an update to SPEC.md. Do not silently deviate from it.
- When a decision changes the spec, update SPEC.md in the same change.

## Generic app — no personal values
- This app is for any Home Assistant user. Never hard-code URLs, tokens, pipeline ids, languages, wake words or device names.
- Never commit credentials. If test values are needed, keep them in git-ignored files only.

## Build and verify
- After every meaningful change: build with `./gradlew assembleDebug`, install on the connected device with `adb install -r`, and check `adb logcat` for errors.
- Fix compile errors and crashes yourself before reporting back. Report only when the build installs and runs.
- Before saying a feature works, verify it on the device. If a step needs me (speaking to the device, approving a permission), tell me exactly what to do and what to look for.

## Code quality
- Kotlin, idiomatic and simple. Small classes with clear responsibilities.
- The background service must stay lightweight: no UI code in it, no per-frame allocations (see SPEC.md section 7).
- All user-facing strings in resources, English and Hebrew.

## Git
- Commit after each working step with a clear English message.
