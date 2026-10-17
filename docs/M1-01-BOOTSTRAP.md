# M1-01 — Kotlin/JVM + Ktor bootstrap

**Status:** implementation candidate in a Draft PR; no live agent execution.

## Deliberate scope
- Kotlin 2.4.21, JDK 21, Gradle 9.5.0 on CI; Ktor 3.5.2.
- `main()` starts a Ktor Netty server **on localhost only** (127.0.0.1), default port 8080.
- GET `/health` returns HTTP 200 and body `OK`.
- Negative test verifies that an execution endpoint is not present.
- No GitHub credentials, SQLite, Codex CLI, sandbox setup, runtime approvals or external effects.

## Commands
```bash
gradle test
gradle run
curl -i http://127.0.0.1:8080/health
```

A Gradle wrapper is **not included in this first patch**: CI provisions pinned Gradle 9.5.0 via `setup-gradle`. Before approving M1-01 for regular workstation use, generate and review the **standard Gradle wrapper** (including the checked wrapper JAR, scripts and distribution SHA), or use an installed compatible Gradle. Do **not** create a custom download-and-execute wrapper.

## Acceptance
- [ ] CI Gradle test/build PASS (not independently observed when this draft was authored).
- [ ] GET /health 200 OK confirmed on actual JVM.
- [ ] Review source and GitHub workflow.
- [ ] GitHub PR reviewed and approved by Owner before Merge.

**Real `codex exec` on WORK remains NOT_TESTED.** Native agent integration happens in later milestones, after credential preflight for a disposable PERSONAL test repository.
