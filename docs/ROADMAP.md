# Roadmap — DevOrchestrator Kotlin/JVM

**Статус:** DRAFT. Этапы последовательные, критерии выхода обязательны.

| Этап | Содержание | Проверяемый результат / gate |
|---|---|---|
| **M0** | PRD, ADR-001..005, acceptance, backlog, угрозы/разрешения | Владелец утвердил документы и стартовый backlog; реализации нет |
| **M1** | JVM/Ktor skeleton, SQLite, GitHub intake, Project Registry + Agent Catalog/Profile schema | Реальная Issue принимается один раз; роли и agent profiles валидируются и сохраняются |
| **M2** | Минимальный Native Codex CLI Adapter (`codex exec`), изолированный временный PERSONAL worktree, artifacts/exit/cleanup | Реальный `codex exec` запущен из Kotlin и создал проверенный diff; Docker не устанавливается |
| **M3** | Independent Review, bounded corrections, Git Publisher, Draft PR | Настоящая Issue дошла до **Draft PR** через автоматическую цепочку; Merge нет |
| **M4** | Persistent service/recovery + второй реальный Agent Adapter и auto routing между разрешёнными ролями/моделями/effort | После restart задачи продолжаются; 2 адаптера и AUTO_RULES/AUTO_AI E2E без смены прав |
| **M5** | Android Monitor (read-only) | Проекты, статусы, события, блокеры видны с телефона через защищённый API |
| **M6** | Android Control | Авторизованные Owner approvals/pause, аудит и запрет обхода политики |

## Gate-модель

1. Для каждого этапа существуют GitHub Issues с зависимостями и критериями DONE.
2. Код принимается через PR/Review; тестовые заглушки честно обозначаются MOCK/NOT_TESTED.
3. Нельзя закрывать M3 по offline симуляции — требуется реальный Draft PR.
4. Проблемы предыдущих этапов устраняются внутри своего scope, без параллельного добавления V2.
5. Рабочие проекты 1МТ не используются как тестовая среда новых допусков.

## Отложенные идеи

Многомашинность через Firefly/WORK, GitHub App вместо начального транспорта, MCP-адаптер для ChatGPT и обязательный AI Chief — **не MVP**. **Подключаемый Agent Catalog и динамический выбор поддерживаемых агентов/моделей/effort — часть серверного MVP (M1–M4)**. Произвольные ещё не интегрированные провайдеры не обещаются.
