# DevOrchestrator — Kotlin/JVM

> **Стадия:** M0 — проектирование. Реализация не начата.  
> **Утверждено:** только [ADR-001 — Project Registry и политики проектов](docs/adr/ADR-001-project-registry.md).

DevOrchestrator — собственная платформа управления задачами разработки и ИИ-агентами.

**Цель MVP:** `GitHub Issue → Coding → Tests → Independent Review → Draft PR` для явно разрешённых личных проектов — без ручного запуска агентов. **Merge остаётся решением владельца**. Рабочие проекты (включая 1МТ) требуют отдельного Owner GO.

Планируемый стек: Kotlin/JVM, Ktor, Kotlin Coroutines, SQLite, Linux на WORK. Android Monitor и Android Control предусмотрены как отдельные этапы M5–M6 после работающего серверного MVP.

## Документы

- [PRD / ТЗ v0.1](docs/PRD.md) — **DRAFT**, требует согласования.
- [Архитектура v0.1](docs/ARCHITECTURE.md) — **DRAFT**.
- [Roadmap M0–M6](docs/ROADMAP.md) — **DRAFT**.
- [Backlog, зависимости и ссылки на M0 Issues](docs/BACKLOG.md) — **DRAFT**.
- [Реестр архитектурных решений](docs/DECISIONS.md).
- [ADR-001: Project Registry](docs/adr/ADR-001-project-registry.md) — **APPROVED**.
- [ADR-002: lifecycle и recovery](docs/adr/ADR-002-lifecycle-DRAFT.md) — **DRAFT**, обсуждаем следующим.

## Этап M0 — согласование, без написания кода

Открыты шесть задач проектирования:

[#1 PRD](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/1) · [#2 ADR-002](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/2) · [#3 ADR-003](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/3) · [#4 ADR-004](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/4) · [#5 ADR-005](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/5) · [#6 Финальная приёмка M0](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/6).

## Правила

1. Никаких Kotlin Coding-задач до явного утверждения M0.
2. Решения DRAFT не считаются утверждёнными; каждое изменение фиксируется через ADR и Git.
3. Только один основной Kotlin-репозиторий оркестратора. Временные изолированные workspace целевых проектов — отдельная управляемая сущность.
4. M3 считается завершённым только после **реального** GitHub → Draft PR, не после фиктивных offline тестов.
5. Запрещены автоматический Merge, самовыдача прав агентам, blind retry неопределённых GitHub эффектов.
6. Старый Python DevOrchestrator остаётся отдельным историческим проектом, не является кодовой базой новой версии.

**Следующий содержательный шаг:** согласовать ADR-002 (жизненный цикл и восстановление после сбоев).
