# ADR-002 / R-06 — Формальная машина состояний и восстановление

**Статус:** APPROVED — утверждено владельцем 2026-10-17; реализация отдельно не разрешена.  
**Дата:** 2026-10-17. **Этап:** M0. **Задача:** [#2](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/2).  
**Основа:** ADR-001 (APPROVED), ADR-002/R-01…R-05 (APPROVED). Этот документ является детализацией, а не новым разрешением на запуск агентов или реализацию.

## 0. Цель и граница решения

DevOrchestrator на Kotlin/JVM должен безопасно доводить разрешённую GitHub Issue до **настоящего Draft PR** даже при перезапусках и потере ответов, **не создавая второй запуск агента, Push или PR вслепую**.

Принцип простоты: один управляющий сервис, SQLite как источник истины, три **раздельные** машины состояний и отдельный blocker. Никакого «одного status на всё», глобального Safety Latch для одной проблемной Issue или собственного распределённого протокола.

Что уже утверждено:
- **R-01:** блокировать задачу и затронутые ресурсы, не независимые проекты;
- **R-02:** продолжение только по доказанному состоянию, иначе `NEEDS_OWNER`;
- **R-03:** максимум два цикла автоматического Coding+Review по замечаниям;
- **R-04:** PERSONAL → Draft PR после обязательного независимого Review PASS без обязательного предварительного Chief; WORK/1МТ — отдельная политика Owner GO;
- **R-05:** логи 30 дней, snapshots 7 дней, история 1 год, неопределённые данные до безопасного разрешения.

## 1. Модель состояний — четыре независимых измерения

| Измерение | Утверждённые значения | Что описывает |
| --- | --- | --- |
| `WorkflowPhase` | `QUEUED`, `CODING`, `VERIFYING`, `REVIEWING`, `READY_FOR_PR`, `PUBLISHING`, `DRAFT_PR_CREATED`, `FAILED`, `CANCELLED` | Основной путь одной Issue |
| `AttemptState` | `PLANNED`, `STARTING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`, `CANCELLED` | **Одна** попытка Coding/Review, со своим ID и generation |
| `EffectState` | `PREPARED`, `SENDING`, `CONFIRMED`, `REJECTED`, `UNCERTAIN` | **Один** внешний эффект: Commit, Push или Create Draft PR |
| `WorkflowHold` | `NONE`, `PAUSED`, `NEEDS_OWNER` | Временная блокировка без потери достигнутой фазы |

**Почему отдельный `WorkflowHold`:** если публикация остановлена на `PUBLISHING`, переход в одно глобальное `UNKNOWN` стёр бы смысл предыдущих PASS. Поэтому UI показывает, например, `PUBLISHING · NEEDS_OWNER: unconfirmed push`, а проверенные Coding/Review остаются терминальными.

`DRAFT_PR_CREATED` — **терминальный результат MVP**. Owner потом решает Merge **в GitHub**, а не через автоматический этап MVP. `FAILED` означает доказанно окончательную ошибку без неразрешённых побочных эффектов; для неоднозначности использовать `NEEDS_OWNER`, а не `FAILED`. `CANCELLED` допускается только после доказанного прекращения всех относящихся к workflow процессов и разрешения/изоляции эффектов. Нельзя превращать `UNKNOWN` в `CANCELLED` одним нажатием.

## 2. Допустимые переходы WorkflowPhase

| Из → в | Условие перехода (все обязательны) | Кто решает |
| --- | --- | --- |
| — → `QUEUED` | Точная repo ID + Issue number, уникальность в БД, Project Registry известен, документированная проверка события | GitHub Intake → Engine |
| `QUEUED` → `CODING` | Project policy позволяет, закреплены Issue body/version и source HEAD/tree, workspace зарезервирован; нет непроверенного владельца процессов; создан и сохранён intent для попытки | Workflow Engine |
| `CODING` → `VERIFYING` | Актуальная Coding attempt `SUCCEEDED` с верифицированным terminal/cleanup proof, сохранён diff/tree; нет неизвестного потомка | Workflow Engine |
| `VERIFYING` → `REVIEWING` | Проверки удовлетворяют **явной** project policy; `PASS`, `FAIL` и `NOT_RUN` различимы; REVIEW получает frozen reviewed tree | Workflow Engine |
| `REVIEWING` → `CODING` | Review = `CHANGES_REQUIRED`; `corrections_used < min(project_limit, 2)`; увеличен счётчик и создан новый generation, предыдущая попытка сохранена | Workflow Engine |
| `REVIEWING` → `READY_FOR_PR` | Текущий независимый Review = `PASS`, относится к последнему Coding generation и точному tree; нет нерешённых hold/активных чужих процессов | Workflow Engine |
| `READY_FOR_PR` → `PUBLISHING` | Отдельная действующая publication policy; repo/branch/base/tree/remote закреплены; для WORK отдельный Owner GO; durable effect intents фиксируются **до** внешних writes | Publisher через Engine |
| `PUBLISHING` → `DRAFT_PR_CREATED` | Commit и Push подтверждены, GitHub PR **прочитан обратно** и точно соответствует repo, head SHA, branch, base и `draft=true` | Workflow Engine |
| Активная фаза → `FAILED` | Терминальная невосстановимая ошибка доказана; нет активного неучтённого процесса или `UNCERTAIN` эффектов | Workflow Engine |
| Активная фаза → `CANCELLED` | Owner запросил отмену; все затронутые процессы и внешние эффекты классифицированы, нет неизвестного ownership | Workflow Engine |

В каждой активной фазе может возникнуть hold `PAUSED` или `NEEDS_OWNER`; **фаза при этом сохраняется**, новые side effects запрещены. Только точное, проверенное Owner-решение и повторные guards могут снять `NEEDS_OWNER`.

`READY_FOR_PR` означает «код принят и проверен», но **не** «публикация уже разрешена». Истечение execution permission не уничтожает результат; публикация проверяет собственное актуальное разрешение. Chief до PR требуется **только если это установлено конкретной политикой WORK/другого проекта**, а не как глобальное правило.

### Ветки отказа / запретные переходы

- `QUEUED` → `PUBLISHING`, `CODING` → `DRAFT_PR_CREATED` без независимого Review, `REVIEWING` → `READY_FOR_PR` по самоотчёту Coding — **запрещены**.
- `REVIEWING` → `CODING` в третий автоматический цикл — **запрещено**. После двух неуспешных исправлений: `REVIEWING + NEEDS_OWNER`.
- `DRAFT_PR_CREATED` → новый Coding для той же Issue по обычному poll — **запрещено**; обновление уже созданного PR и новые задачи будут отдельным будущим решением.
- Исторический Review не подменяет текущий verdict, не меняет tree и не может сам сдвинуть фазу. Но его **реально живой / неопределённый процесс** продолжает блокировать совместно используемые ресурсы.
- `NOT_RUN` нельзя превращать в `PASS`. Если проектная политика требует данный тест, `VERIFYING + NEEDS_OWNER` до его настоящей проверки/решения согласно политике.
- `PAUSED`, `NEEDS_OWNER`, `FAILED`, `CANCELLED` нельзя обходить новым событием GitHub или повторным чтением Issue.

## 3. Состояния отдельных попыток Coding / Review

| Из → в | Условие |
| --- | --- |
| — → `PLANNED` | Выделен уникальный `attempt_id`/`generation`, scope и конфигурация привязаны к Project Registry, записаны в SQLite |
| `PLANNED` → `STARTING` | Durable launch intent записан в SQLite **до** запуска процесса, ресурсы зарезервированы |
| `STARTING` → `RUNNING` | Доказан конкретный запущенный процесс: machine/boot/process start/generation + owner; не просто PID-число |
| `STARTING`/`RUNNING` → `SUCCEEDED` | Подтверждённый exit, cleanup и результат, привязанный к неизменённому исходному дереву и попытке |
| `STARTING`/`RUNNING` → `FAILED` | Подтверждённый terminal exit с ошибкой и доказанный cleanup; не только timeout |
| `STARTING`/`RUNNING` → `UNKNOWN` | Запуск/ownership/exit/cleanup невозможно доказать; нельзя повторить с новым generation автоматически |
| `RUNNING` → `CANCELLED` | Есть запрос Owner, **доказано** отсутствие остающихся процессов и побочных эффектов |

`UNKNOWN` не «лечится» автоматическим новым запуском. Read-only reconciliation может перевести в `SUCCEEDED` или `FAILED` **только при доказанном результате**, иначе сохраняется `UNKNOWN` + `NEEDS_OWNER`. Отмена не является обходом.

Все предыдущие attempts сохраняются. Только последняя **актуальная и связанная** attempt влияет на новые переходы workflow, но старые попытки учитываются при доказательстве освобождения общего workspace и отсутствия активных процессов.

## 4. Состояния внешнего эффекта Commit / Push / Draft PR

| Из → в | Условие |
| --- | --- |
| — → `PREPARED` | В SQLite транзакционно записаны `effect_id`, тип, reviewed tree, target repo/branch/base/expected SHA, authority и plan |
| `PREPARED` → `SENDING` | Зафиксировано начало **ровно одного** разрешённого обращения/операции до выполнения внешнего действия |
| `SENDING` → `CONFIRMED` | Внешний результат проверен по Git/GitHub (не только по exit=0/HTTP=2xx), точные идентификаторы совпадают |
| `SENDING` → `REJECTED` | Доказанный окончательный отказ **без** принятой мутации; сомнительный отказ не считается `REJECTED` |
| `SENDING` → `UNCERTAIN` | Ответ потерян, timeout/crash или Git/GitHub не позволяет доказать результат |
| `UNCERTAIN` → `CONFIRMED` | Read-only reconciliation обнаружил точно тот же commit/ref/PR и verified tree |
| `UNCERTAIN` → `REJECTED` | Достоверно доказано отсутствие эффекта и невозможность его позднего принятия; простой «не найден в списке» недостаточен |

Из `CONFIRMED` нельзя отправить тот же effect повторно; `REJECTED` не даёт права автоматического нового эффекта без отдельного решения и нового разрешённого ID. Если `UNCERTAIN` не снимается доказательствами — `PUBLISHING + NEEDS_OWNER`.

**Проверки публикации:** exact repo ID, expected branch/base, reviewed source tree, отсутствие force push, CAS/expected remote ref, `draft=true` и единственный однозначно идентифицированный PR. Merge-операции в Publisher MVP вообще отсутствуют. GitHub read может повторяться в установленных пределах; повтор write «на удачу» запрещён.

## 5. Сценарии сбоев и ожидаемая реакция

| Сценарий | Что проверяем после restart | Решение |
| --- | --- | --- |
| F01. GitHub Issue прочитана дважды | `UNIQUE(repo_id, issue_number)` в SQLite | Одна workflow, повторный poll — no-op |
| F02. Crash после `PLANNED`, до запуска | Durable launch intent + доказательства того, что запуск не начинался | Продолжить тот же безопасно проверенный шаг; если факт старта неизвестен — `NEEDS_OWNER` |
| F03. Crash после spawn, до подтверждения | Boot/start/generation/ownership, процесс и durable launch receipt | При уверенности — наблюдать существующую attempt; иначе `UNKNOWN + NEEDS_OWNER`, **не запускать вторую** |
| F04. Codex завершился, ответ пропал | Подтверждённые exit/cleanup, input identity, результат и diff SHA | `SUCCEEDED` и далее `VERIFYING` без повторного Coding |
| F05. Review завершился, но verdict не записан | Reviewer session, frozen snapshot SHA, независимый отчёт и cleanup | Принять verified review evidence, не запускать Review снова |
| F06. Review = CHANGES_REQUIRED | Точный reviewed tree, correction counter и project limit | До двух новых correction Coding+Review, далее `NEEDS_OWNER` |
| F07. Execution grant истёк после Review PASS | Точные terminal attempts + publication policy | Сохранить `READY_FOR_PR`; publication получает отдельную authority, Coding не повторяется |
| F08. Crash до/во время локального Commit | Local branch/tree/OID, journal/effect ID | Подтвердить созданный commit либо удержать; не создавать произвольный другой commit |
| F09. Push принят, ответ потерялся | `ls-remote` exact branch/ref, expected SHA, отсутствие force | Подтвердить исходный Push без повторного; при drift/неопределённости hold |
| F10. Draft PR создан, HTTP response потерян | GitHub PR по exact head/base/repo/head SHA и `draft=true`, уникальность | Признать тот же PR, не создавать второй; если не доказано — hold |
| F11. Draft PR подтверждён, но SQLite ещё на `PUBLISHING` | GitHub PR и effect journal | Перейти в `DRAFT_PR_CREATED` без нового GitHub write |
| F12. Старый Review имеет stale READ_ONLY | Историческая generation и действительный cleanup, текущий Review PASS | Не менять текущую phase при terminal historical Review; при неизвестном ownership блокировать **затронутый ресурс** |
| F13. Отказ GitHub / сеть недоступна | Тип операции: read или write, журнал отправки | Read-only retry с bounded backoff; writes не повторять без доказательства результата |
| F14. Workflow A завис в UNKNOWN, Workflow B независима | Реальные resource keys: repo/branch/workspace/process ownership | A + связанные ресурсы blocked, B продолжает; если общая изоляция не доказана, расширить блокировку |
| F15. Запрос отмены во время процесса | Поколение и принадлежность process tree, конец/cleanup | До доказанной остановки `NEEDS_OWNER`/hold; чужие процессы не сигналить |
| F16. Повреждена SQLite или общий security/trust state | Целостность БД, журналов и authorizations | Остановить все **затронутые** записи/запуски; если глобальная достоверность потеряна — остановить writes системы; recovery по отдельной процедуре |
| F17. Истёк срок хранения, но есть UNKNOWN | Наличие holds, leases, связанных effects и evidence | Очистку запретить, сохранить доказательства (R-05) |
| F18. WORK Issue без Owner GO | Project policy/version и доверенные approval records | Оставить `QUEUED + NEEDS_OWNER` без запуска; PERSONAL-политика не наследуется |
| F19. NOT_RUN у обязательных тестов | Verification policy, фактические результаты | Не выдавать PASS и не публиковать; состояние `VERIFYING + NEEDS_OWNER` |
| F20. Поступил новый GitHub event при действующем workflow | Issue identity, pinned Issue body/metadata SHA и workflow version | Не перезапускать; изменение входных данных требует отдельного однозначного решения |

## 6. Инварианты хранения и конкурентности (эскиз для Kotlin/JVM)

Один `WorkflowEngine` управляет переходами; SQLite хранит `workflows`, `attempts`, `effects_outbox`, `events`, `resource_locks`, `approval_records` и `artifacts` (точный DDL позже).

1. `UNIQUE(repository_id, issue_number)` — одна логическая задача на GitHub Issue.
2. `workflow.version` — optimistic compare-and-set на переходы; контроль старой версии не меняет новую фазу.
3. `attempt_id + generation + role` — уникальны; последнее валидное поколение используется только по явной связи с текущим workflow.
4. На один ресурс может быть только один подтверждённый writer; resource lock имеет owner/lease/identity, и **истечение lease само по себе не доказывает освобождения процессов**.
5. Каждое внешнее изменение идёт через durable outbox: prepare → send → confirm/reconcile. Команда Owner/агента не пишет SQLite authoritative state напрямую.
6. Evidence связывается с точной версией source/reviewed tree и сохраняется отдельно от удобного краткого отчёта.
7. Нет глобальной зависимости между unrelated workflow; блокировки распространяются **по фактическим ресурсам**, а не по наличию старого `READ_ONLY` где-то в общем ответе.
8. Логи и snapshots очищаются только по R-05 и при отсутствии recovery-зависимостей.
9. Engine никогда не трактует агентное `PASS` как достаточный сигнал безопасности или публикации: проверяются terminal/process/verification/review+policy.
10. В существующей фазе `READY_FOR_PR` нет автоматического обязательства ждать Chief для PERSONAL; для WORK действует отдельная строгая project policy и Owner GO.

**Не обещаем математическое exactly-once выполнение внешних GitHub writes.** Гарантируем, что неоднозначность остаётся `UNCERTAIN`, а восстановление не рассылает побочные действия вслепую; успешный итог утверждается только после доказанной сверки.

## 7. Критерии приёмки R-06 (требования к будущим тестам; тесты не запускались)

- **State table:** каждый разрешённый переход проходит unit test; любой переход без guard отклоняется без state mutation.
- **Generation/Review:** устаревший Review не авторизует PR, не перезаписывает актуальное состояние; живой исторический worker удерживает ресурс.
- **No duplicates:** повтор poll, restart и потеря ответа не создают второй Coding/Review, force Push или второй Draft PR.
- **Recovery:** F01–F20 проверяются offline fault injection, а сценарии интеграции с реальным GitHub/агентами — отдельными контролируемыми E2E.
- **Bounded corrections:** максимум initial + 2 correction cycles; счётчик переживает restart.
- **Scoped failure:** Issue A блокируется без остановки безопасной Issue B в другом repo/workspace.
- **Project policy:** WORK без Owner GO остаётся без исполнений; PERSONAL проходит Review→Draft PR без Chief до PR.
- **Retention:** UNKNOWN не удаляется по календарю; удаление завершённых artifacts безопасно и аудируемо.

## 8. Граница утверждения R-06 и отдельные решения

- **ADR-003:** кто и чем доказывает process ownership/cleanup, credential boundary, systemd/sandbox details.
- **ADR-004:** GitHub read-after-write identity, CAS ref, idempotency markers и действительные полномочия Publisher.
- **ADR-005:** конкретные контракты Coding/Review, тестов, artifact manifest и session independence.
- **Решение ADR-002:** таблицы переходов и F01–F20 утверждены. `DRAFT_PR_CREATED` является терминальным результатом серверного MVP без дополнительного `DONE`/post-merge workflow; Merge остаётся исключительно действием владельца в GitHub.

**Окончательный статус R-06 — APPROVED владельцем 2026-10-17. Это архитектурное решение, не разрешение на реализацию, запуск Kotlin-кода, агентов, публикацию или эксплуатацию.**
