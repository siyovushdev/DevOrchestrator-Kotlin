# M3-02 — saved Coding → controller verification → independent Review

Issue #36; stacked on Draft #38 `8b82da6794921fbc99a958b4a5412860568340a7`.
This is an explicit synchronous engine slice for the first PERSONAL Issue, not a service,
permission framework, scheduler, publisher or acceptance of M3. No startup/HTTP hook.

`WorkflowEngine.step()` consumes at most one persisted action. Start with the existing
`VERIFYING` workflow and its successful Coding attempt. Never enqueue/re-run Coding just
to integrate a saved result. The engine imports the #38 journal's actual completion and
process facts, checks their pinned input/profile identities, reads the real CLI session
and terminal events, and independently hashes the saved checkout through a fresh Git index.
A `SUCCEEDED` row or agent's text alone cannot create a verification receipt.

Verification uses a separate `FrozenCandidate` copy, with no `.git` or valuable child env,
and a fixed controller-owned Python `unittest` harness (`-I -B`; discover `tests`). The
minimum executed, non-skipped test count is trusted configuration, pinned with the original
correction limit. Actual command execution, test counts, exit status, tree integrity and
supervisor birth/terminal/ECHILD cleanup must agree. No tests/all-skipped cannot pass.
A known test failure may request correction; timeouts, lost streams, changed files and
incomplete process evidence become UNKNOWN/NEEDS_OWNER and cannot consume a correction.
Tests are still arbitrary generated code: the separately approved disposable OS context
must prevent access to valuable files/credentials; a scrubbed env does not provide that.

After required tests pass, a final frozen candidate binds the coding attempt/session,
base, generation, Git tree and verification identity to a durable manifest hash. The
engine creates a separate REVIEW `agent_attempts` row and immutable `attempt_contexts`
with the current REVIEW assignment/profile/manifest, plus `review_inputs` pinning the
candidate. It uses the existing command planner and the shared #38 Linux process observer.
The CLI receives a new `exec` (never resume), `--sandbox read-only`, and
`--skip-git-repo-check` because its working directory is a separate plain-file snapshot.
Review edits are never copied into Coding or the saved candidate; any snapshot mutation
holds the workflow. The new CLI thread/session must differ from Coding and previous Review.
A structured verdict must match candidate ID, manifest, tree and generation, with concrete
findings for CHANGES_REQUIRED and none for PASS. Bare PASS text is insufficient.

A real current PASS reaches READY_FOR_PR only with no unresolved attempts/reservations
and unchanged workflow/policy. No publication side effect is created here. Existing #34
publication intents remain a separate sender integration under #37.

## Corrections and restart

Initial generation is 0. A known test failure or bound CHANGES_REQUIRED creates one
immutable correction request. `CodingAttemptJournal.planCorrection` atomically consumes
that request, creates the next immutable Coding context/reservation, advances phase by
CAS, and increments `corrections_used`. The next Coding starts from the exact previous
candidate tree and receives saved findings. Maximum: two correction Coding/verification/
Review rounds, or the lower original configured limit. Subsequent constructor arguments
cannot relax the original limit/test contract. Exhaustion holds NEEDS_OWNER.

Schema v7 adds only verification facts, pinned Review inputs and correction requests to
the existing SQLite store. Existing v6 Coding receipts remain readable; no HOME DB was
opened/migrated during development. Immutable evidence and claim release/phase updates
are committed together. Repeated calls after completion do not execute that action again.
The controller must call `recover()` exclusively at restart before resuming: unresolved
STARTING/RUNNING processes and verifications become UNKNOWN, with claims retained.
A PLANNED correction is safe to launch once; an uncertain launch cannot be replayed.
Recovery never clears an Owner hold, resets the counter, deletes artifacts or retries.

## Current live boundary

Owner's preserved second HOME Coding candidate: `~/devorch-smoke-2`, tree
`55e86cfb40966afe68485b1f5fd3ce4c4bb0ca31`; manual offline Python tests 3/3 PASS.
That evidence is acknowledged, not rerun or modified here. Source/fake-child tests and
source review do not equal the product's real independent Review.

Future HOME integration needs a separately scoped Owner GO for the exact reviewed
engine SHA, Samsung host/user, preserved candidate and one new bounded Review process.
Do not invoke `step()` on HOME now: it can launch Coding/Review according to phase.
Prepare a separately approved protected DB copy and new evidence directories inside the
pinned project root, retaining original DB/checkout/artifacts and failed first-run evidence.
Contexts contain immutable absolute paths: copying a DB does not relocate those paths.
The verifier reads the saved checkout and Coding artifacts at their original paths and
hashes only its new snapshot into a new object store; it does not write the saved Git index
or objects. Configure minimumTests=3 and verify the expected phase/attempt/tree before
calling the verification-only entry point. Real Review
requires the existing Codex auth/proxy/isolated OS context; no monetary cap is enforced.
No retry after UNKNOWN. Publisher/E2E additionally need #37, unmerged #26 repairs and
separate publication authority. No Merge is authorized by this document.
