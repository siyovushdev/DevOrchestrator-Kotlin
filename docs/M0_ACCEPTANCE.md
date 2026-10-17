# M0 — приемка архитектурного пакета

**Решение Owner:** APPROVED, 2026-10-17. **Начало M1 разрешено.**  
**Область:** Native PERSONAL test MVP в новом приватном `siyovushdev/DevOrchestrator-Kotlin`. Это разрешение на **разработку Kotlin**, а не на запуск Codex с рабочими токенами или публикацию изменений от имени владельца без фактических проверок.

## Утверждённые решения

- [ADR-001 Project Registry](adr/ADR-001-project-registry.md): неизвестные проекты DENY, для PERSONAL explicit allow, WORK требует отдельные Owner approvals.
- [ADR-002 Lifecycle and Recovery](adr/ADR-002-lifecycle-DRAFT.md): R-01…R-06, state/attempt/effect/hold, verification/review, no blind retry, 20 аварийных сценариев.
- [ADR-003 Native PERSONAL Trust Boundary](adr/ADR-003-native-personal-mvp.md): уже работающий Codex CLI через `codex exec`, временная копия **личного тестового проекта**; Docker и дополнительные Linux-ограничения отложены.
- [ADR-004 GitHub Draft PR](adr/ADR-004-github-mvp-DRAFT.md): polling + repo/issue identity + durable publisher + Draft PR, Merge вручную.
- [ADR-005 Agents and Review](adr/ADR-005-agent-adapters-mvp-DRAFT.md): один AgentAdapter interface, первый настоящий Codex CLI, независимая review session; динамический выбор модели/агента — далее.
- [PRD v1](PRD.md), [ARCHITECTURE](ARCHITECTURE.md), [ROADMAP](ROADMAP.md) утверждены как проектные требования/план; выполнение будущих тестов не подразумевается.

## Измеримый план

1. **M1** — каркас Kotlin/JVM + Ktor + tests/CI, SQLite state, Project Registry, GitHub intake; без запуска опасных агентов.
2. **M2** — короткий smoke настоящего noninteractive `codex exec` на WORK, затем AgentRunner и process evidence.
3. **M3** — настоящий Issue → Coding → Tests → Independent Review → GitHub Draft PR; без auto-Merge и без повторного write после неизвестного ответа.
4. **M4** — эксплуатационная устойчивость, второй adapter и допустимая dynamic agent/model/effort selection.
5. **M5/M6** — Android Monitor/Control после стабильного серверного MVP.

## Допуски и ограничения

- **Запрещено:** WORK/1МТ и проекты с чувствительными данными в native current-UID sandbox; запуск `--dangerously-bypass...`, логирование/передача секретов, автоматический Merge, запуск старого Python DevOrchestrator.
- **Реальный риск:** Codex под тем же Unix UID может читать доступные этому пользователю файлы и credentials. `git worktree` защищает от случайных изменений основного checkout, но не является OS security boundary. Реальный Codex smoke **нельзя проводить**, пока не подтверждено отсутствие доступных опасных токенов или не выбрана более строгая изоляция.
- Контроль публикации нужен отдельный: первым использовать только disposable PERSONAL test repo с узкими credentials.
- После каждого этапа — реальный проверяемый результат и короткий статус PASS/FAIL/NOT_RUN.

## Перенос опыта старого проекта

При возникновении знакомой проблемы **свериться с уже подтверждёнными фактами, паттернами и E2E старого DevOrchestrator**, чтобы не повторять исследование. Это контекстная рекомендация, **не** самостоятельная задача на полный аудит или перенос Python-кода.

**M0 завершаем документами, а не объявляем E2E выполненным.** Код, CI и живые задачи ещё предстоит реализовать и проверить.
