# M1-01 — Kotlin/JVM + Ktor bootstrap

**Status:** Draft PR #9. The real Kotlin agent workflow is not implemented or executed.

## Scope
- Kotlin 2.4.21, Java 21, Ktor 3.5.2, Gradle 9.5.0 for CI.
- A localhost-only Netty server (127.0.0.1), port 8080 by default or `DEVORCH_PORT`.
- `GET /health` responds HTTP 200 and body `OK`.
- Ktor tests verify the health route and that `/api/run` returns 404.
- No credentials, SQLite, GitHub poller, Codex Runner, Docker or publication logic.

## Verification
The GitHub Actions `JVM checks` workflow uses Node.js 24-based actions
(Checkout, Setup Java and Gradle setup, all v5), Java 21 and Gradle 9.5.0:

```bash
gradle --no-daemon clean test build installDist
```

It then starts the **actual installed JVM distribution** on port 18080,
polls `GET /health` for exact `OK`, and stops the server process.
This tests a real Netty HTTP server, in addition to `testApplication`.

Check the workflow result on the current PR head before accepting.
A previous build alone does not prove that the current head passed.

## Local Ubuntu (JDK 21 + installed Gradle 9.5.0)

```bash
gradle clean test build installDist
gradle run
curl -i http://127.0.0.1:8080/health
```

**Gradle wrapper is not included yet.** CI installs pinned Gradle 9.5.0 via
`gradle/actions/setup-gradle`. For repeatable workstation use, add the
**official Gradle Wrapper** with its checked-in wrapper JAR, scripts,
distribution checksum and standard Gradle invocation (or verify an installed
compatible Gradle). Do not create a custom downloader.

## Acceptance boundaries
- [ ] Current PR head: CI build, unit tests and running-server HTTP smoke PASS.
- [ ] Source/diff and workflow reviewed.
- [ ] Standard Gradle Wrapper or compatible workstation Gradle confirmed.
- [ ] Owner approves Merge separately.

**NOT_TESTED:** real `codex exec` on WORK, agent permissions, SQLite and GitHub
publication. Those belong to M2/M3 and need a separate credential preflight
for a disposable PERSONAL test repository.
