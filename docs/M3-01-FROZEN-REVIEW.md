# M3-01a — frozen candidate integrity and Review evidence contract

Issue #30, stacked on #29. **Offline artifact slice; real Review/E2E NOT_RUN.**

`FrozenCandidate.capture` creates a new snapshot, copies bounded regular files,
retains executable mode and writes a version-2 canonical manifest bound to the
explicit candidate ID (also validated against the directory name), workflow,
Coding attempt/session/generation, trusted base/tree IDs and verification
status/evidence. Top-level `.git` directory is excluded. Nested Git metadata,
symlinks, hardlinks, special files, path traversal, overlapping storage and
existing destinations are rejected. Failures preserve incomplete artifacts;
there is no deletion or overwrite helper. Linux POSIX file metadata is required.

The manifest digest must be stored in trusted durable controller state.
`reopen(directory, expectedManifestSha256)` verifies against that external digest;
reading a digest supplied by the snapshot itself would not establish trust.
`isIntact` rechecks candidate/directory identity, file inventory/content/size/executable mode and manifest. `__pycache__` and `*.pyc` are not part of that comparison.
Review receipts include the candidate ID; distinct captures cannot share a
receipt even with otherwise identical content and Coding bindings.
Snapshot mutation (including changes by a reviewer) invalidates eligibility;
no reviewer edits are copied back into the candidate.

This is integrity checking, **not OS immutability**. Same-UID attackers can race
or modify accessible files; protected staging and environment isolation remain
required. Provided Git tree/base IDs are controller bindings, not calculated Git
object attestations. The future controller must independently prove that the
captured candidate corresponds to the exact Git tree and verified tests. This
slice does not invoke Git, model inference, a reviewer process, or a publisher.

`ReviewEvidencePolicy` rejects self/stale/mismatched/NOT_RUN receipts and required
verification FAIL/NOT_RUN. Independent attempt/session IDs are necessary but do
not authenticate a fresh process: a trusted runner must establish session
provenance and terminal/cleanup evidence. A consistent PASS yields only
`ELIGIBLE_FOR_CONTROLLER_CHECKS`, never a launch/publish permit or workflow phase
change. Changing ID strings cannot independently establish a valid review.

Correction disposition uses the existing durable `WorkflowSnapshot.correctionsUsed`
value, requires the same Coding generation, and never permits more than two
corrections (or the stricter project limit). **No correction counter/phase is
changed here**; atomic consumption/restart and a new Coding+Review pair belong to
the runtime integration engine after verified process receipts.

Offline tests cover durable manifest reopen, content/mode/inventory corruption,
link and path rejection, resource bounds, self/stale receipts, verification
status and correction bounds. They are not evidence of an actual independent
runtime Review. The external collaboration reviews of these PRs verify source
changes only and do not satisfy the product's real M3 E2E gate.

Next dependencies: #31 Owner environment/repository decision, actual native
runner/cleanup + source/test evidence, then integrated bounded corrections and
credential-scoped durable publisher. No WORK/1MT, auto-start or Merge.
