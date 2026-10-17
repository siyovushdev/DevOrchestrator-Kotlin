# DevOrchestrator — Kotlin/JVM

> **Стадия:** M1 — Kotlin реализация разрешена (M0 APPROVED 2026-10-17), реальный Codex/GitHub E2E ещё не выполнен.  
> **Утверждено для Native PERSONAL test MVP:** [M0 acceptance](docs/M0_ACCEPTANCE.md), ADR-001/002/003/004/005.

DevOrchestrator — собственная платформа управления задачами разработки и ИИ-агентами.

**Цель MVP:** `GitHub Issue → Coding → Tests → Independent Review → Draft PR` для явно разрешённых личных проектов — без ручного запуска агентов. **Merge остаётся решением владельца**. Рабочие проекты (включая 1МТ) требуют отдельного Owner GO.

Планируемый стек: Kotlin/JVM, Ktor, Kotlin Coroutines, SQLite, Linux на WORK. Android Monitor и Android Control предусмотрены как отдельные этапы M5–M6 после работающего серверного MVP.

**Гибкие AI-агенты (PRD FR-13–FR-18, целевой контракт):** Agent Catalog + Agent Profiles для Coding/Review/Planner/Chief; выбор Codex/Cursor/другого интегрированного агента, модели и reasoning effort вручную или автоматически по задаче. Менять профили можно без остановки сервиса, но новая конфигурация применяется только к следующему attempt. Автоселектор не выдаёт дополнительных прав. Первый E2E — Codex; второй адаптер и AUTO routing — M4.

**Runtime первого MVP:** [ADR-003/S-04 — Native Codex CLI](docs/adr/ADR-003-MVP-native-cli-decision.md) — **APPROVED как стратегия**. Kotlin вызывает уже работающий `codex exec` в отдельной временной копии разрешённого PERSONAL тестового проекта. **Без Docker, microVM, нового Linux-пользователя и собственного sandbox engine.** Под текущим UID нет гарантии изоляции от читаемых файлов хоста; WORK/1МТ в таком профиле запрещён. Docker отложен; [ADR-003 Native](docs/adr/ADR-003-native-personal-mvp.md) утверждён **только** для личных тестовых проектов.

## Документы

- [PRD / ТЗ v1](docs/PRD.md) — **APPROVED**.
- [Архитектура](docs/ARCHITECTURE.md) — **APPROVED** для Native PERSONAL MVP.
- [Roadmap M0–M6](docs/ROADMAP.md) — **APPROVED**.
- [Backlog/Issues](docs/BACKLOG.md) — M1 implementation.
- [Реестр архитектурных решений](docs/DECISIONS.md).
- [ADR-001: Project Registry](docs/adr/ADR-001-project-registry.md) — **APPROVED**.
- [ADR-002: lifecycle и recovery](docs/adr/ADR-002-lifecycle-DRAFT.md) — **APPROVED**; [R-06 — формальные переходы/F01–F20](docs/adr/ADR-002-R06-state-machine-DRAFT.md) — **APPROVED**.

## Этап M0 — APPROVED; реализация M1

Шесть задач проектирования утверждены и закрыты:

[#1 PRD](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/1) · [#2 ADR-002](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/2) · [#3 ADR-003](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/3) · [#4 ADR-004](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/4) · [#5 ADR-005](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/5) · [#6 Финальная приёмка M0](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/6).

## Правила

1. M0 утверждён 2026-10-17. Kotlin-разработка M1 разрешена, реальные агенты/публикация требуют проверок M2/M3.
2. Решения DRAFT не считаются утверждёнными; каждое изменение фиксируется через ADR и Git.
3. Только один основной Kotlin-репозиторий оркестратора. Временные изолированные workspace целевых проектов — отдельная управляемая сущность.
4. M3 считается завершённым только после **реального** GitHub → Draft PR, не после фиктивных offline тестов.
5. Запрещены автоматический Merge, самовыдача прав агентам, blind retry неопределённых GitHub эффектов.
6. Старый Python DevOrchestrator остаётся отдельным историческим проектом, не является кодовой базой новой версии.

**Текущий шаг:** [Issue #7 — M1-01 Kotlin + Ktor bootstrap](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/7). Реальная команда `codex exec` и проверка доступных credentials на WORK нужны до включения agent execution. Старый Python DevOrchestrator не меняется.
