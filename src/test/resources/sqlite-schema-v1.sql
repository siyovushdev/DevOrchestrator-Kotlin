-- Frozen complete schema-v1 DDL from main M1-02 (five tables, three indexes).
CREATE TABLE projects(
    project_id TEXT PRIMARY KEY NOT NULL,
    repository_id INTEGER UNIQUE NOT NULL CHECK(repository_id > 0),
    kind TEXT NOT NULL CHECK(kind IN ('PERSONAL', 'WORK')),
    workspace_root TEXT NOT NULL,
    policy_version INTEGER NOT NULL DEFAULT 1 CHECK(policy_version > 0)
);

CREATE TABLE workflows(
    workflow_id TEXT PRIMARY KEY NOT NULL,
    repository_id INTEGER NOT NULL REFERENCES projects(repository_id),
    issue_number INTEGER NOT NULL CHECK(issue_number > 0),
    phase TEXT NOT NULL DEFAULT 'QUEUED'
        CHECK(phase IN (
            'QUEUED', 'CODING', 'VERIFYING', 'REVIEWING', 'READY_FOR_PR',
            'PUBLISHING', 'DRAFT_PR_CREATED', 'FAILED', 'CANCELLED'
        )),
    hold TEXT NOT NULL DEFAULT 'NONE'
        CHECK(hold IN ('NONE', 'PAUSED', 'NEEDS_OWNER')),
    hold_reason TEXT,
    version INTEGER NOT NULL DEFAULT 0 CHECK(version >= 0),
    corrections_used INTEGER NOT NULL DEFAULT 0
        CHECK(corrections_used BETWEEN 0 AND 2),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(repository_id, issue_number)
);

CREATE TABLE agent_attempts(
    attempt_id TEXT PRIMARY KEY NOT NULL,
    workflow_id TEXT NOT NULL REFERENCES workflows(workflow_id),
    role TEXT NOT NULL CHECK(role IN ('CODING', 'REVIEW')),
    generation INTEGER NOT NULL CHECK(generation >= 0),
    state TEXT NOT NULL CHECK(state IN (
        'PLANNED', 'STARTING', 'RUNNING', 'SUCCEEDED', 'FAILED',
        'UNKNOWN', 'CANCELLED'
    )),
    source_tree TEXT,
    recorded_at TEXT NOT NULL,
    UNIQUE(workflow_id, role, generation)
);

CREATE TABLE effects_outbox(
    effect_id TEXT PRIMARY KEY NOT NULL,
    workflow_id TEXT NOT NULL REFERENCES workflows(workflow_id),
    kind TEXT NOT NULL CHECK(kind IN ('COMMIT', 'PUSH', 'DRAFT_PR')),
    state TEXT NOT NULL CHECK(state IN (
        'PREPARED', 'SENDING', 'CONFIRMED', 'REJECTED', 'UNCERTAIN'
    )),
    idempotency_key TEXT NOT NULL UNIQUE,
    intended_target TEXT NOT NULL,
    expected_sha TEXT,
    recorded_at TEXT NOT NULL
);

CREATE TABLE workflow_events(
    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
    workflow_id TEXT NOT NULL REFERENCES workflows(workflow_id),
    event_type TEXT NOT NULL,
    detail TEXT,
    recorded_at TEXT NOT NULL
);

CREATE INDEX idx_workflow_events ON workflow_events(workflow_id, event_id);

CREATE INDEX idx_attempts_workflow ON agent_attempts(workflow_id);

CREATE INDEX idx_effects_workflow ON effects_outbox(workflow_id);
