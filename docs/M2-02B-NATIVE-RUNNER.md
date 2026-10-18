# M2-02b — one explicit native Coding Runner (#35)

Code/test scope only. **Real Kotlin model inference: NOT_RUN. Merge forbidden.**
HOME flags-only Codex 0.163.0 is owner-reported 1/1 PASS at #34; see
[#31](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/31#issuecomment-6248995642).
It does not prove model/auth/proxy inference or this new Runner on HOME.

## What runs

`NativeCodingRunner.run(attemptId)` is an internal, synchronous trusted call on
one configured PERSONAL repository/Issue #1, with at most 120 seconds (or the
stricter profile timeout), 1 MiB event/last-message bound and no retries. It reuses
`CodingAttemptJournal`, `SqliteAgentCatalog`, `CodexExecCommandPlanner` and the
bounded Git reader. Main/HTTP/intake do not call it. No new schema, scheduler,
permission framework, adapter abstraction or publisher was added.

Before spawn: current policy/hold/profile/source checks; exact clean HEAD/tree;
new private artifact directory; durable STARTING. RUNNING records PID + Linux
start ticks + boot/machine identity in existing events. HOME/CODEX_HOME and a
minimal trusted PATH are explicit. Only Codex receives HTTP_PROXY/HTTPS_PROXY/
ALL_PROXY and lowercase equivalents, NODE_USE_ENV_PROXY=1 and local NO_PROXY,
pointing to `http://127.0.0.1:10809`. The Python supervisor receives a scrubbed
non-auth, non-proxy environment. No SSH agent/GitHub token is forwarded. Nothing
changes system routes, networking, host credentials or the parent environment.
Happ must already be listening; passing proxy vars is not a connection test.

One small bundled Python/Linux helper establishes `PR_SET_CHILD_SUBREAPER`
BEFORE spawning Codex. Orphaned double-fork/setsid children are adopted; cleanup
signals only its own direct children through pidfds and reaps until waitpid
reports ECHILD. Root exit, process-group emptiness or a sampled process list alone
are not used as proof. See the [Linux manual](https://man7.org/linux/man-pages/man2/PR_SET_CHILD_SUBREAPER.2const.html).
The helper enforces its own deadline even if Kotlin dies. It requires Linux
procfs, pidfds, Python3 stdlib/ctypes and a readable machine-id; no installation,
sudo, service manager, cgroup or sandbox implementation is involved. This is a
process lifetime primitive, **not a filesystem/network/credential isolation boundary**.

Missing helper/stream/identity/cleanup evidence => UNKNOWN + NEEDS_OWNER, retain
claims. Timeout/output-limit/nonzero exit with positive cleanup proof => FAILED
+ NEEDS_OWNER, release the finished process's claim. Thread interruption requests
cleanup and preserves the interrupt flag. Child/helper crashes never trigger a
new attempt. Use existing `holdUnreconciledStarts` after a controller crash;
do not promote a leftover receipt or UNKNOWN to success automatically.

After a clean exit0, recheck Git metadata and unchanged base HEAD/tree, build a
candidate using a separate `GIT_INDEX_FILE`, and save its actual tree OID and
binary diff. Root HEAD/index are not staged/committed. Success persists the tree
receipt + claim release + VERIFYING in one journal transaction. Controller tests
remain NOT_RUN; agent text/exit0 is not test/Review PASS. #36 will consume the
actual candidate with required tests and `FrozenCandidate`/Review receipts;
#37 will use the existing publication journal. No publication occurs here.

## Verification boundaries

CI/default tests use fake Codex executables, real temp Git and actual Linux
processes, including detached double-forks. Cases cover proxy/env/stdin, bounded
output, timeout, interruption, nonzero exit, helper loss/UNKNOWN, Git uncertainty
before spawn, metadata filter injection, concurrent starts, no replay and receipt
transaction rollback. They do not contact OpenAI or the disposable GitHub target.

The supervisor/config/receipts/logs/diff are retained in private local artifacts;
no raw prompt/auth/log content is published to GitHub. Protected controller-owned
staging and the already chosen isolated HOME user remain required: same-UID
adversarial replacement/ptrace/external service effects are not an OS security
guarantee of this code. An unknown helper outcome never proves those effects absent.

CLI 0.163.0 flag support is known from Owner evidence. Requested model/effort/auth
and real Happ inference are only established by the separately approved smoke;
no fallback model is selected. `maxBudgetMicroUsd` is an estimate, **not an enforced
monetary cap**: the CLI runner enforces wall time/output, not dollars, token usage,
CPU/memory or remote request cancellation. Before real invocation Owner must
explicitly accept the one-shot estimate and these limits, or keep execution blocked.
No source/default-flag setting is Owner GO by itself.

## Future one-shot HOME invocation — NOT AUTHORIZED OR RUN NOW

Only after a separate explicit Owner GO covering this exact reviewed HEAD, host
`seva-550P5C-550P7C`, user `devorch-e2e`, one Coding attempt for the fixed target,
120 seconds, model and estimated spend limitation. This command does NOT authorize
Review, publisher or Merge. Narrow single-repo credentials remain an Owner step.

Prepare a new private `$HOME/devorch-smoke` root with a fresh standalone
`checkout` of **siyovushdev/devorch-kotlin-e2e-smoke**, stable repo ID **1424152600**;
Happ must be listening at 127.0.0.1:10809. `HomeCodingSmokeTest` reads Issue #1 and
fresh default-branch SHA through the existing GitHub reader, compares the clone,
and pins one new run.db. Preserve run.db/artifacts after any outcome. If they
already exist, inspect them; do not delete/reset/retry UNKNOWN or consumed Coding.
#26 intake fixes remain a separate unmerged prerequisite for the complete E2E;
this stacked #34-based Runner PR does not silently merge or accept #26.

From this Kotlin branch on HOME, with `DEVORCH_CODEX_MODEL` explicitly chosen and
`DEVORCH_GITHUB_READ_TOKEN` already set privately to a token restricted to that one
repo (never paste/print it):

```bash
# FUTURE ONLY: run once after the separate Owner GO; never run on WORK.
DEVORCH_HOME_CODING_GO=issue35-owner-approved-once \
DEVORCH_HOME_SMOKE_ROOT="$HOME/devorch-smoke" \
DEVORCH_CODEX_MODEL="${DEVORCH_CODEX_MODEL:?Owner must choose the exact model}" \
gradle --no-daemon test --tests dev.orchestrator.agent.nativecli.HomeCodingSmokeTest \
  --rerun-tasks --no-build-cache
```

JUnit must show 1 executed/0 skipped/failures/errors; also inspect persisted
process/cleanup facts, actual candidate.diff/tree and VERIFYING state. Then run
and verify required tests through #36; this smoke's terminal Coding is not an
E2E PASS. CI/default builds skip this opt-in test. Today's remaining live blocker
is the separately agreed bounded HOME test/Owner GO, including model/budget and
single-repo auth readiness, not another architecture project.
