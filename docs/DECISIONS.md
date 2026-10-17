# Реестр архитектурных решений

**Текущий этап:** M1 — реализация начата (owner authorized 2026-10-17); реальные Codex/GitHub E2E ещё не выполнены.

| ID | Название | Статус | Примечание |
|---|---|---|---|
| ADR-001 | Project Registry и project-level policies | **APPROVED** | Владелец согласовал в ChatGPT 2026-10-17 |
| ADR-002 | Task lifecycle и recovery | **APPROVED** | R-01–R-06 согласованы 2026-10-17; таблицы переходов/F01–F20. Детали механизмов — ADR-003/004/005 |
| ADR-003 | Native PERSONAL MVP trust boundary | **APPROVED (limited scope)** | [Native-only policy](adr/ADR-003-native-personal-mvp.md); WORK/1MT denied; Docker archived |
| ADR-004 | GitHub intake/publication/PR identity | **APPROVED** | [MVP polling / scoped Draft PR](adr/ADR-004-github-mvp-DRAFT.md); actual E2E deferred to M3 |
| ADR-005 | Agent Adapter/Review/Profiles | **APPROVED** | [Codex exec + independent Review](adr/ADR-005-agent-adapters-mvp-DRAFT.md); second adapter in M4 |

## Правило статусов

`DRAFT` — предложение; `APPROVED` — согласовано владельцем; `SUPERSEDED` — заменено новым ADR со ссылкой. Рабочие предположения в PRD/ARCHITECTURE не становятся утверждёнными решениями автоматически.
