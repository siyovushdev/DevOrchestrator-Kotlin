# Roadmap — DevOrchestrator Kotlin/JVM

**Статус:** DRAFT. Этапы последовательные, критерии выхода обязательны.

| Этап | Содержание | Проверяемый результат / gate |
|---|---|---|
| **M0** | PRD, ADR-001..005, acceptance, backlog, угрозы/разрешения | Владелец утвердил документы и стартовый backlog; реализации нет |
| **M1** | JVM/Ktor skeleton, SQLite, события/состояния, GitHub intake, registry | Реальная Issue принимается один раз; restart и неизвестный проект проверены |
| **M2** | Codex Adapter, process lifecycle, sandbox, artifacts, deadlines | Система сама выполнила одну реальную Coding-задачу без ручного запуска Codex |
| **M3** | Independent Review, bounded corrections, Git Publisher, Draft PR | Настоящая Issue дошла до **Draft PR** через автоматическую цепочку; Merge нет |
| **M4** | Persistent service, recovery, monitoring, backups, safety operations | Повторные задачи после restart идут без оператора; recovery не дублирует эффекты |
| **M5** | Android Monitor (read-only) | Проекты, статусы, события, блокеры видны с телефона через защищённый API |
| **M6** | Android Control | Авторизованные Owner approvals/pause, аудит и запрет обхода политики |

## Gate-модель

1. Для каждого этапа существуют GitHub Issues с зависимостями и критериями DONE.
2. Код принимается через PR/Review; тестовые заглушки честно обозначаются MOCK/NOT_TESTED.
3. Нельзя закрывать M3 по offline симуляции — требуется реальный Draft PR.
4. Проблемы предыдущих этапов устраняются внутри своего scope, без параллельного добавления V2.
5. Рабочие проекты 1МТ не используются как тестовая среда новых допусков.

## Отложенные идеи

Многомашинность через Firefly/WORK, GitHub App вместо начального транспорта, MCP-адаптер для ChatGPT, дополнительный AI Chief и расширенные AI-провайдеры — **не MVP**. Возвращаемся к ним только после M4 и отдельных ADR.
