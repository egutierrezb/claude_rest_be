---
name: implement-task
description: Start a task on a fresh branch cut from an up-to-date master, then implement it. Use when the user invokes /implement-task with a description of work to do (e.g. "/implement-task Create a logger in the backend instead of using sysouts statements"), or asks to start new work on its own branch.
---

# implement-task

The argument is the task to implement. Everything before step 4 is setup — do not
skip it and start editing on whatever branch happens to be checked out.

## 1. Check the working tree is clean

```bash
git status --short
```

Uncommitted changes to **tracked** files: stop and ask whether to commit, stash, or
discard them. Do not stash silently — the user may not know they had pending work.

Untracked files are fine to leave in place; they survive the branch switch. Do not
`git add` them as a side effect of this workflow.

## 2. Sync master

```bash
git switch master
git pull
```

**The base branch is `master`, not `main`.** This repo also has a `main` branch left
over from repo creation whose history is unrelated to `master` (`git merge-base`
finds no common ancestor). Branching from `main` produces a branch that shows every
file as new and cannot be merged. If `git pull` reports "no common commits" or the
diff looks like the entire repository, you are on the wrong branch — go back to
`master`.

If `git pull` leaves master behind or conflicted, resolve that before branching.
Never start a task on a stale base.

## 3. Branch

Derive a short kebab-case name from the task — the change, not the mechanism
(`replace-sysouts-with-logger`, not `fix-code`):

```bash
git switch -c <branch-name>
```

Confirm the branch actually points at the synced master before proceeding:

```bash
git log --oneline -1
```

## 4. Implement the task

Do the work the argument describes, at its stated scope. Read the surrounding code
first and match its conventions. If the task is ambiguous in a way that changes what
you build, ask before writing code rather than guessing and rewriting.

If you find an unrelated pre-existing problem along the way, mention it — do not
silently fold it into this branch.

## 5. Verify

```bash
mvn test
```

A single test: `mvn test -Dtest='ClaudeAgentAppTest#someTest'`.

Tests must pass before you report done. If the suite was already failing before your
change, say so explicitly so it does not look like your change broke it.

Where the change is observable at runtime (an endpoint, a startup path, a log line),
exercise it for real — a passing unit test is not proof the app behaves correctly.

## 6. Report

State what changed, what you verified and how, and anything deliberately left out.

**Stop here.** Committing, pushing, and opening a PR are not part of this skill — ask
whether the user wants them. If they do, the PR base is `master`, and
`.claude/rules/pr-comments.md` covers replying to and resolving review feedback.
