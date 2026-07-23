# Quirks

## Git may reject repository checks when ownership differs

- Environment: Windows Codex sandbox with the repository owned by a different Windows user identity.
- Symptom: Git reports `detected dubious ownership` for this repository.
- Cause: Git's `safe.directory` ownership protection.
- Workaround: Use a repository-scoped `-c safe.directory=<repository path>` option for read-only Git checks; do not change global Git configuration for this project.
- Status: Applies to this execution environment.
