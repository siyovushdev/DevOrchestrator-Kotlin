# Реестр архитектурных решений

**Текущий этап:** M0; реализующий код отсутствует.

| ID | Название | Статус | Примечание |
|---|---|---|---|
| ADR-001 | Project Registry и project-level policies | **APPROVED** | Владелец согласовал в ChatGPT 2026-10-17 |
| ADR-002 | Task lifecycle и recovery | **APPROVED** | R-01–R-06 согласованы 2026-10-17; таблицы переходов/F01–F20. Детали механизмов — ADR-003/004/005 |
| ADR-003 | Sandbox, credentials, trust boundary | **DRAFT (S-04 Native MVP APPROVED)** | [S-04 Native Codex CLI](adr/ADR-003-MVP-native-cli-decision.md) заменяет Docker S-01/S-02 для первого MVP; Docker и Gate S0 отложены; native profile только PERSONAL test repo |
| ADR-004 | GitHub intake/publication/PR identity | **DRAFT** | [Минимальный GitHub polling → Draft PR](adr/ADR-004-github-mvp-DRAFT.md), ожидает решения Owner |
| ADR-005 | Agent Adapter/Review/Profiles | **DRAFT** | [Native Codex CLI/Review + расширяемые профили](adr/ADR-005-agent-adapters-mvp-DRAFT.md), ожидает решения Owner |

## Правило статусов

`DRAFT` — предложение; `APPROVED` — согласовано владельцем; `SUPERSEDED` — заменено новым ADR со ссылкой. Рабочие предположения в PRD/ARCHITECTURE не становятся утверждёнными решениями автоматически.
