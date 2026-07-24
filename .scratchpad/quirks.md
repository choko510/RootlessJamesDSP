# Quirks

## Do not overlap Android native packaging builds

- Environment: Windows workstation building all four Android ABIs.
- Symptom: `stripRootlessFullDebugDebugSymbols` can report missing files or permission errors when a timed-out Gradle invocation is still running and another build starts.
- Workaround: Wait for the active daemon or stop it, then rerun with `--max-workers=1`.
- Status: Applies to this execution environment.

## Android Gradle builds require the bundled JBR and an explicit SDK path

- Environment: Windows workstation with Oracle Java 25 as the default `java`.
- Symptom: Gradle/Kotlin configuration fails with `25.0.2`, then reports that the Android SDK location is missing.
- Workaround: Run with `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` and `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`.
- Status: Applies to this execution environment.

## Macrobenchmark reporting can outlive a completed test on this emulator

- Environment: API 34 emulator with a transport-endpoint backed filesystem.
- Symptom: The instrumentation log reports `finished: float1024TraceSections`, but the Gradle connected-test task can remain blocked while collecting additional JSON and hit the command timeout.
- Workaround: Check `macrobenchmark/build/outputs/connected_android_test_additional_output/**/benchmarkData.json` and logcat before treating the run as a test failure.
- Status: Applies to this execution environment.

## Car Audio macrobenchmark setup is sensitive to emulator task state

- Environment: API 34 emulator using the benchmark release package.
- Symptom: The Car Audio trace scenario can fail before setup with `Rootless power toggle did not appear` while the launcher/task state is stale; this is independent of Kotlin compilation and native build success.
- Workaround: Force-stop the measured package, inspect `dumpsys activity`/logcat, and rerun after returning to MainActivity. Treat the scenario as unverified until its trace JSON is produced.
- Status: Applies to this execution environment.

## Git may reject repository checks when ownership differs

- Environment: Windows Codex sandbox with the repository owned by a different Windows user identity.
- Symptom: Git reports `detected dubious ownership` for this repository.
- Cause: Git's `safe.directory` ownership protection.
- Workaround: Use a repository-scoped `-c safe.directory=<repository path>` option for read-only Git checks; do not change global Git configuration for this project.
- Status: Applies to this execution environment.

## Native Phase 3 changes need a full ABI rebuild

- Environment: Windows Android build with the libjamesdsp gitlink checked out at the reviewed Phase 3 commit.
- Symptom: Kotlin-only tasks can remain up-to-date while native source changes are not exercised.
- Workaround: Run `:app:externalNativeBuildRootlessFullDebug` and the targeted connected instrumentation tests after changing the submodule.
- Status: Applies to this execution environment.
