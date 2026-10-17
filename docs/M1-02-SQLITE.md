# M1-02 — SQLite state store

**Status:** implementation in a separate Draft PR; GitHub CI is the source of build/test truth.

## Scope
- One file-backed SQLite database using Xerial SQLite JDBC 3.53.4.0 and standard JDBC.
- Transactional schema migration v1 with `PRAGMA user_version`, WAL and foreign key checks.
- `BEGIN IMMEDIATE` writer reservation before the first registry SELECT protects concurrent pollers from stale WAL snapshots (`SQLITE_BUSY_SNAPSHOT`); schema-version reads are protected from simultaneous initializers.
- Tables: projects, workflows, agent_attempts, effects_outbox, workflow_events.
- Unique logical task identity `(repository_id, issue_number)`.
- `enqueue` records one QUEUED task and only one event for repeat polls.
- One-way compare-and-set `markNeedsOwner` that retains the workflow phase and persists an audit event atomically.
- Real database restart, duplicate intake, concurrent (six-worker) intake, migration and constraint unit tests.

## Strict boundaries
This is a **persistence layer**, not an execution controller. It does not provide APIs to:
- start Codex, change workflow phases, grant authority, clear NEEDS_OWNER, or send GitHub effects;
- publish PRs or access WORK/1MT repositories;
- turn an agent output into an approved Review or change a past attempt.

Follow-up M1-03 supplies the policy evaluator; later WorkflowEngine supplies guarded transitions.
The SQL tables for attempts and external effects are inert until those components exist.

## Acceptance
- [ ] Exact PR-head CI Gradle tests and build PASS.
- [ ] Reopen SQLite and verify rows/events, concurrent duplicate poll no-op, CAS/version guard.
- [ ] Invalid phase and orphan effects/attempts rejected by SQL constraints.
- [ ] Newer schema_version rejected without destructive downgrade.
- [ ] PR reviewed and Owner authorizes Merge independently.

**No real agent execution, model selection, GitHub polling or publication in this PR.**
