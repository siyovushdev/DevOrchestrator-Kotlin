# ADR-003 / S-02 — Доступ агентов, сеть, секреты и передача результата

**Статус:** APPROVED владельцем 2026-10-17 — правила доступа и передачи результата; техническая работоспособность подтверждается отдельно Gate S0.  
**Этап:** M0 · [GitHub Issue #3](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/3) · Дата черновика: 2026-10-17.  
**Уже утверждено:** ADR-003/S-01 — Docker Sandboxes microVM как основной кандидат при обязательном Gate S0; ADR-001 и ADR-002. **S-02 утверждено как архитектурный контракт. Реализующий код и тесты на WORK не запускались.**

## 1. Цель

Агент имеет широкие права **внутри своей microVM**, может менять код и ставить зависимости, но не получает прямого доступа к рабочему хосту, чужим проектам, SSH-агенту, host MCP и GitHub Publisher credentials. Kotlin-оркестратор получает проверяемые артефакты и сам решает публикацию. Без нового многослойного механизма `grant/hold/permissions` на каждую команду.

## 2. Основной поток данных (предложение)

```text
GitHub Issue + Trusted Project Registry
                |
      Kotlin Controller (политики)
                |
      Clean Staging Snapshot (host)
    pinned SHA, only approved files,
    no credentials, no Git remotes
                |
      sbx microVM (Coding, clone mode)
       sudo/build/test inside VM
                |
      Export to host quarantine
       artifacts + candidate files
                |
   Controller verifies candidate tree
                |
   Separate sbx microVM (Review)
     frozen candidate, report only
                |
   Kotlin Publisher, own credentials
          Commit -> Push -> Draft PR
```

Термин `Clean Staging Snapshot` означает **отдельную временную копию проверенного Git commit/tree**, подготовленную контроллером для одной задачи. Это НЕ `~/StudioProjects/1MT...` и НЕ действующее рабочее дерево владельца. Источник и SHA закрепляются в SQLite. Любые untracked/ignored файлы с реального ПК по умолчанию не переносятся.

## 3. Матрица доступа

| Объект / операция | Coding VM | Review VM | Controller/Publisher |
| --- | --- | --- | --- |
| Разрешённая очищенная копия исходников | RW **внутри VM**, источник только RO | Изолированная frozen копия, выход — review report | Готовит и проверяет snapshot |
| Shell, sudo, apt/npm/pip, Gradle, Android SDK, Git, тесты | Да, **внутри VM** | Проверка в VM; изменения Reviewer не публикуются | Не исполняет агентский код как host shell |
| GitHub Issue / Project policy | Только минимальный текст задачи и ограниченный контекст | Только review context | Авторитетные repo ID, gates и policy |
| GitHub remote, Push, PR credentials | **Нет** | **Нет** | Только Publisher после gates |
| Host checkout/домашние каталоги/другие проекты | **Нет** | **Нет** | Только разрешённые roots для staging |
| Host Docker socket, SSH/Tailscale agent, host MCP | **Нет** | **Нет** | Администрирование отдельно, не через агента |
| Доступ к model provider / зависимостям | Нужные разрешённые адреса | Нужные разрешённые адреса | Сетевую политику задаёт доверенный владелец |
| Импорт изменённых файлов | Только экспорт в quarantine, без прямого host RW | Только отчёт с pinned tree hash | Проверка/публикация точного дерева |

Это не утверждает, что любое произвольное поведение агента внутри VM безопасно. Сам агент может исполнять вредоносный код и передавать увиденный код на **разрешённые** сетевые адреса; PROJECT и model-provider политики остаются обязательными.

## 4. Исходники: критичная поправка к `sbx --clone`

Документация Docker уточняет: `--clone` создаёт независимый Git clone **внутри VM**, но host repository остаётся смонтированным **read-only** по `/run/sandbox/source`, включая **untracked и ignored файлы (.env и подобные)**. Clone mode защищает хост от *изменений*, но **не от чтения** исходного checkout.

**Предложение S-02:** в качестве аргумента `sbx --clone` использовать **только Clean Staging Snapshot**, не реальный checkout.

Правила staging:
1. Подготовить из зарегистрированного repo и pinned commit/tree отдельный чистый каталог; включать только необходимые tracked source и manifest. Не копировать `.env`, ключи, локальные конфиги, `.ssh`, credentials и неразрешённые каталоги.
2. Для работы `--clone` подготовить самостоятельный временный Git repo со **своим минимальным history без секретов и без `origin/upstream` remotes**. Не копировать исходную `.git`/старые секреты в истории. Original repository ID и full source commit SHA оставлять только в trusted controller metadata.
3. Даже в staging файлы содержат потенциально чувствительный исходный код — он может уйти через allowed model/network. Для WORK обязателен Owner GO, выбор разрешённого провайдера/проекта и запрет на непринятую передачу закрытого кода.
4. Не подключать дополнительные workspaces или прямые writable host mounts. Дополнительные исходные документы — только явно переданные по project policy (не «все соседние репозитории»).
5. Вариант mountless + `sbx cp` допустим как **альтернативный транспорт** после S0, если это проще для конкретного runtime; не смешивать оба способа без необходимости. На старте один рабочий механизм.

**Известное ограничение:** некоторые Git-dependent проекты требуют tags/submodules/историю, а Android-сборки могут использовать private Maven. Фиксируем эту потребность в Project Profile и задаём изолированный источник, а не монтируем рабочий home.

## 5. Сеть, credentials, skills и MCP

- **Network:** Docker Sandboxes имеет сетевую политику с исходящим deny-by-default, но **стандартные allow-правила могут содержать широкие wildcard-домены**. Перед реальным использованием проверяем активные правила и разрешаем необходимые model/provider/Gradle/Maven endpoints без `allow all`; соединения и отклонения логируются. Доменный allowlist **не гарантирует защиту от эксфильтрации** на разрешённые адреса.
- **Model credentials:** по возможности `sbx secret`/host-side credential proxy для поддерживаемого сервиса, а не env/file с сырой API-key; проверить, как конкретный агент авторизуется (CLI OAuth может отличаться от API key). Токены модели — только для model service, с бюджетом/лимитами; никакого Publisher/GitHub production token.
- **SSH:** Docker Sandboxes **по умолчанию может пересылать host SSH agent**. Для выделенной инсталляции `sbx` требуем отключить `ssh.agentForwardingEnabled` и проверить результат. Эта настройка может быть общесистемной для локального daemon — изменение только после инвентаризации существующих sandbox сессий, не молча.
- **Skills:** отключать host shared skills mount при создании (`--skills=off`, если поддерживается установленной версией), не полагаться на default read-only/readwrite.
- **MCP:** local stdio MCP через Docker gateway исполняется **на хосте**, вне VM. Не подключать host MCP toolsets к агенту для MVP; проверять реальную доступность инструментов и отсутствие наследования опасных Codex/Cursor конфигов. Подключаемые remote MCP без host powers — отдельное решение позже.
- **Host port mappings:** не публиковать порты VM наружу по умолчанию. Для тестов web UI использовать ограниченный/временный доступ и отдельное явное разрешение.
- **Agent profiles:** смена модели или Coding/Review агента не добавляет mount, SSH, host MCP или GitHub grants. Роли/модели выбирает Agent Catalog (ADR-005) в пределах одобренной Project Policy.

## 6. Выход результата: только через контролируемый импорт

**Результат агента не является доверенным host-файлом**, даже если тесты внутри VM прошли.

1. Coding создаёт кандидат изменений в VM. Controller экспортирует нужные исходные файлы/патч и тестовые evidence в **task-scoped quarantine** на хосте через ограниченную операцию (`sbx cp` или verified Git fetch из sandbox). Никакого автоматического копирования в реальный checkout.
2. На хосте Controller сам проверяет: базовый SHA, допустимые относительные пути, отсутствие `../`/абсолютных путей и symlink escapes, size/count limits; включение **новых/untracked файлов**; сканирование/пометки необычных бинарных файлов и изменений build scripts/hooks. Не исполнять код/скрипты из quarantine на хосте.
3. Controller пересобирает **точный candidate tree** из pinned base + проверенных файлов, вычисляет immutable content hash и использует этот hash как идентичность для Review и публикации. Самоотчёт агента не считается доказательством hash.
4. Independent Review запускается в **другой sandbox и новой сессии**, получает frozen candidate и проверяет его. Поскольку Reviewer внутри VM может иметь RW в *своём* каталоге, Controller **игнорирует все reviewer code changes**: импортирует только structured review report с точным candidate hash.
5. PASS Review относится только к frozen tree. Изменение хотя бы одного файла после PASS требует нового независимого Review. Никакого Commit/Push/PR без совпадения candidate/reviewed/published tree.
6. Trusted Publisher в отдельной host trust boundary использует scoped GitHub credentials и переносит проверенное дерево на точную target branch/base с CAS-guards, создаёт **Draft PR** и read-after-write подтверждение (подробности — ADR-004).
7. После подтверждения durable evidence VM/snapshot удаляются **по ADR-002/R-05**, но не при `UNKNOWN`, `UNCERTAIN` или ещё нужном recovery. Никаких host PID signals из Issue.

## 7. Отрицательные тесты Gate S0 (до разработки AgentRunner)

| Проверка | PASS означает |
| --- | --- |
| S0-01 host KVM/Ubuntu/CLI | Реальная microVM запускается в нужной конфигурации на WORK |
| S0-02 staging isolation | Агент видит только чистый staging, не реальный dirty checkout или `.env` |
| S0-03 VM freedom | Codex реально запускает команды, устанавливает тестовую зависимость, редактирует код и выполняет тесты |
| S0-04 secrets/SSH/MCP | Не видит GitHub Publisher secret, host SSH signing и host MCP actions |
| S0-05 network | Нужные inference/Gradle/Maven доступны, запрещённый test endpoint недоступен |
| S0-06 artifact transfer | Экспортирован новый + изменённый файл, exact base/candidate hash совпал, path escape rejected |
| S0-07 independent Review | Второй sandbox проверяет frozen tree; reviewer изменения не попадают в publish |
| S0-08 stop/restart/cleanup | Подтверждённое завершение и recovery без повторного запуска и удаления неясных evidence |
| S0-09 WORK boundary | Без Owner GO нет подготовки/запуска WORK проекта, чужой repo не виден |
| S0-10 publication isolation | Без токенов VM не может самостоятельно Push/PR; Publisher проверяет те же Git refs |

**Gate S0** — это реальная проверка возможностей выбранного Docker sandbox, а не симуляция unit-тестами. Полный E2E GitHub Draft PR относится к M3. Детали CLI зависят от установленной версии и проверяются перед тестом.

## 8. Границы S-02 и следующие решения

- **S-02 APPROVED:** чистый staging, строгие host-boundary, сеть/model credentials, контролируемый экспорт и отдельный review context. Реальные CLI-механизмы и совместимость проверяются Gate S0, а не предполагаются.
- **S-03 (следом):** procedure/ownership Gate S0, точная схема запуска, VM budgets/cache, cleanup, S0 FAIL; правила высокорисковых WORK действий и отдельные approvals.
- **ADR-004:** Publisher GitHub authentication/ref/CAS/PR identity/recovery.
- **ADR-005:** Agent Catalog, роли, model/effort, adapters, automatic agent selection и evidence schema.
- **M0 release gate:** до завершения ADR-003/004/005 и отдельного согласования ТЗ/архитектуры/roadmap реализация и установка runtime не разрешены.

## Источники Docker (требуют практической перепроверки на установленной версии)

- https://docs.docker.com/ai/sandboxes/security/ — VM, SSH, MCP, credentials и прочие trust boundaries.
- https://docs.docker.com/ai/sandboxes/security/isolation/ — clone mode, включая host source read-only.
- https://docs.docker.com/ai/sandboxes/usage/ — `sbx cp`, mountless и clone mode.
- https://docs.docker.com/ai/sandboxes/workflows/git/ — Git remotes и workspace modes.
- https://docs.docker.com/ai/sandboxes/configuration/settings/ — SSH agent forwarding default, disabled setting.
- https://docs.docker.com/ai/sandboxes/security/defaults/ — network rules, shared skills и default capabilities.

**Итог:** один готовый sandbox runtime с широкими правами *внутри*, но строго ограниченным входом и выходом. **S-02 APPROVED. ADR-003 в целом DRAFT до утверждения S-03 и оставшихся вопросов. Gate S0 не проводился, доступ к WORK и запуск агентов не разрешены этим документом.**
