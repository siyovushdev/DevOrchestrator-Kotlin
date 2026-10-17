# M1-04 — GitHub Issue intake (read-only, no scheduler)

Status: implementation in a **Draft PR**, not accepted/merged. This step adds
one-shot GitHub reads and durable intake only. It cannot run an agent, publish a
branch, push, open a PR, or merge.

## Trust and data flow

1. A future trusted local owner configuration supplies the exact
   `GitHubRepositoryTarget(owner, name, repositoryId)`. **Never** populate it
   from an Issue title/body, repository label or a URL supplied by an agent.
2. `GitHubIssueIntake.pollOnce()` loads the authoritative SQLite project policy.
   Only a registered **PERSONAL** repository with explicit auto-execution opt-in
   may be polled. Unknown, WORK and disabled projects fail before any GET.
3. `GitHubIssueReader` makes **GET-only** requests to the fixed
   `https://api.github.com` host. Exact repository ID and full name must match,
   and the default-branch commit SHA must be a valid 40-digit hex SHA.
4. Poll `issues?state=all`, sorted by created date, up to five 100-item pages.
   GitHub PR objects are excluded. Issue number, stable ID, URL, optional
   repository_url, state and updated_at are validated; malformed data aborts.
   A GitHub failure **does not** produce a successful poll receipt.
5. Fully collect/validate all pages **before** enqueue. Under SQLite
   `BEGIN IMMEDIATE`, re-check the current PERSONAL policy and atomically create
   exactly one QUEUED workflow + immutable source snapshot + events. Persistent
   key `(repository_id, issue_number)` handles parallel polls/restarts.
6. Pinned snapshot: GitHub issue ID, title/body, updated_at, SHA-256 body hash
   and source default-branch SHA. The body is **untrusted task data**.
7. Changed/closed source for an existing nonterminal workflow gets
   `NEEDS_OWNER` once; the frozen input is never rewritten and no automatic
   rerun/cleanup occurs. A pre-existing legacy workflow lacking pinned source
   must be held rather than silently filled in.

## Limits and operational boundaries

- No persistent scheduler / startup poll / service API is enabled in this PR.
  The callable one-shot service is **not** a live deployed integration.
- GitHub read token (if needed for a private repo) is accepted by the controller
  from `DEVORCH_GITHUB_READ_TOKEN`, **not stored** in SQLite and not placed
  in task body or logs. Before any future Codex process launch, scrub token and
  broader credentials from the child environment; same Linux UID remains an
  unsafe host-read boundary and is subject to ADR-003/S-04.
- Five-page hard bound deliberately fails closed when exceeded. A more complete
  paginated sync (cursor/rate limit/recovery) will require separate tests.
- No WORK execution, public API, GitHub writes, token provisioning, or
  automation permission is granted by creating or queuing a workflow.
- This code assumes a future **trusted** caller. The `pollOnce()` method is
  not exposed to HTTP, GitHub Issue text, agent tools, or a public command.
- Read-only tests use deterministic fake GitHub transport. A real private
  GitHub read **from Kotlin on WORK** has not been executed by this PR and is
  `NOT_TESTED`; acceptance of the *live* criterion requires that smoke later.

## Verification

CI checks Gradle compile/test/build and actual Ktor `/health`.
Regression cases: duplicate/restart, six parallel contenders, changed/closed
source, spoofed repository identity, missing permissions, owner revocation
during polling, multipage + PR filtering, oversized source, malformed JSON,
partial GitHub failure and no misleading SUCCESS.

Next: M1-06 Agent Catalog/Profile contracts, then M2 native Codex CLI preflight
and the first controlled GitHub+Codex end-to-end path. No Merge without
separate Owner approval.
