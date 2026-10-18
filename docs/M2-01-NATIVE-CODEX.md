# M2-01 — Native Codex CLI probe and command planning (partial)

**Status:** Draft implementation only. The first real Codex `exec` attempt has **NOT** run.
The code does **not** auto-start any agents on startup or from GitHub polling.

## What is implemented

1. `NativeCodexCliPreflight` checks trusted SQLite Project Registry:
   stable repository ID, PERSONAL with explicit execution opt-in, exact project
   role/profile snapshot from Agent Catalog, and no WORK access.
2. The flags-only probe accepts a **minimal standalone disposable clone**:
   canonical workspace below registered root, ordinary `.git`, strict local
   `config` allowlist, no `include`/`filter`/`core.worktree`, external
   `commondir`, symlinked Git metadata, custom attributes filter or submodules.
   `TrustedGitCheckoutGuard` scans these BEFORE calling any Git binary.
   Unknown layouts are **DENY**, not auto-fixed.
   Embedded `.git` directories/gitfiles/symlinks are rejected even without
   `.gitmodules`. Before any Git process, a bounded SHA-1 index v2/v3 reader
   rejects mode-160000 gitlinks, unsupported extended entries, sparse/split
   indexes, unknown extensions and corrupt/truncated metadata. Only the inert
   optional TREE cache extension is accepted; index v4 is currently DENY.
   Worktree symlinks and special files are conservatively unsupported.
3. Git commands explicitly bind `--git-dir` and `--work-tree` to the approved
   checkout and check the effective toplevel/git-dir/common-dir before comparing
   the pinned HEAD SHA and clean status. Runtime `core.fsmonitor` and hooks are
   disabled. `codex --version` and `codex exec --help` only check flags.
   Git and CLI timeouts retain **UNKNOWN** at the top-level result, not
   `INVALID_BASE_SHA` or `DIRTY_BASE`.
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

## Optional flags-only probe on WORK

An opt-in integration test in `InstalledCodexCliFlagsProbeTest` creates a **local
disposable Git repository inside JUnit @TempDir**, registers a temporary
PERSONAL policy/profile and checks the actual installed Codex
`--version`/`exec --help` using Kotlin `ProcessBuilder`. It never sends a
prompt to Codex, does not call `codex exec` without `--help`, does not need a
GitHub write credential, and does not operate on employer repositories.

For a trusted owner to invoke manually after reviewing the machine, from
the checked-out M2 branch with Gradle 9.5 and JDK 21 available:

```bash
DEVORCH_CODEX_FLAGS_PROBE=true \
DEVORCH_CODEX_BIN="$(command -v codex)" \
DEVORCH_GIT_BIN="$(command -v git)" \
gradle --no-daemon test \
  --tests dev.orchestrator.agent.nativecli.InstalledCodexCliFlagsProbeTest
```

Expected output only proves `REAL_CODEX_CLI_FLAGS_ONLY_PASS`. Absence of the
environment opt-in means this test is **skipped**, not PASS. The test does
NOT prove auth/model availability, native host credential isolation, Process
Runner safety or successful autonomous code generation. A real Coding smoke
requires a separate authorized plan and evidence; this test is only preflight.

- Regression tests include disposable **real Git** repositories with forbidden
  clean/process filter markers, external `core.worktree`/`commondir`,
  linked Git index, metadata config injection and Git HEAD/status timeouts.
  Tests assert denial **before** filter marker execution.
  The embedded-repository regression has a separate intentionally unsafe
  disposable positive control proving parent status executes the inner clean
  filter assigned through `sub/.git/info/attributes`. Production rejects the
  equivalent fixture before any external Git, with its marker absent. Gitlinks
  without nested metadata and nested gitfiles/symlinks are also covered.
- **Preflight is still not a secure execution boundary:** metadata may be
  changed after static inspection by another same-UID process, and the code
  does not attest checkout provenance or protect readable host secrets.
  Only an owner-controlled protected staging clone and an effect-boundary
  recheck can authorize future native agent execution. No such launch permit
  exists in this PR.

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
