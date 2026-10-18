# Backlog — план задач DevOrchestrator Kotlin/JVM

**Статус:** M0 APPROVED; M1-01/02/03/04/06 приняты и merged в `main`
(`bb10f0f1ea7f9c15bce5ad616478e3c47f4edc6c`). Открытые задачи отмечены отдельно.
M2 — частичный preflight в Draft PR #23; реальный запуск агента из Kotlin — **NOT_RUN**.
Текущая цель и Owner gates: [#27](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/27).

## M0 — проектирование (GitHub Issues: #2 завершена)

| Key | GitHub Issue | Depends on | Definition of Done |
|---|---|---|---|
| M0-01 | [#1 Утвердить PRD v1](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/1) | ADR-001 | Статус APPROVED, ясные acceptance criteria |
| M0-02 | [#2 ADR-002: состояния/UNKNOWN/recovery](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/2) | ADR-001 | **APPROVED**: R-01–R-06, переходы и F01–F20 (закрыта) |
| M0-03 | [#3 ADR-003: permissions/credentials/sandbox](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/3) | M0-02 | Граница Owner/Agent, защита PERSONAL/WORK |
| M0-04 | [#4 ADR-004: GitHub intake и Draft PR](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/4) | M0-02, M0-03 | Идентичность, retries, read-after-write, запрет Merge |
| M0-05 | [#5 ADR-005: Codex/Review/corrections](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/5) | M0-02, M0-03 | Adapter contract, bounded attempts, независимость Review |
| M0-06 | [#6 Утвердить архитектуру, roadmap и E2E-план](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/6) | M0-01..05 | Нет неразрешённых блокирующих решений |

## M1 — Core (принятые задачи и открытые продолжения)

| Key | Issue | Depends on | Definition of Done |
|---|---|---|---|
| M1-01 | [#7 Kotlin/JVM + Ktor bootstrap](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/7) — **принята**, PR #9 merged | M0-06 | CI/build и running-server smoke PASS |
| M1-02 | [#8 SQLite schema/migrations/workflow store](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/8) — **принята**, PR #10 merged | M1-01 | Durable state и миграционные тесты |
| M1-03 | [#11 Registry + policy evaluator](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/11) — **принята**, PR #12 merged | M1-02 | Unknown/WORK DENY, project identity scoped |
| M1-04 | [#18 GitHub intake и dedupe](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/18) — **принята**, PR #19 merged; review repairs [#25](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/25)/Draft PR #26 **открыты** | M1-03 | Read-only intake; не запуск агента и не публикация |
| M1-06 | [#20 Agent Catalog/Profile revisions](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/20) — **принята**, PR #21 merged | M1-02,M1-03 | Immutable declared profiles; runtime capabilities остаются unverified |
| M1-05 | Read-only status API/event history — **план**, отдельная Issue не создана | M1-02 | Статус доступен без side effects |
| M1-07 | [#13 API contract независимых клиентов](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/13) — **открыта**, не принята | M1-02,M1-03 | Контракт клиентов; не блокирует первый M3 E2E |

## M2 — Agent Runtime (частичная разработка; real execution NOT_RUN)

| Key | Issue | Depends on | Definition of Done |
|---|---|---|---|
| M2-01 | [#22 Native Codex preflight + real CLI smoke](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/22) — **открыта**, Draft PR #23 частичный | M1-04,M1-06 | Реальный Coding через Kotlin **NOT_RUN**; flags/fake tests не равны smoke |
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

## M5 — Android Monitor (предложения)

| Key | Issue | Depends on | Definition of Done |
|---|---|---|---|
| M5-01 | Ktor read API/auth + events | M4-03 | Авторизованный read-only доступ |
| M5-02 | Compose: projects/tasks/status/logs | M5-01 | С телефона видно живую задачу, blocked reason и PR |
| M5-03 | Уведомления о статусах | M5-02 | Без дубликатов и утечки данных |

## M6 — Android Control (предложения)

| Key | Issue | Depends on | Definition of Done |
|---|---|---|---|
| M6-01 | Owner auth + command audit API | M5-03 | Replay/unauthorized rejected |
| M6-02 | Approve/reject/pause с телефона | M6-01 | Контроль политики независимо от UI |
| M6-03 | Live Android-to-server acceptance | M6-02 | Одна задача управляется безопасно с телефона |

**Правило:** открывать ближайшие dependency-ordered задачи по #27, сохраняя отдельные ветки и evidence.
M3 real Coding → independent Review → Draft PR — **NOT_RUN**. Merge только Owner
для конкретного PR/HEAD; реальный Kotlin-triggered agent/publisher требует отдельного
Owner GO после credential/security проверки. WORK/1МТ вне допуска.
