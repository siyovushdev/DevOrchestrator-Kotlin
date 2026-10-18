package dev.orchestrator.storage

internal object RuntimeSchema {
    val v7 = listOf(
        """CREATE TABLE workflow_verifications(
            workflow_id TEXT NOT NULL REFERENCES workflows(workflow_id),
            generation INTEGER NOT NULL CHECK(generation BETWEEN 0 AND 2),
            coding_attempt_id TEXT NOT NULL UNIQUE REFERENCES agent_attempts(attempt_id),
            policy_version INTEGER NOT NULL,
            minimum_tests INTEGER NOT NULL CHECK(minimum_tests BETWEEN 1 AND 10000),
            correction_limit INTEGER NOT NULL CHECK(correction_limit BETWEEN 0 AND 2),
            state TEXT NOT NULL CHECK(state IN ('STARTING','SUCCEEDED','FAILED','UNKNOWN')),
            evidence TEXT,
            PRIMARY KEY(workflow_id,generation)
        )""",
        """CREATE TABLE review_inputs(
            attempt_id TEXT PRIMARY KEY NOT NULL REFERENCES attempt_contexts(attempt_id),
            workflow_id TEXT NOT NULL,
            generation INTEGER NOT NULL,
            candidate_id TEXT NOT NULL,
            manifest_sha256 TEXT NOT NULL,
            candidate_tree TEXT NOT NULL,
            candidate_directory TEXT NOT NULL,
            coding_session_id TEXT NOT NULL,
            FOREIGN KEY(workflow_id,generation) REFERENCES workflow_verifications(workflow_id,generation)
        )""",
        """CREATE TABLE correction_requests(
            workflow_id TEXT NOT NULL REFERENCES workflows(workflow_id),
            generation INTEGER NOT NULL CHECK(generation BETWEEN 0 AND 1),
            source_tree TEXT NOT NULL,
            feedback TEXT NOT NULL,
            PRIMARY KEY(workflow_id,generation)
        )""",
        """CREATE TRIGGER immutable_verification_input
            BEFORE UPDATE OF workflow_id,generation,coding_attempt_id,policy_version,minimum_tests,correction_limit
            ON workflow_verifications BEGIN SELECT RAISE(ABORT,'immutable verification input'); END""",
        """CREATE TRIGGER immutable_verification_terminal BEFORE UPDATE ON workflow_verifications
            WHEN OLD.state!='STARTING' BEGIN SELECT RAISE(ABORT,'immutable verification result'); END""",
    ) + listOf("workflow_verifications","review_inputs","correction_requests").map { table ->
        "CREATE TRIGGER immutable_"+table+"_delete BEFORE DELETE ON "+table+
            " BEGIN SELECT RAISE(ABORT,'immutable workflow evidence'); END"
    } + listOf("review_inputs","correction_requests").map { table ->
        "CREATE TRIGGER immutable_"+table+"_update BEFORE UPDATE ON "+table+
            " BEGIN SELECT RAISE(ABORT,'immutable workflow input'); END"
    }

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
