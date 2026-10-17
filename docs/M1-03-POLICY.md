# M1-03 — Project Registry / Policy acceptance notes

Scope: pure policy preflight and SQLite schema v2. No remote API, native agent launch,
GitHub polling or publisher is enabled by this PR.

## Trusted rules implemented

- Repo is keyed by stable GitHub repository ID; unknown IDs are denied.
- Registered PERSONAL projects start with auto-execution **OFF**. Enabling it is a
  trusted-owner storage change using CAS/version + durable before/after audit.
- WORK projects can be discovered but **execution and publication are denied**
  regardless of project registration. A one-time Owner GO must be workflow/attempt
  scoped; a project-wide remembered approval is forbidden.
- Authoritative ProjectRegistry loads the stored policy and rejects malformed
  registrations and workspaces. Directly supplied Issue/agent policy objects are not
  an authorization input.
- SQLite v1 migrates transactionally to v2 with prior task rows retained and
  policy default DENY. Corrupt legacy root paths fail closed on registry lookup.
- Registration and changed owner policy each create a versioned audit record.
  Stale and no-op updates do not create new versions or audit entries.

## Gates that remain OUTSIDE this PR (must not be skipped)

1. **Owner authentication**: `ownerActorId` is only a recorded identity parameter,
   not proof of authenticated Owner access. A future trusted controller must
   authenticate/authorize it before calling the store.
2. **WORK Owner GO**: implement workflow-scoped, durable approval records and
   separate publication grants. Never replace with a permanent WORK allow flag.
3. **Enforcement**: SQLite enqueue is only a storage primitive; `QUEUED` does
   not imply permission to launch an agent. WorkflowEngine/GitHub intake MUST use
   the authoritative policy guard before any external side effect, and re-check
   revision and owner grants at the effect boundary.
4. **Filesystem confinement**: current workspace check is **lexical only**.
   Symlink/realpath checks, race-safe workspace access and native process isolation
   must be enforced by M2 runtime; do not interpret this preflight as host
   filesystem isolation.
5. **GitHub source identity**: GitHubGateway must derive repository ID from a
   trusted GitHub response, not user text. CI does not verify a live WORK host.
6. **Review independence**: current source inspections are from ChatGPT, not an
   independent external AI reviewer. Record this as NOT_RUN until separately done.

The PR may be code-reviewed and tested in isolation; it is not permission to
enable WORK, publish PRs, expose control endpoints or merge automatically.
