# M4-03 / Issue #47 — SSH executor, stage 1 (NOT_READY for workflow use)

Based on PR #46, exact `3ca5e62e71d86f87a8f2011c514504d4dbcacff0`.
This PR is a reviewable protocol/executor slice. **It does not complete #47.**
`runtimeMode=local` preserves the existing protected runtime. Enabling `ssh-vm`
returns `REMOTE_WORKFLOW_BRIDGE_NOT_READY` before credentials, DB, or execution.
There is no service/HTTP command that submits remote work in this slice.

## Why a separate stage

`NativeProcessRunner` and `WorkflowEngine` bind attempts to local process birth,
local canonical paths and a frozen Git inventory. Replacing the process with SSH
would make the SSH PID look like a model supervisor. Existing UNKNOWN attempts
intentionally cannot become successful by merely supplying a new receipt.

The new internal adapter stores remote facts separately. It never completes an
existing attempt, removes reservations/holds, changes a workflow phase, calls
Publisher or grants a push/POST. `EVIDENCE_STORED` means a transport result was
validated and durably saved; it is **not** Tests PASS or Independent Review PASS.

## Implemented boundary

Controller → one fixed OpenSSH invocation → fixed Python worker command. No shell
arguments, remote paths, SSH options or executable are obtained from Issue text.
Owner-selected private identity/known-host files, explicit host-key checking,
BatchMode, no agent/proxy/config/multiplexing/forwarding delegation. The endpoint
is deliberately only `vboxuser@127.0.0.1:2222`; this is not a general remote shell.

Request pins repository ID `1424152600`, workflow, Issue, attempt, role, generation,
base commit, source tree, worker installation digest, model/effort and limits.
TESTS/REVIEW also pin candidate ID, manifest digest and Coding session ID. A
bounded Git bundle transports the real base object, not controller credentials or
`.git/config`. A complete file image transports candidate bytes/modes. Parent
traversal, `.git`/`.codex`/`.ssh` segments, symlinks, hardlinks and special files
are rejected. Both sides independently compute a Git tree in a fresh private
object store. The controller materializes only into a new disposable directory.

Controller SQLite records SENDING before SSH. Guest fsyncs the claim and its parent
before spawning a detached supervisor. Claims persist even if the caller dies.
The guest and controller reject duplicate attempt/role-generation slots. Changed
bytes under the same attempt ID are an error. A partial/lost reply leaves UNKNOWN;
NOT_FOUND, authentication failure, process death, or reboot never authorize retry.

`reconcile()` sends **only query**. The guest query reads saved files and `/proc`
identity, never runs Git, tests, models or publication. A complete result retains
real guest machine/boot/PID/start-ticks, child exit and subreaper ECHILD evidence.
Results are fsynced and published atomically with no replacement. Missing cleanup
or invalid evidence keeps the hold. SQLite failures propagate as internal errors;
transport diagnostics retain safe exception type/source location without stderr.
History is append-only by the adapter (not a cryptographic audit log).

The guest supports three fixed roles:

- CODING: new `codex exec` session, native workspace permission profile.
- TESTS: existing trusted unittest harness; discovery and every test child run in
  the fixed bubblewrap namespace with read-only source, no model HOME/state,
  private proc/tmp and no network. No candidate import in the trusted parent.
- REVIEW: separate `codex exec`, native read-only workspace profile; new session
  must differ from the Coding session, and source tree must remain unchanged.

There is **no outer bwrap around Codex**. Native permission profiles deny general
filesystem reads, permit minimal system tools and the current workspace only,
and deny tool network. Legacy sandbox flags are deliberately not mixed with
profiles; dedicated user/system `config.toml` / `managed_config.toml` are refused.
Before a model starts, **the same `default_permissions` override list used by exec**
must pass a no-inference `codex sandbox` canary (no separate `-P` selector):
Python cannot read/open for modification an artificial model secret, worker
request/state or installed helper; Coding can write source, Review cannot. A
failed/unsupported native policy never falls back to unrestricted execution.
Inherited managed requirements/configuration still require deployment review.
See [OpenAI permissions documentation](https://learn.chatgpt.com/docs/permissions)
for profile precedence; compatibility with the pinned CLI is tested separately.
The synthetic canary is not a promise that arbitrary VM configuration is safe.

Limits: 24 MiB request/result, 8 MiB file image, 1 MiB/file, 1000 files, 2000 walked
directories, 4 MiB bundle, 64 KiB prompt; 120 s task deadline plus cleanup. Captured
files have a kernel 24 MiB file-size ceiling and a 1 MiB evidence acceptance limit.
This is a bounded pilot, not #44 dynamic limits or a disk/memory quota manager.
All requests/evidence contain untrusted task content and stay in private storage;
never print raw request, stdout or stderr in Owner status messages.

## Still required for stage 2

1. Bind the internal request to the existing persisted Agent Profile/Owner policy
   and approved frozen candidate, rather than trusting an internal caller.
2. Atomic validated artifact import and exact phase transition across existing
   Coding/Tests/Review journals, including crash points. No generic UNKNOWN reset.
3. Bind Review verdict JSON to `FrozenCandidate` / `ReviewEvidencePolicy`, enforce
   session uniqueness against existing workflow history and correction budget.
4. Validate frozen source/manifest again at Publisher; preserve exact-once effects.
5. Add the narrow Owner continuation command and status display for that bridge.

No `EVIDENCE_STORED` record may be consumed by Publisher until these are complete.
Worker receipts are statements from a trusted installed worker over pinned SSH,
not signatures or hardware attestation. OS protection of that worker's files from
native tool commands is a deployment precondition; fake CLI tests cannot prove it.

## Deployment preparation — instructions only, not performed

Do not install/update Samsung, start HOME, edit saved tasks, copy a PAT onto MSI,
or connect this source slice to the autonomous queue. PR #26 remains separate.

After separate Owner/admin approval of placement and a **no-model protocol canary**:

1. Review the exact source SHA. On the guest, install the three unchanged resources
   `ssh-vm-worker.py`, `native-codex-supervisor.py`, `verify-python-unittest.py` into
   `/home/vboxuser/.local/lib/devorch-worker/`. Keep install/config/model-home/state
   outside every writable workspace. Parent paths must be trusted, no symlinks or
   group/other write access. The model identity must not have passwordless sudo or
   another route around the native sandbox. Never bind/share host HOME, WORK/1MT,
   host sockets or host SSH-agent into the guest.
2. Manually create private `~/.config/devorch-worker.json` using the schema in
   `config/ssh-worker.json.example`; canonical root/modelHome must exist, be
   disjoint, root/modelHome private 0700, config 0600. Keep both outside minimal OS mounts (`/usr`, `/etc`, etc.). Use a native absolute Codex executable;
   authenticate only that guest's dedicated model HOME. No controller GitHub PAT.
   Review managed requirements and keep unrelated plugins/MCP/credentials absent.
3. Restrict the dedicated SSH public key to the fixed worker command with OpenSSH
   `restrict,command="exec /usr/bin/python3 -I -S /home/vboxuser/.local/lib/devorch-worker/ssh-vm-worker.py serve /home/vboxuser/.config/devorch-worker.json"`.
   Restrict all forwarding/PTY and do not retain another broad login key for this
   controller identity. Private key and out-of-band verified host-key pin stay on
   the controller only. This code does not create keys or trust new host keys.
4. Owner/admin must enforce and verify **guest → controller/LAN denial** outside
   untrusted code, including NAT host `10.0.2.2`, controller addresses on every
   interface, IPv6 and local services. NAT alone and OpenSSH forwarding options
   do not provide this. Permit only the separately reviewed inference/DNS egress;
   no shared folders/clipboard or bridged access to WORK networks. Do not blindly
   apply a firewall script on the working MSI. If no enforceable boundary exists,
   use another personal host/network and refuse deployment on MSI.
5. Only then run the separately approved disposable protocol canary with fake data
   and the new native read/write canary using no inference. Test lost SSH/restart,
   saved-result query, unchanged canary bytes and blocked controller reachability.
   Do not repeat the already successful SSH/login/sandbox availability checks.

A real Coding → Tests → independent Review → Draft PR experiment needs a **later,
separate Owner GO**, after stage 2 and review. Issue #4 → PR #5 is not rerun.
No automatic merge. #43 cancellation and #44 dynamic limits remain backlog.

## Validation scope

Offline tests use a fake CLI and a local pipe in place of SSH, real disposable Git,
SQLite, Python, bubblewrap and the unchanged supervisor. They cover request/tree
binding, cleanup, two-role command/session separation, lost response while running
and after completion, restart/query/no replay, changed identity, partial response,
missing auth, inherited-env exclusion, bounds, and genuine Python secret canaries.
A separate native CLI canary (no inference, synthetic HOME) checks actual native
permission enforcement. CI pins Codex 0.163.0; fake CLI receipts are not claimed as
proof of native isolation or successful model work. Actual guest/network/SSH
protocol integration and workflow publication remain NOT_TESTED / NOT_READY.
