# Архитектура DevOrchestrator v0.1

**Статус:** DRAFT. Концепция для обсуждения, не действующее решение о реализации.

## 1. Главные принципы

- Kotlin/JVM — управляющая логика; Linux — изоляция и управление ограниченными процессами.
- Один основной сервис, один писатель authoritative workflow state, SQLite — источник истины, GitHub — внешний источник задач и PR.
- Проектная политика проверяется на границе каждого действия, а не только при обнаружении Issue.
- Coding и Review не могут публиковать GitHub PR своими credentials.
- **Нет жёстко заданного Codex/модели/effort для роли:** WorkflowEngine вызывает Agent Adapter по выбранному Agent Profile. Выбор профиля сам по себе не расширяет права.
- Не путать *одну кодовую базу оркестратора* с *временными изолированными рабочими копиями целевых репозиториев*.

## 2. Предложенная схема

```text
ChatGPT / GitHub Issue      Android (M5–M6, позднее)
         |                          |
         v                          v
   GitHub Adapter               Ktor API
         |                          |
         +------> Workflow Engine <-+
                       |
        +--------------+---------------+
        |              |               |
   Project Registry  SQLite        Policy Engine
        |         state/events       |
        +--------------+---------------+
                       |
                Agent Runtime
                 /         \
             Codex       Reviewer
                 \         /
                 Evidence + Verified Diff
                       |
                PR Publisher
                       |
                  Draft PR
             (Merge: Owner only)
```

## 3. Модули-контракты (не обязательно Gradle-модули)

| Граница | Ответственность | Не должна делать |
|---|---|---|
| ProjectRegistry | Доверенные repo ID, тип, policy, sandbox roots | Давать права из текста Issue |
| GitHubGateway | Чтение Issues и проверка PR/refs, scoped writes | Менять durable state без двигателя |
| WorkflowEngine | Решения и переходы фаз, лимиты, очередь | Выполнять shell/HTTP side effects напрямую |
| TaskStore | SQLite transactions, attempts, events, outbox | Подменять успешными неизвестные эффекты |
| AgentCatalog | Каталог проверенных адаптеров, capabilities, поддержанных моделей/effort и доступности | Принимать произвольные опасные настройки из текста Issue |
| AgentSelector | MANUAL / AUTO_RULES / AUTO_AI выбор среди разрешённых Project Registry профилей, с audit | Повышать rights или менять WORK approval policy |
| AgentRunner | Запуск/stop/observation bounded процессов через выбранный адаптер и sandbox | Иметь токены публикации или незаметно менять текущий attempt |
| ReviewDriver | Независимая проверка frozen tree/diff | Доверять саморевью агента |
| Publisher | Branch, commit, push, Draft PR, durable receipts | Merge или повторять неопределённый эффект вслепую |
| ApiServer | Read/control endpoints для владельца | Прямой root shell |

## 3A. Подключаемые агенты и маршрутизация задач (DRAFT)

```text
       GitHub Issue + Project Registry
                       |
                 Policy Filter
                       |
            AgentSelector / Planner
         MANUAL | AUTO_RULES | AUTO_AI
                       |
              Agent Profile Snapshot
       adapter + role + model + effort
       runtime + capabilities + limits
                       |
               Sandbox Adapter
                       |
          Agent Adapter (Codex / ...)
             /             \
         Coding         Independent Review
             \             /
                Verified Evidence
                       |
              Trusted Publisher
              (Draft PR only)
```

- **Один контракт AgentAdapter** для реальных Codex/Cursor/Claude/Gemini/Kimi/OpenCode (только после отдельной интеграции); адаптер запускает, собирает evidence, останавливает/наблюдает процесс и объявляет совместимые модели/effort.
- **Роль — назначение, не тип бинарного файла:** Coding/Review/Planner/Chief (Chief опционален). Для каждого проекта можно менять профили без изменения core.
- **Роутинг:** ручной выбор или автоматический на основе сложности, инструментов, доступности, лимитов, стоимости/качества. AI Selector **выбирает, но не выдаёт права**. Если подходящего агента нет — BLOCKED без запуска.
- **Динамика безопасна между attempts:** конфигурация версионируется и фиксируется при старте attempt; изменённый Owner профайл применяется к следующей попытке. Во время RUNNING модификация процесса/модели запрещена.
- **Верификация:** reviewer — новая независимая сессия и frozen tree; если модель/провайдер совпадают, независимость контекста всё равно обязательна, проектная policy может требовать другого провайдера.
- **Runtime для MVP:** ADR-003/S-04 **APPROVED**: Kotlin вызывает существующий `codex exec` в native `workspace-write` режиме через ProcessBuilder в отдельном временном PERSONAL git clone/worktree. Никаких Docker/microVM, нового Linux UID или дополнительных systemd sandbox layers. **Это не полноценная граница чтения хостовых файлов/секретов** при том же UID; проекты WORK запрещены для native профиля. Docker S-01/S-02 отложен как усиленный вариант.
- **Никакого авто-Merge:** публикация проходит через отдельный процесс с собственными авторизованными credentials и git/github guards (ADR-004).

**Этапы:** M1 — минимальная схема Agent Catalog и профилей; M2 — Native Codex `exec` и быстрая проверка реального CLI; M3 — Independent Review/Draft PR; M4 — второй агент и проверенный AUTO selection. Приоритет — первый настоящий E2E, а не инфраструктурная настройка. Точный интерфейс — ADR-005.

## 4. Состояния

**Утверждено ADR-002/R-06:** фаза workflow, состояние попытки и состояние внешнего эффекта — раздельные машины; `NEEDS_OWNER` — отдельный hold. Например, `REVIEWING` — фаза, а `UNKNOWN` — состояние конкретного attempt. [ADR-002](adr/ADR-002-lifecycle-DRAFT.md) и [таблица R-06](adr/ADR-002-R06-state-machine-DRAFT.md) имеют статус APPROVED; вся ARCHITECTURE.md пока DRAFT.

## 5. Данные

Минимальные сущности: `projects`, `policies`, `issues`, `workflows`, `attempts`, `events`, `effects_outbox`, `approval_records`, `artifacts`.

Инварианты:

- Уникальная связка `(github_repository_id, github_issue_number)` для логической задачи.
- Не более одного активного writer для одной задачи и workspace.
- Каждый side effect имеет idempotency/reconciliation key и durable intent до отправки.
- Terminated agent ≠ proven absence of descendants; UNKNOWN не снимается по timeout.
- Secrets хранятся вне БД/лога задач; агенты имеют минимальный набор прав.

## 6. Публикация

Draft PR — **целевой результат автоматизации**. Его создание допускается после PASS необходимого Review и проверки exact tree/branch/base. Ответ GitHub перед объявлением SUCCESS надо проверить повторным read; невозможность подтвердить outcome должна вести в `NEEDS_OWNER` без повторного создания PR.

По утверждённому ADR-002/R-04 в PERSONAL Draft PR создаётся после обязательного независимого Review PASS без предварительного Chief по умолчанию. Merge — только Owner. WORK использует отдельные строгие Owner gates. Полный архитектурный документ пока DRAFT.

## 7. Android (будущие этапы)

Kotlin/Compose клиент Ktor API: список проектов/задач, timeline, короткие статусы, разрешения, пауза. Через защищённое соединение (например, Tailscale). Без прямого SSH и без токенов GitHub в приложении. Авторизация и аудит команд обязательны до M6.

## 8. Что намеренно не проектируем сейчас

Много машин, переключение модели **внутри выполняющегося agent attempt**, Kubernetes, глобальный транзакционный протокол между Git и GitHub, генерацию собственных sandbox-движков. **Между attempts** смена агента/модели/effort и автоматическая маршрутизация входят в план серверного MVP. Для MVP нужны минимальные существующие системные средства и измеримые инварианты.
