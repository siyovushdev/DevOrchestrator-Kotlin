# M3-03 — saved verified result → one Draft PR (#37)

Source implementation stacked on Draft #39 `00d1d2e573288f9537fb7d4bc535cac5c09060ea`.
The Owner reports genuine Samsung Coding → controller Tests → fresh Review PASS and
`READY_FOR_PR/NONE`, generation0, saved tree `55e86cfb40966afe68485b1f5fd3ce4c4bb0ca31`.
See [live evidence](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/31#issuecomment-6252554905).
Those HOME artifacts were neither opened nor modified during this source implementation.
**Actual publisher/test-repo GitHub writes: NOT_RUN. No new model runs or Merge.**

## The explicit call

`DraftPrPublisher.publish()` consumes the existing frozen candidate, verification,
Coding/Review/process/session receipts and #34 publication journal. It never calls
`WorkflowEngine.step`, Coding, Review, a scheduler or HTTP entry point. It is fixed to
PERSONAL `siyovushdev/devorch-kotlin-e2e-smoke` ID1424152600 / Issue1 / main; the trusted
caller supplies the approved base SHA (live target `0121b178ded841a041492c850b38a3abe37e0512`).
No URL, command, title or credential is taken from Issue or model prose.

The Publisher reopens and checks the saved frozen files/manifest and exact binding;
requires successful controller test counts and process evidence, separate Review PASS
with bound candidate/generation/profile/session and terminal cleanup, current PERSONAL
policy/version, pinned source/Issue and quiet overlapping resources. READY_FOR_PR alone
is insufficient. These are protected controller receipts, not cryptographic process or
OS isolation attestation; #39's unittest semantics/isolated-user limitations still apply.

A new private staging directory under the registered root imports only local base objects
from the guarded saved source. It hashes the frozen files into its own Git object store
and requires the exact saved tree. The original source HEAD/index/objects, candidate and
Review artifacts remain read-only. A fixed author/committer, epoch timestamp, parent,
workflow/candidate/manifest message determine the commit OID before the first send intent.
This metadata is intentionally reproducible, not the wall-clock time of model execution.
Only the staging store receives the commit object. Existing #34 COMMIT/PUSH/DRAFT_PR
intents and exact read-back receipts are used; no schema or parallel permission database.

Before **each** effect: current saved evidence, policy/resources and explicit trusted
Owner-GO callback are checked again; GitHub API confirms stable repository identity,
unchanged main SHA, expected head state and no existing PR. Local guards and GO are
repeated after network prechecks. No blanket launch grant is inferred from READY or SENDING.

Git push uses a fixed HTTPS URL and a create-only empty expected-ref lease
(`--force-with-lease=refs/heads/<deterministic-head>:`). Despite the Git option's name,
this never updates/rewrites an existing branch, even one permitting fast-forward,
and never uses unconditional `--force`. Git may treat an already-identical ref as
up-to-date despite the empty lease. Publisher therefore additionally requires exactly
one `*` porcelain record with the exact SHA/ref and `[new branch]`; `=`/missing/other
records hold UNCERTAIN before PR creation. A preflight
404 alone would have a race. The [Git push contract](https://git-scm.com/docs/git-push)
defines the empty expected value. The result is always read back through the GitHub
ref and commit APIs (exact OID/tree/sole parent), not inferred from exit0.

The HTTPS credential is supplied only to the Publisher HTTP client and supervised Git
push process, through a fixed askpass helper. Private process env values are omitted
from persisted supervisor config and logs. Git has a scrubbed HOME/env, no inherited
SSH agent/GH tokens/user credential helpers/global Git config or redirects. No Codex
process is launched with this authority. Env separation is not protection from another
same-UID process; the dedicated isolated HOME user remains the real environment boundary.
A request has finite connect time, an explicit 20-second full-response deadline with
async cancellation (JDK21's request timeout alone ends at headers), and a body bounded
while receiving; no
application send retry or redirect forwarding. Interrupted/failed sends are uncertain.

A single POST uses `draft=true`, fixed base/head, and controller-generated provenance.
The [GitHub PR API](https://docs.github.com/en/rest/pulls/pulls#create-a-pull-request)
response is insufficient: list `state=all` by exact head (without a base filter), then
GET the single PR and verify URL/number/repository identities/open/unmerged/draft/base/
head SHAs. Closed, duplicate, wrong-base or malformed entries block another POST.
Only three confirmed effects and a final exact read-back permit DRAFT_PR_CREATED.
No merge/update/delete/force-overwrite endpoint exists.

## Failure/restart

Persist SENDING before each effect. A missing response, timeout, mismatched receipt or
local/remote ambiguity leaves UNCERTAIN + NEEDS_OWNER and retained intents. No blind
retry, branch deletion or second PR. Use `recover()` only under exclusive controller
restart; it holds existing unreconciled sends through the existing journal. A currently
active SENDING run is not reconciled or resent by a concurrent publish call.

`reconcile(kind)` performs reads only against the known plan and exact expected receipt.
It never sends, clears the hold, advances terminal workflow or relaxes policy. A matched
lost Push/PR can be confirmed while NEEDS_OWNER remains for explicit Owner inspection.
Absent/mismatched evidence stays uncertain; never reset the DB or delete its artifacts.
Confirmed steps can be skipped after a known clean stop; each remaining send still
requires current evidence/GO. A consumed terminal call returns its stored URL without
another send. Recovery cannot make failed/missing old model evidence pass.

## Future final Samsung publication — NOT AUTHORIZED OR RUN NOW

Current HOME PAT/deploy keys are READ-ONLY. No credential upgrade was performed here.
After independent source review and exact-head CI, Owner must separately approve one
bounded Publisher call on the Samsung host/user `seva-550P5C-550P7C` / `devorch-e2e`,
this exact implementation HEAD, the saved tree/base above and disposable repo only.
Provide a **new fine-grained PAT for that repository only**, Metadata(read),
Contents(write), Pull requests(write), and a reasonable expiry; confirm the prepared
isolated context still has no valuable WORK authority. Never paste/print the token.
[GitHub permissions](https://docs.github.com/en/rest/pulls/pulls#create-a-pull-request)
are an Owner provisioning step; this code does not attest token scope from its prefix.

Preserve `~/devorch-smoke-2/run.db`, `review-run.db`, source and all Coding/Review
artifacts. On HOME, use SQLite's backup API (including WAL) to create a **new**
`~/devorch-smoke-2/publication-run.db` from READY_FOR_PR `review-run.db`; no file-copy of
a live SQLite/WAL pair. Create a new private `publisher-artifacts/` under the same root.
Existing immutable paths still point to the preserved evidence; do not relocate it.
Inspect exact gen0 READY_FOR_PR/NONE, no publication plan, tests3+ PASS, Review PASS,
base/tree and no pending runtime claims. The opt-in test repeats these critical checks.

Only after separate Owner GO, from the reviewed implementation on HOME:

```bash
# FUTURE ONLY. DEVORCH_PUBLISH_TOKEN is already set privately; do not echo it.
DEVORCH_HOME_PUBLISH_GO=issue37-owner-approved-once \
DEVORCH_PUBLISH_DB="$HOME/devorch-smoke-2/publication-run.db" \
DEVORCH_PUBLISH_ROOT="$HOME/devorch-smoke-2/publisher-artifacts" \
gradle --no-daemon test --tests dev.orchestrator.publish.HomePublicationSmokeTest \
  --rerun-tasks --no-build-cache
```

Expected evidence: JUnit1/1, DRAFT_PR_CREATED/NONE in publication-run.db, all three exact
receipts CONFIRMED, one actual Draft PR URL with reviewed tree/base/head, and a repeated
terminal call with no second push/POST. No new Coding/Review or Merge. Preserve everything
on any outcome; UNKNOWN/UNCERTAIN means read-only inspection/reconciliation, not rerun.
Default/CI skip this test. #26 intake repairs remain separate/unmerged; local compatibility
checks are not permission to merge or alter the saved live result. Full real E2E is only
PASS after this separately authorized publisher and remote read-back succeed.
