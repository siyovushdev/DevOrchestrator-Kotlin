# ADR-003 — безопасность без повторения permission-блокировок

**Статус:** DRAFT / PROPOSAL, требуется согласование владельца.  
**Этап:** M0, [Issue #3](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/3).  
**Основа:** ADR-001 и ADR-002 — APPROVED. **Не разрешает написание кода, создание runtime grants или запуск агентов.**

## 1. Цель

У DevOrchestrator должны быть реальные границы безопасности, не блокирующие уже подтверждённое выполнение. Избегать самодельного многослойного управления Linux sandbox, множества несовместимых конфигураций и вручную создаваемых per-Issue launch scripts/approvals. Ошибка прав — диагностируемый отказ **до потребления попытки**, а не повод очищать state.

## 2. Предлагаемые границы доверия

| Роль | Разрешено | Не разрешено |
| --- | --- | --- |
| Owner | Утверждать/менять Project Registry и WORK approvals через аутентифицированный API | Выдавать полномочия текстом Issue/ответом агента |
| Kotlin Controller | Сверять project/repo ID, управлять workflow, durable attempts/effects и политиками | Исполнять произвольную shell-команду из Issue; считать Codex PASS авторизацией |
| AgentRunner (Coding) | Только изолированная копия выбранного проекта, нужные build tools, inference | Доступ к 1МТ/Production, чужим workspace, токенам публикации и контроллеру |
| Independent Review | Read-only/frozen diff/tree, отдельная сессия и необходимые read-only проверки | Подменять результат Coding, изменять reviewed tree, публиковать |
| Trusted Publisher | После проверенных gates — exact Commit/Push/Create **Draft PR** по одной разрешённой ветке | Исполнять agent commands, force push, Merge, произвольные GitHub операции |

Интерфейсное разделение Kotlin-классов не равно изоляции ОС: worker не должен иметь тот же доступ к секретам Publisher через общие файлы/UID/процессы. Потребуются проверенные OS/VM credentials boundaries.

## 3. Project policy и жизненный цикл полномочий

- **PERSONAL:** явно разрешённый проект автоматически проходит Coding → Review → Draft PR, без per-Issue Owner GO и ручных запусков, в рамках заранее утверждённой project policy.
- **WORK:** start и publication требуют отдельных Owner GO; неизвестные проекты default DENY.
- Execution permission применяется только для актуальной попытки; completed Coding/Review сохраняют подтверждённые результаты. Его истечение **не аннулирует** reviewed tree, PASS и исторические receipts.
- Publication разрешается **отдельной policy** на основании точной актуальной Issue/repo/tree/branch, успешных Verification и независимого Review; не требует «продлить старое execution разрешение».
- Подтверждённые старые attempts не становятся новыми grants. При UNKNOWN действует ADR-002: scoped hold + доказательный recovery, без blind retry.
- Для одного ресурса один авторитетный владелец переходов/исполнения; SQLite — долговременный источник состояний, а не набор внешних lock/claim JSON-файлов.

## 4. Не писать собственный sandbox engine

**Кандидат A:** поддерживаемый sandbox в изолированной microVM (например, Docker Sandboxes `sbx`, если подтверждены совместимость KVM/Ubuntu, управление CLI, credentials, ресурсы и clone-mode).

**Кандидат B:** Codex native sandbox `workspace-write` под отдельной низкопривилегированной OS-учётной записью и минимально необходимым systemd confinement. Не наслаивать дополнительный bubblewrap/AppArmor/ProcSubset без подтверждённой совместимости.

Решение A/B пока **НЕ принято**. Ранее зафиксированные блокеры systemd/`/proc/self/exe`, двойного sandbox и авторизации — обязательные отрицательные сценарии. Не выбирать runtime только по документации или unit-тестам.

Правило secrets: publisher GitHub token хранится только вне AgentRunner/agent VM; inference credentials отделены. Приватный исходный код допускается только в доверенную изолированную копию. Любые MCP/SSH/host-mounted sockets и shared directories считаются потенциальным выходом за границу и запрещены по умолчанию.

## 5. Preflight-first: обязательный Gate S0 перед реализацией AgentRunner

До разработки orchestration вокруг выбранного runtime провести ограниченную проверку **реального Codex в той же конфигурации, в которой он будет выполняться**:

1. На WORK: реальный sandbox и изоляция от production, чужих репозиториев и GitHub publication credentials.
2. Доступность `/proc/self/exe`, нужных системных вызовов, временного writable каталога, Git, сети/model inference и аутентификации.
3. Реальный безвредный coding + read-only independent review на временном личном тестовом репозитории, с проверенным stdout/artifacts.
4. Доказанное завершение агента и потомков; timeout/stop/cgroup/VM cleanup на тестовых процессах, без сигналов чужим PID.
5. Повтор проверки после restart и проверка limits; негативная проба доступа к запрещённым путям/секретам.
6. При FAIL — **не потреблять workflow attempt, не запускать новую Issue, не ослаблять случайно sandbox**. Сначала документировать конкретную несовместимость и выбрать поддерживаемый runtime; не строить собственный патч-движок.

Gate S0 должен выполняться до полноценного M2 AgentRunner, а его API/shape должен стать частью ADR-005. На этом этапе — только документируем требования, не устанавливаем новые службы.

## 6. Безопасный пользовательский результат

- Обычные PERSONAL задачи не запрашивают подтверждение на каждую попытку.
- При штатном PASS исполнитель автоматически переходит к независимому Review и публикации Draft PR.
- Если preflight недостаточен: `BLOCKED: SANDBOX_INCOMPATIBLE` с одним конкретным объяснением — без создания claim/attempt.
- Если позже фактически возник UNKNOWN: `NEEDS_OWNER` только у затронутой задачи и ресурсов; состояние REVIEW/PR не уничтожать.
- Контроллер никогда не снимает уже записанные holds по истечению времени или исчезновению PID.

## 7. Требования к приёмке

- Разрешённый PERSONAL repo → настоящий Codex + Review + Draft PR без ручного runtime GO.
- WORK без Owner GO → нет ни исполнения, ни публикации.
- Agent пытается читать publisher token / чужой проект → отказ проверен в реальном runtime.
- Настоящий Codex работает в выбранном sandbox, а не только mocked/test binary.
- Permission/config error диагностируется **до** создания durable attempt/claim.
- После успешного Review отозванное или истёкшее право на новое Coding не блокирует отдельно разрешённый Draft PR, если все актуальные publication guards проходят.
- Crash/неопределённость не создают вторую попытку/PR, история сохраняется, изоляция по ADR-002/R-01.

**Открыто на согласование:** выбрать модель A/B после Gate S0; точный deployment trust boundary для Kotlin Controller/Runner/Publisher и storage отдельных credentials; лимиты KVM/памяти для Android-сборок. Пока всё DRAFT.
