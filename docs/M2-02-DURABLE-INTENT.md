# M2-02a — durable initial Coding intent (partial M2)

Issue #28, stacked on Draft PR #23; both repair PRs #23/#26 still require
separate exact-HEAD Owner merge decisions. Real Kotlin agent execution: **NOT_RUN**.

`CodingAttemptJournal` is an internal storage component, not a launcher or
permission provider. No call from Main, HTTP, intake or scheduler exists.

Within one `BEGIN IMMEDIATE` transaction on the existing SQLite database it:

- Rechecks registered PERSONAL opt-in, QUEUED/NONE workflow CAS and pinned Issue.
- Resolves role assignment/profile/manifest under the same writer reservation.
- Pins exact Issue body hash/base, trusted-caller source tree, policy version,
  assignment version and immutable profile/manifest references.
- Reserves repository and canonical workspace, rejecting overlapping paths and
  legacy attempts without cleanup evidence. A legacy attempt conservatively
  occupies its registered root across all repository IDs; an unknown physical
  scope denies reuse. This is repeated at STARTING. The #35 Runner extension
  releases its reservation only with a known terminal receipt; UNKNOWN retains it.
- Saves PLANNED + context + reservation + CODING phase + event atomically.

`markStarting` repeats policy/binding/workflow/workspace checks and consumes
PLANNED once. STARTING is a durable intent **not a launch permit**. It cannot
prove Git HEAD/tree identity, executable provenance, actual model support,
credential isolation or Owner authorization. The future runtime must verify
those at its own protected effect boundary; neither supplied SHA nor static
filesystem inspection attests provenance or prevents same-UID TOCTOU.

Restart must reconcile existing process evidence first. Where no reliable
reconciliation exists, `holdUnreconciledStarts` converts STARTING/RUNNING to
UNKNOWN + NEEDS_OWNER, retaining reservations and phase. It never kills, retries,
releases or changes UNKNOWN to FAILED. An already-established Owner reason is
preserved. PLANNED has no spawn intent yet and can consume the same guarded CAS.

Schema v5 is additive: contexts reference existing immutable catalog revisions;
triggers prevent context edits/deletes and source identity changes. Existing
v1/v3/v4 migration/restart tests retain pinned data. Tests for this component use
inert mock checkout metadata and disposable SQLite; they are **offline**, not
Coding/Review/E2E or process-cleanup evidence.

The #35 [native Runner](M2-02B-NATIVE-RUNNER.md) adds trusted markRunning and
completeObserved methods in this same journal/events schema. They persist
process facts, release only known terminal claims and advance successful Coding
to VERIFYING. No controller test PASS or runtime permission is inferred.

Remaining M2: HOME runtime/credential attestation + specific
Owner GO, real Coding diff/tests. Remaining M3: frozen-tree independent Review,
bounded corrections and durable Draft publisher/read-back. No WORK/1MT and no Merge.
