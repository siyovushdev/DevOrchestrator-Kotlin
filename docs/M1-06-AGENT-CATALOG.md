# M1-06 — Agent Catalog & Profile Revisions (Kotlin Core)

Implementation branch: `feat/m1-06-agent-catalog-profiles`.
Owner acceptance and Merge remain separate decisions.

## What M1-06 implements

- `AgentCapabilityManifest`: immutable, typed owner-declared adapter/provider, supported
  roles, model/effort combinations and native sandbox profiles. Each revision is retained.
  **Declarations are NOT tested CLI/model capabilities or permission to launch.**
- `AgentProfile`: immutable versioned project/role + adapter manifest revision + provider,
  model, effort, sandbox, timeout and estimated max budget. It is not a credential store
  or an active job. SQLite preserves old revisions on restart.
- `SqliteAgentCatalog` (schema v4): trusted-owner configuration primitives
  `declareManifest`, `saveProfile`, `bindRole`, immutable reads and audit.
  New versions are appended, never overwritten. A separate versioned per-project
  role assignment decides which exact profile snapshot will apply **to a future
  attempt**. Merely editing a profile does not rebind the role.
- Both `CODING` and `REVIEW` can use the same declared adapter if they have
  *different* role-scoped profiles/sessions. CODING uses native personal workspace
  write; REVIEW/PLANNER/CHIEF use native personal read-only.
- Validation rejects undeclared model + effort, unsupported role/sandbox,
  changed project/role identity, stale CAS, missing project, invalid IDs/limits
  and all WORK native agent profiles. Assigning a role does not override
  `ProjectRegistry`, grant Owner GO, or authorize GitHub publication.
- SQLite v3 to v4 migrations are additive and preserve pinned Issue/workflow
  data. Immutable version rows are protected against SQL UPDATE and DELETE
  by SQLite triggers.
- Owner configuration events are written with actor, revision, target and timestamp;
  no-op/stale changes have no audit amplification.

## Security and runtime boundaries

1. **Owner auth is not implemented here.** `ownerActorId` is an audit identifier
   supplied by a future authenticated/trusted controller. It cannot prove identity.
   Never make these write functions available directly to Issue content, agents or
   unauthenticated HTTP endpoints.
2. **No agent is installed/launched by this PR.** No ProcessBuilder, remote execution,
   Codex interaction, CLI arguments, SSH, network or GitHub write endpoints.
3. **All profile resolutions report `DECLARED_UNVERIFIED`.** M2 must implement
   real adapter preflight (CLI flags/version, installed model/effort compatibility,
   auth, sandbox, credential exposure) and prove immutable profile snapshot pinned
   to each new attempt. An existing RUNNING/UNKNOWN attempt is not changed by a
   new profile or role binding.
4. **WORK is denied** for current native PERSONAL profiles. A future stronger
   isolation strategy plus workflow-scoped Owner grants must be separately approved.
5. Budget and timeout are **stored configuration, not enforcement**. M2 process
   runtime must implement bounded execution and independent evidence collection;
   storing values must not be reported as runtime safety.
6. Reviewer independence is achieved later in M3 by a *new separate process/session*
   with frozen candidate tree, not by the existence of a REVIEW role enum.
7. Existing M1-04 GitHub read and `/health` stay intact. No scheduled auto intake,
   auto correction, Draft PR or Merge is added here.

## Acceptance checks

- Save manifest + Coding profile + Review profile and independently assign them.
- Unsupported model/effort/role/native sandbox and all WORK profiles fail closed.
- Profile version 2 does not affect assigned version 1 before explicit role rebind.
- Existing version 1 remains readable after rebinding, restart and manifest v2.
- Concurrent CAS binding produces exactly one winner; stale/no-op writes do not
  create repeated event records.
- Full v3-schema fixture with pinned GitHub Issue migrates to v4 without changes
  to the workflow, Issue body or PERSONAL policy.
- GitHub CI build/tests and existing live read-only GitHub test (if CI reruns
  under its explicit gating) pass on the final PR HEAD.
- No Merge without separate owner authorization.
