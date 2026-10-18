# M2-01 — Native Codex CLI probe and command planning (partial)

**Status:** Draft implementation only. The first real Codex `exec` attempt has **NOT** run.
The code does **not** auto-start any agents on startup or from GitHub polling.

## What is implemented

1. `NativeCodexCliPreflight` checks trusted SQLite Project Registry:
   stable repository ID, PERSONAL with explicit execution opt-in, exact project
   role/profile snapshot from Agent Catalog, and no WORK access.
2. The first executable profile is confined to a **disposable standalone clone**:
   normalized absolute workspace below registered root, no symlink/realpath
   escape, and an ordinary `.git` directory (linked worktree disallowed for
   this first smoke).
3. Read-only probes through `ProcessBuilder` verify `git rev-parse HEAD` is
   the expected 40-digit source SHA, `git status --porcelain` is clean,
   `codex --version` executes, and `codex exec --help` advertises necessary
   non-interactive flags. Timeouts are **UNKNOWN**, not silent PASS/retry.
4. `CodexExecCommandPlanner` builds an argument **list**, not a shell command,
   with `--sandbox workspace-write` only for CODING and `read-only` only
   for REVIEW. Effort/model and immutable profile revision are explicit.
   No dangerous bypass, auto-Merge or `--skip-git-repo-check` flag.
   Prompt is reserved for stdin, not CLI arguments or environment.
5. A bounded, scrubbed-environment `ProcessBuilder` is used for **probe
   commands only**. `--version`/`--help` are not an inference request.
6. CI uses **FAKE CLI** scripts to test command planning, blocked conditions,
   wrong SHA, dirty clone, unsupported flags and timeout. This must be
   reported as `MOCK`, never as a real Codex execution.

## Critical gates before ANY real `codex exec` on WORK

- Actual installed binary location/version, `exec --help` flags and supported
  model/effort must be verified on WORK, not inferred from declarations.
- Native same-UID `workspace-write` cannot prevent reading other local host
  files or valuable credentials. Existing `~/.codex`, SSH agent,
  `gh auth`, Git credential helpers and shell/environment credentials must
  be inventoried. If sensitive/broad tokens are reachable, block this native
  mode until isolation/authorization is separately agreed.
- Use a disposable PERSONAL test repository, with no private employer code or
  sensitive artifacts, pinned Git base, trusted Owner authorization and a
  credential-scoped process environment.
- M2-02: durable attempt state before spawn, output limits, process tree
  termination, UNKNOWN reconciliation and cleanup. Agent exit code zero does
  NOT establish successful tests or a valid candidate diff.
- M3: separate independent REVIEW session, frozen source tree and guarded
  Draft PR publisher. No automated Merge.
- Project policy must be rechecked at **the effect boundary**, not merely at
  discovery/preflight. The existing `FlagsOnly` result explicitly sets
  `authorizedToLaunch=false` and `modelAndCredentialsVerified=false`.

## Acceptance for this Draft

- JVM tests/CI + GitHub read-only regression + running Ktor smoke pass.
- The CLI probe's compatibility result is clearly marked **FLAGS ONLY**.
- No WORK execution, real installed CLI probe or real `codex exec` is claimed.
- M2-01 remains **in progress** until owner-controlled live test produces
  a checked real file/diff and reliable process evidence.
- PR stays Draft. Merge requires a separate Owner decision.
