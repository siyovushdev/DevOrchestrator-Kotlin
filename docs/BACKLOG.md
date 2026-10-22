# Backlog — план задач DevOrchestrator Kotlin/JVM

**Статус:** M0 APPROVED, M1 начат. Создаются только ближайшие Issues реализации; остальное — план.

## M0 — проектирование (GitHub Issues: #2 завершена)

| Key | GitHub Issue | Depends on | Definition of Done |
|---|---|---|---|
| M0-01 | [#1 Утвердить PRD v1](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/1) | ADR-001 | Статус APPROVED, ясные acceptance criteria |
| M0-02 | [#2 ADR-002: состояния/UNKNOWN/recovery](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/2) | ADR-001 | **APPROVED**: R-01–R-06, переходы и F01–F20 (закрыта) |
| M0-03 | [#3 ADR-003: permissions/credentials/sandbox](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/3) | M0-02 | Граница Owner/Agent, защита PERSONAL/WORK |
| M0-04 | [#4 ADR-004: GitHub intake и Draft PR](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/4) | M0-02, M0-03 | Идентичность, retries, read-after-write, запрет Merge |
| M0-05 | [#5 ADR-005: Codex/Review/corrections](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/5) | M0-02, M0-03 | Adapter contract, bounded attempts, независимость Review |
| M0-06 | [#6 Утвердить архитектуру, roadmap и E2E-план](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/6) | M0-01..05 | Нет неразрешённых блокирующих решений |

## M1 — Core (предложения, Issues не созданы)

| Key | Issue | Depends on | Definition of Done |
|---|---|---|---|
| M1-01 | Kotlin/JVM + Ktor bootstrap и базовые проверки | M0-06 | CI/build PASS; один запуск сервера без ручного скрипта |
| M1-02 | SQLite schema/migrations/workflow store | M1-01 | Перезапуск сохраняет состояние; миграционные тесты |
| M1-03 | Registry + policy evaluator по ADR-001 | M1-02 | Unknown/WORK DENY, project identity scoped |
| M1-06 | Agent Catalog/Profile schema, roles/models/effort и versioned config (ADR-005) | M1-02,M1-03 | MANUAL profiles меняются без правки workflow; проверка supported capabilities |
| M1-04 | GitHub intake и dedupe | M1-03 | Реальная Issue импортируется однократно |
| M1-05 | Internal read-only status API/event history (localhost; not external access) | M1-02 | Side-effect-free core status primitives; no external auth claims |
| M1-07 | [#13 Versioned API contract](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/13) для Web/Android/CLI | M1-03,M1-05 | OpenAPI `/api/v1`, resources, commands/errors/events, compatibility tests; design only, no public control endpoints |

## M2 — Agent Runtime (предложения)

| Key | Issue | Depends on | Definition of Done |
|---|---|---|---|
| M2-01 | Native Codex `exec` adapter + короткий real CLI smoke | M1-04,M1-06 | Один настоящий Coding через Kotlin на disposable PERSONAL repo, реальный файл/diff подтверждён |
| M2-02 | Native workspace-write, timeout и child-process cleanup | M2-01 | Нет Docker/systemd sandbox engine; timeout/restart не повторяет UNKNOWN attempt |
| M2-03 | Real verification evidence and logging | M2-01 | PASS/FAIL/NOT_RUN проверяемы |
| M2-04 | Restart recovery for coding attempts | M2-02,M2-03 | Нет повторного старта UNKNOWN attempt |

## M3 — Review и Draft PR (предложения)

| Key | Issue | Depends on | Definition of Done |
|---|---|---|---|
| M3-01 | Independent Review from frozen diff | M2-03 | Новая сессия; immutable source identity |
| M3-02 | Bounded correction loop | M3-01 | Лимит соблюдён, новый review обязателен |
| M3-03 | Publisher: isolated Git + Draft PR | M2-04,M3-01 | Единственный Draft PR, без Merge |
| M3-04 | **Live acceptance: Issue → Draft PR** | M3-02,M3-03 | Реальные Codex/GitHub/Review; после restart нет дублей |

## M4 — эксплуатация (предложения)

| Key | Issue | Depends on | Definition of Done |
|---|---|---|---|
| M4-01 | Постоянный сервис, restart, log rotation | M3-04 | При перезагрузке сохраняет очереди |
| M4-02 | Operational recovery, diagnostics, alerting | M4-01 | UNKNOWN/INCIDENT без ложных success и дублей |
| M4-03 | Personal/work isolation E2E и backup | M4-02 | WORK требует Owner GO, исходные состояния сохраняются |
| M4-04 | Второй реальный Agent Adapter + переключение ролей/модели/effort | M4-03,M1-06 | Два реальных адаптера выбираются в разных задачах; immutable profile version на attempt |
| M4-05 | MANUAL / AUTO_RULES / AUTO_AI task routing и fallback | M4-04 | Политика выбирает поддерживаемый профиль, хранит причину/стоимость; не выдаёт новых прав |

## M5 — Universal API platform (planned Issues, not authorized for implementation)

| Key | Issue | Depends on | Definition of Done |
|---|---|---|---|
| M5-01 | [#14 Secure Monitoring REST + SSE API](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/14) | M1-07,M4-03 | Authenticated read-only project/task/attempt/review/agent/status/events/health endpoints; redaction, pagination, reconnect, no side effects |
| M5-02 | [#15 Secure Owner Control API](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/15) | M5-01,M4-03,WorkflowEngine owner guards | Owner approve/reject, pause/resume/cancel, guarded retry/reconcile, policy/profile updates with RBAC, version CAS, idempotency receipts and audit; deny replay |
| M5-03 | API security and recovery E2E | M5-02 | Remote clients cannot bypass project policy; WORK requires Owner GO; restart and UNKNOWN effects are safe |

## M6 — Universal dashboard and clients (planned)

| Key | Issue | Depends on | Definition of Done |
|---|---|---|---|
| M6-01 | [#16 Web dashboard first](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/16) | M5-03 | Browser shows live queue/status/agent evidence and issues Owner commands exclusively through API, mobile responsive |
| M6-02 | Optional Android app / CLI client | M6-01 | Same `/api/v1` contract with no duplicate backend logic; no GitHub or agent secrets in clients |
| M6-03 | Cross-client acceptance | M6-01 | One task observed and safely controlled through documented API, with audit and no bypass |

**API implementation order:** M1 internal status primitives and versioned contract → M3 first autonomous Draft PR → M4 stable recovery and isolation → M5 secure external read/control API → M6 clients. A thin loopback read-only endpoint may be added earlier, but remote control needs full auth and command gating.

**Explicit exclusions:** arbitrary remote shell, direct DB writes from UI, unsupervised Merge, assigning policy from Issue/agent content, public unauthenticated endpoints.

**Правило:** задачи планируются без параллельного запуска. Выбираем очередную готовую задачу только после зависимостей и отдельного решения о реализации.
