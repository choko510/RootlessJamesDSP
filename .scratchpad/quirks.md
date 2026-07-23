# Quirks

## Android Gradle builds require the bundled JBR and an explicit SDK path

- Environment: Windows workstation with Oracle Java 25 as the default `java`.
- Symptom: Gradle/Kotlin configuration fails with `25.0.2`, then reports that the Android SDK location is missing.
- Workaround: Run with `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` and `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`.
- Status: Applies to this execution environment.

## Git may reject repository checks when ownership differs

- Environment: Windows Codex sandbox with the repository owned by a different Windows user identity.
- Symptom: Git reports `detected dubious ownership` for this repository.
- Cause: Git's `safe.directory` ownership protection.
- Workaround: Use a repository-scoped `-c safe.directory=<repository path>` option for read-only Git checks; do not change global Git configuration for this project.
- Status: Applies to this execution environment.
