# Persistent Working Memory

The `.scratchpad/` directory contains persistent working memory for AI agents across sessions. It is committed to Git, but it is not a replacement for Git history.

The purpose of this directory is to preserve context that cannot be reliably inferred from the current codebase or commit history, such as active work, unresolved questions, non-obvious design rationale, reusable lessons, and environment-specific behavior.

Do not use these files as session transcripts, file-by-file changelogs, or duplicated commit summaries.

## Core Principle

Each file has a different retention model.

| File           | Retention model                          |
| -------------- | ---------------------------------------- |
| `current.md`   | Replaceable active state                 |
| `backlog.md`   | Mutable queue                            |
| `completed.md` | Append-only history                      |
| `decisions.md` | Cumulative knowledge                     |
| `lessons.md`   | Cumulative knowledge                     |
| `quirks.md`    | Cumulative knowledge with status updates |

Only `current.md` should be routinely rewritten as work progresses.

The other files preserve knowledge across sessions. They must not be replaced with notes from only the current session.

## Files

| File           | Purpose                                                                           | Update timing                                              |
| -------------- | --------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| `current.md`   | Active work, current status, unresolved questions, and the next action            | Every working session                                      |
| `backlog.md`   | Future ideas, deferred tasks, and unstarted improvements                          | When work is proposed, deferred, started, or rejected      |
| `completed.md` | Concise record of completed work                                                  | When work is completed                                     |
| `decisions.md` | Non-obvious design choices and the reasons behind them                            | When an important decision is made or revised              |
| `lessons.md`   | Reusable lessons, failed approaches, and patterns that should not be rediscovered | When a useful lesson is learned                            |
| `quirks.md`    | Runtime-specific issues, environmental behavior, timing problems, and workarounds | When a quirk is discovered, updated, fixed, or invalidated |

## Session Start

At the beginning of substantial work:

1. Read `current.md`.
2. Confirm that referenced tasks, files, components, and assumptions still exist.
3. Read `decisions.md`, `lessons.md`, `quirks.md`, or `backlog.md` only when relevant to the current task.
4. Do not load every file automatically when the task does not require it.
5. Do not treat old notes as automatically correct. Verify anything that may have become stale.

If `current.md` contains unrelated active work, preserve it unless the task has clearly been completed, abandoned, or superseded.

## Session End

Before ending a session or handing work to another agent:

1. Update `current.md` with the actual current state.
2. Record any new non-obvious design rationale in `decisions.md`.
3. Record reusable findings or failed approaches in `lessons.md`.
4. Record environment-specific behavior and workarounds in `quirks.md`.
5. Add completed work to `completed.md`.
6. Remove completed work from `current.md`.
7. Remove started, completed, or rejected work from `backlog.md`.
8. Remove stale references only after confirming they are no longer valid.

Do not write notes merely because a session occurred. Write only information that will help a future agent continue, avoid a mistake, or understand a decision.

## `current.md`

`current.md` contains only active or immediately relevant work.

Keep it small. Aim for fewer than approximately 40 lines.

It may be rewritten freely as the active state changes. It should not preserve the full history of the task.

Recommended format:

```markdown
# Current Work

## Task: Short task name

- Goal:
- Status:
- Files involved:
- Open questions:
- Next action:
```

Fields may be omitted when they add no value.

### Include

* The current objective
* What is already done
* What remains unresolved
* Relevant files or components
* The next concrete action
* Important blockers
* Assumptions that still require verification

### Do not include

* Long debugging logs
* Full command output
* A chronological session diary
* Completed implementation details
* Commit hashes
* Information already obvious from the code
* Knowledge that belongs in `decisions.md`, `lessons.md`, or `quirks.md`

When no active work remains, use:

```markdown
# Current Work

No active work.
```

## `backlog.md`

`backlog.md` contains only work that has not started.

When an item starts, move it to `current.md` and remove it from `backlog.md`.

When an item is rejected, made obsolete, or intentionally abandoned, remove it. If the reason for rejecting it is important and non-obvious, record that reason in `decisions.md`.

Do not keep the same item in both `backlog.md` and `current.md`.

Backlog items should be concise and actionable. Avoid speculative idea dumps that have no realistic relationship to the project.

## `completed.md`

`completed.md` is an append-only record of completed work.

Add new entries at the top in reverse chronological order. Use the date format `YYYY-MM-DD`.

Each entry should be one or two sentences at most.

Recommended format:

```markdown
# Completed

- 2026-07-23: Added persistent agent working memory under `.scratchpad/` and documented project-wide agent conventions.
```

Do not include:

* Commit hashes
* File-by-file change lists
* Full implementation summaries
* Test logs
* Details already represented by Git history

Existing entries should not be deleted or rewritten merely to make the file reflect the current session.

An old entry may be corrected only when it is factually wrong, misleading, duplicated, or refers to work that was later reverted. Preserve the historical meaning whenever possible.

## `decisions.md`

`decisions.md` records non-obvious technical or product decisions and the reasons behind them.

Record the reason, not merely the implementation.

Good example:

```markdown
## Keep working memory split across multiple files

Separate files allow agents to load only the context relevant to the current task, reducing unnecessary context usage and lowering the risk of unrelated notes influencing implementation.
```

Weak example:

```markdown
## Added six Markdown files

The files were added.
```

A decision entry should answer one or more of these questions:

* What alternatives were considered?
* Why was this option selected?
* What trade-off was accepted?
* What constraint shaped the decision?
* Under what conditions should the decision be revisited?

Do not record obvious naming choices or implementation details that are self-explanatory from the code.

### Update behavior

`decisions.md` is cumulative.

Before editing it, read the existing content.

* Add new decisions without removing unrelated entries.
* If a decision already exists, update or extend that entry instead of creating a duplicate.
* If a decision changes, preserve enough context to explain why it changed.
* Do not silently replace the old rationale with the latest session's opinion.
* If two decisions apply under different conditions, document those conditions rather than overwriting one with the other.

## `lessons.md`

`lessons.md` records reusable knowledge that should prevent future agents from repeating mistakes or rediscovering the same behavior.

Good topics include:

* Approaches that failed and why
* Misleading APIs or assumptions
* Testing patterns that caught real defects
* Safe editing practices
* Project-specific conventions that are easy to miss
* Recurring integration problems

Recommended format:

```markdown
## Avoid broad multiline regex replacements

Broad regex replacements can collapse whitespace or remove unrelated code. Prefer exact-context replacements, then review the resulting diff and run validation.
```

Do not paste large logs, temporary stack traces, or one-off failures with no reusable value.

### Update behavior

`lessons.md` is cumulative.

* Preserve unrelated existing lessons.
* Add a new lesson only when it is meaningfully distinct.
* Merge duplicates into a single clearer entry.
* Extend an existing lesson when new evidence adds useful conditions or exceptions.
* Do not replace the file with lessons from only the current session.
* Do not delete an old lesson merely because it was not relevant to the latest task.

## `quirks.md`

`quirks.md` records behavior tied to an operating system, runtime, framework, external service, timing condition, hardware setup, or other environment-specific constraint.

Where possible, include:

* Environment
* Trigger or precondition
* Symptom
* Confirmed or suspected cause
* Workaround
* Current status
* Whether the issue is still reproducible

Recommended format:

```markdown
## File watcher may miss rapid consecutive writes

- Environment: Windows with VS Code
- Trigger: Multiple generated files are written in rapid succession.
- Symptom: The second file is not detected immediately.
- Cause: Not confirmed.
- Workaround: Write files sequentially and verify their existence before continuing.
- Status: Active.
```

Use explicit status labels when helpful:

* `Active`
* `Mitigated`
* `Resolved`
* `No longer reproducible`
* `Obsolete`

### Update behavior

`quirks.md` is cumulative but may receive status updates.

* Do not delete an active quirk because a workaround was found.
* Update its status and preserve the workaround.
* If behavior differs by environment, add the conditions instead of replacing the old entry.
* Remove an entry only when it is confirmed to be obsolete and no longer useful.
* When an issue is resolved, preserve the cause and resolution if they remain useful to future work.

## Append, Merge, Update, and Delete Rules

For `decisions.md`, `lessons.md`, and `quirks.md`, use the following order of preference:

1. **Append** when the information is new and distinct.
2. **Merge** when the same knowledge already exists.
3. **Update** when an existing entry has gained new evidence, conditions, or status.
4. **Delete** only when the entry is confirmed to be invalid, obsolete, duplicated, or misleading.

When uncertain, preserve the existing entry.

Do not perform full-file rewrites unless structural repair is genuinely necessary.

A session-specific summary must never replace unrelated accumulated knowledge.

## Allowed Deletion Conditions

Existing entries in `decisions.md`, `lessons.md`, or `quirks.md` may be deleted only when at least one of the following is true:

1. The information has been confirmed to be factually incorrect.
2. The referenced code, feature, dependency, or environment no longer exists.
3. The entry duplicates another entry and can be safely merged without losing context.
4. The workaround is obsolete and the final resolution is documented.
5. The entry contains sensitive information that should never have been stored.
6. The user explicitly requests its removal.

Do not delete entries simply because they are old, unrelated to the current task, or absent from the latest session.

## Conflict Handling

When new information conflicts with an existing entry:

1. Do not immediately overwrite the old entry.
2. Verify whether the conflict is caused by different environments, versions, configurations, or task conditions.
3. Preserve both observations when both may be valid.
4. Clearly label confirmed facts, hypotheses, and unresolved contradictions.
5. Replace an old statement only after confirming it is wrong or obsolete.

Example:

```markdown
## Build behavior differs by Node.js version

- Node.js 20: Build completes successfully.
- Node.js 22: Build fails during native dependency installation.
- Cause: Under investigation.
```

This is preferable to replacing the Node.js 20 observation with the newer Node.js 22 failure.

## Sensitive Information

Never store secrets or sensitive values in `.scratchpad/`.

This includes:

* API keys
* Access tokens
* Passwords
* Private keys
* Cookies
* Authorization headers
* Session identifiers
* Production credentials
* Personal data copied from real users
* Private URLs containing embedded credentials
* Secret environment variable values

Use names or placeholders instead:

```text
API_KEY
DATABASE_URL
YOUR_TOKEN
example@example.invalid
```

If sensitive information is discovered in an existing file, remove or redact it immediately and report the issue.

Do not reproduce the secret in the removal note.

## General Rules

Apply the following rules to every file in `.scratchpad/`:

* Do not record commit hashes.
* Do not create file-by-file changelogs.
* Do not duplicate Git history.
* Do not store long conversation transcripts.
* Do not store full terminal logs.
* Do not duplicate the same information across multiple files.
* Remove references to deleted files or removed code after confirming they are obsolete.
* Remove stale workarounds only after confirming they are no longer useful.
* Preserve unrelated existing knowledge.
* Distinguish confirmed facts from assumptions.
* Record information for the next agent, not personal commentary from the current agent.
* Prefer concise entries with enough context to remain understandable.
* Keep dates in `YYYY-MM-DD` format.
* Do not rewrite files solely for formatting consistency.
* Do not turn `.scratchpad/` into a documentation system for the entire project.

## Maintenance

Occasional pruning is useful, but pruning must be conservative.

A maintenance pass may:

* Merge duplicate entries
* Correct factual errors
* Remove obsolete references
* Mark quirks as resolved
* Shorten entries that have become unnecessarily verbose
* Normalize inconsistent headings
* Remove information that is now fully obvious from the code

A maintenance pass must not:

* Replace accumulated knowledge with a current-session summary
* Delete unrelated entries
* Remove historical rationale that still explains the current design
* Remove resolved quirks whose cause or workaround remains useful
* Rewrite every file merely to make formatting uniform

## File Header Recommendation

The following warning may be placed at the top of `decisions.md`, `lessons.md`, and `quirks.md`:

```markdown
> This is a cumulative knowledge file.
> Preserve unrelated existing entries.
> Do not replace it with notes from only the current session.
```

For `completed.md`, use:

```markdown
> Append new entries at the top.
> Preserve previous completion records.
```

For `current.md`, use:

```markdown
> This file contains active work only and may be rewritten as the task changes.
```

## Final Check Before Saving

Before modifying any `.scratchpad/` file, verify:

* Did I read the existing file first?
* Am I preserving unrelated knowledge?
* Am I adding context that is not already obvious from Git or the code?
* Does this information belong in this specific file?
* Am I accidentally writing a session log?
* Am I deleting anything without a valid deletion reason?
* Am I storing a secret or sensitive value?
* Could this entry be merged with an existing one?
* Is the distinction between confirmed fact and assumption clear?
* Will this still be useful to another agent in a later session?
