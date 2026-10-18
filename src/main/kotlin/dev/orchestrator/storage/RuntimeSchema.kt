package dev.orchestrator.storage

internal object RuntimeSchema {
    val v6 = listOf(
        """CREATE TABLE publication_plans(
            workflow_id TEXT PRIMARY KEY NOT NULL REFERENCES workflows(workflow_id),
            repository_id INTEGER NOT NULL UNIQUE REFERENCES projects(repository_id),
            plan_json TEXT NOT NULL CHECK(length(plan_json)<=8192)
        )""",
        """CREATE TABLE publication_receipts(
            effect_id TEXT PRIMARY KEY NOT NULL REFERENCES effects_outbox(effect_id),
            evidence TEXT NOT NULL CHECK(length(evidence)<=8192),
            recorded_at TEXT NOT NULL
        )""",
        """CREATE TRIGGER immutable_publication_plan_update BEFORE UPDATE ON publication_plans
            BEGIN SELECT RAISE(ABORT,'immutable publication plan'); END""",
        """CREATE TRIGGER immutable_publication_plan_delete BEFORE DELETE ON publication_plans
            BEGIN SELECT RAISE(ABORT,'immutable publication plan'); END""",
        """CREATE TRIGGER immutable_publication_receipt_update BEFORE UPDATE ON publication_receipts
            BEGIN SELECT RAISE(ABORT,'immutable publication receipt'); END""",
        """CREATE TRIGGER immutable_publication_receipt_delete BEFORE DELETE ON publication_receipts
            BEGIN SELECT RAISE(ABORT,'immutable publication receipt'); END""",
        """CREATE TRIGGER immutable_publication_effect_identity
            BEFORE UPDATE OF workflow_id,kind,idempotency_key,intended_target,expected_sha ON effects_outbox
            WHEN EXISTS (SELECT 1 FROM publication_plans WHERE workflow_id=OLD.workflow_id)
            BEGIN SELECT RAISE(ABORT,'immutable publication effect identity'); END""",
    )

    val v5 = listOf(
        """
        CREATE TABLE attempt_contexts(
            attempt_id TEXT PRIMARY KEY NOT NULL REFERENCES agent_attempts(attempt_id),
            repository_id INTEGER NOT NULL REFERENCES projects(repository_id),
            issue_number INTEGER NOT NULL,
            issue_body_sha256 TEXT NOT NULL CHECK(length(issue_body_sha256)=64),
            base_sha TEXT NOT NULL CHECK(length(base_sha)=40),
            policy_version INTEGER NOT NULL CHECK(policy_version>0),
            assignment_version INTEGER NOT NULL CHECK(assignment_version>0),
            profile_id TEXT NOT NULL,
            profile_version INTEGER NOT NULL,
            adapter_id TEXT NOT NULL,
            manifest_version INTEGER NOT NULL,
            workspace TEXT NOT NULL,
            planned_workflow_version INTEGER NOT NULL CHECK(planned_workflow_version>0),
            FOREIGN KEY(profile_id,profile_version) REFERENCES agent_profile_versions(profile_id,version),
            FOREIGN KEY(adapter_id,manifest_version) REFERENCES agent_manifest_versions(adapter_id,version),
            FOREIGN KEY(repository_id,issue_number) REFERENCES workflows(repository_id,issue_number)
        )
        """.trimIndent(),
        """
        CREATE TABLE runtime_reservations(
            attempt_id TEXT PRIMARY KEY NOT NULL REFERENCES attempt_contexts(attempt_id),
            repository_id INTEGER NOT NULL UNIQUE REFERENCES projects(repository_id),
            workspace TEXT NOT NULL UNIQUE
        )
        """.trimIndent(),
        """
        CREATE TRIGGER immutable_attempt_context_update BEFORE UPDATE ON attempt_contexts
        BEGIN SELECT RAISE(ABORT,'immutable attempt context'); END
        """.trimIndent(),
        """
        CREATE TRIGGER immutable_attempt_context_delete BEFORE DELETE ON attempt_contexts
        BEGIN SELECT RAISE(ABORT,'immutable attempt context'); END
        """.trimIndent(),
        """
        CREATE TRIGGER immutable_runtime_attempt_input
        BEFORE UPDATE OF workflow_id,role,generation,source_tree ON agent_attempts
        WHEN EXISTS (SELECT 1 FROM attempt_contexts WHERE attempt_id=OLD.attempt_id)
        BEGIN SELECT RAISE(ABORT,'immutable runtime attempt input'); END
        """.trimIndent(),
    )
}
