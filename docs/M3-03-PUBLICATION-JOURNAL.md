# M3-03a — publication intent / exact receipt journal

Issue #33, stacked on #32. **Persistence only; real Publisher/E2E NOT_RUN.**

`PublicationJournal` reuses the shared SQLite outbox. Schema v6 adds immutable
publication plans and full matched read-back receipts. A plan pins workflow,
repo ID/full name, Issue, base ref/SHA, deterministic head branch and commit OID,
candidate ID/tree/manifest digest, Coding/Review IDs, generation and policy version.
The head ref encodes the entire workflow ID with URL-safe unpadded base64,
avoiding ref hazards such as trailing dots, `.lock` or `..` without collisions
from sanitization or truncation.

`prepare` serializes the current PERSONAL policy, READY_FOR_PR/NONE/version,
pinned Issue base and succeeded Coding/Review rows (Review source tree matches
candidate). It refuses active/UNKNOWN repo attempts and legacy/uncertain effects.
It atomically inserts one plan and exactly three PREPARED effects. An identical
repeat is an idempotent read, not a renewed authorization. A changed plan cannot
replace existing evidence. For the first E2E, a plan conservatively reserves its
repository indefinitely: **no reservation release API** exists in this slice.

A future controller must establish actual verified test/review/source/session/
process-cleanup evidence before setting READY_FOR_PR/PUBLISHING. This journal
cannot establish those facts from AttemptState.SUCCEEDED or supplied digests.
Tests arrange those states directly in disposable SQL as **MOCK completed runtime**;
no production API currently performs that transition. No new phase-changing API
is exposed by this PR. Current M2 STARTING storage does not grant runtime authority.

`markSending` is a one-way durable intent CAS, never a sender or publish permit.
It rechecks PERSONAL policy version, PUBLISHING/NONE/generation, terminal attempt
rows and the supplied trusted verified manifest digest. PUSH requires confirmed
COMMIT; DRAFT_PR requires confirmed PUSH. The trusted Publisher must ALSO enforce
current protected candidate integrity, all required test/review/cleanup evidence,
repository/ref identity, process/resource ownership, credential scope and #31
specific Owner GO immediately before an external effect. A fabricated digest or
state row must never be passed as evidence by an Issue/agent/public endpoint.

Only exact trusted read-back observations can confirm:

- Commit OID, tree and the sole expected base parent.
- Push repository ID/full name, exact branch and commit OID.
- Exactly one PR with canonical URL/number, repo/head repo, head branch/OID,
  base branch/SHA and `draft=true`.

The matched full receipt is persisted, not merely HTTP success or agent prose.
Repeated confirmation must exactly equal the persisted full receipt; a second
singleton PR with a different number/URL is not the same confirmed effect.
A mismatch retains SENDING; the caller must reconcile or hold, never resend.
`holdUnreconciledSends` makes unresolved sends UNCERTAIN + NEEDS_OWNER and retains
all intents. Confirmation after read-only reconciliation does not clear the
Owner hold. There is no retry/rejection/release helper and no automatic terminal
workflow advancement; the integration engine still owns those decisions.

Offline tests verify exact receipts/sequence, concurrent idempotency and one
send CAS, policy/manifest refusal, wrong/ambiguous/non-Draft PR, lost-response
restart and held confirmation, legacy/WORK refusal, rollback, immutable history
and additive v5 migration. No Git commit/push, HTTP write or credentialed sender
runs here. #31 blocks real native execution/publication; no WORK/1MT or Merge.
