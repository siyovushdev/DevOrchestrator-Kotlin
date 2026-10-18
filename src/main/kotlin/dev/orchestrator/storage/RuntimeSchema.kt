package dev.orchestrator.storage

internal object RuntimeSchema {
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
