# Backlog — план задач DevOrchestrator Kotlin/JVM

**Статус:** DRAFT. Только задачи M0 опубликованы в GitHub; M1–M6 пока остаются предложениями. Номера будущих задач назначит GitHub после согласования M0.

## M0 — проектирование (открытые GitHub Issues)

| Key | GitHub Issue | Depends on | Definition of Done |
|---|---|---|---|
| M0-01 | [#1 Утвердить PRD v1](../../issues/1) | ADR-001 | Статус APPROVED, ясные acceptance criteria |
| M0-02 | [#2 ADR-002: состояния/UNKNOWN/recovery](../../issues/2) | ADR-001 | Инварианты, переходы, аварийные сценарии |
| M0-03 | [#3 ADR-003: permissions/credentials/sandbox](../../issues/3) | M0-02 | Граница Owner/Agent, защита PERSONAL/WORK |
| M0-04 | [#4 ADR-004: GitHub intake и Draft PR](../../issues/4) | M0-02, M0-03 | Идентичность, retries, read-after-write, запрет Merge |
| M0-05 | [#5 ADR-005: Codex/Review/corrections](../../issues/5) | M0-02, M0-03 | Adapter contract, bounded attempts, независимость Review |
| M0-06 | [#6 Утвердить архитектуру, roadmap и E2E-план](../../issues/6) | M0-01..05 | Нет неразрешённых блокирующих решений |

## M1 — Core (предложения, Issues не созданы)

| Key | Issue | Depends on | Definition of Done |
|---|---|---|---|
| M1-01 | Kotlin/JVM + Ktor bootstrap и базовые проверки | M0-06 | CI/build PASS; один запуск сервера без ручного скрипта |
| M1-02 | SQLite schema/migrations/workflow store | M1-01 | Перезапуск сохраняет состояние; миграционные тесты |
| M1-03 | Registry + policy evaluator по ADR-001 | M1-02 | Unknown/WORK DENY, project identity scoped |
| M1-04 | GitHub intake и dedupe | M1-03 | Реальная Issue импортируется однократно |
| M1-05 | Read-only status API/event history | M1-02 | Статус доступен без side effects |

## M2 — Agent Runtime (предложения)

| Key | Issue | Depends on | Definition of Done |
|---|---|---|---|
| M2-01 | Codex adapter + bounded runner | M1-04 | Один настоящий Coding в разрешённом проекте |
| M2-02 | Linux sandbox и worker cleanup evidence | M2-01 | Таймаут/перезапуск не задевают чужие процессы |
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

**Правило:** эти задачи не создаются и не запускаются параллельно. После утверждения M0 уточняем зависимости и открываем только ближайшие готовые задачи реализации.
