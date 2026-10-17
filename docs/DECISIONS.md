# Реестр архитектурных решений

**Текущий этап:** M0; реализующий код отсутствует.

| ID | Название | Статус | Примечание |
|---|---|---|---|
| ADR-001 | Project Registry и project-level policies | **APPROVED** | Владелец согласовал в ChatGPT 2026-10-17 |
| ADR-002 | Task lifecycle и recovery | **APPROVED** | R-01–R-06 согласованы 2026-10-17; таблицы переходов/F01–F20. Детали механизмов — ADR-003/004/005 |
| ADR-003 | Sandbox, credentials, trust boundary | **DRAFT** | [Первый проект](adr/ADR-003-security-runtime-DRAFT.md): Docker Sandboxes microVM / Gate S0; выбор runtime не утверждён |
| ADR-004 | GitHub intake/publication/PR identity | **PLANNED** | Не согласовано |
| ADR-005 | Agent execution/review/correction policy | **PLANNED** | Не согласовано |

## Правило статусов

`DRAFT` — предложение; `APPROVED` — согласовано владельцем; `SUPERSEDED` — заменено новым ADR со ссылкой. Рабочие предположения в PRD/ARCHITECTURE не становятся утверждёнными решениями автоматически.
