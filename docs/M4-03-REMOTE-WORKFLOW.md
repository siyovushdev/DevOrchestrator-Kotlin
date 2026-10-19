# Issue #47 — SSH Remote Worker, этап 2

Исходная версия: PR #48, `cd5c2a0bfa7b6b98b014fe26a643d5faf2a26443`.
ТЗ и Chief checkpoint: [Issue #47](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/47#issuecomment-6263462303).

## Как работает

Существующий PERSONAL service принимает только разрешённую владельцем задачу фиксированного
репозитория. Kotlin контроллер сохраняет Issue/base SHA, policy, профили, поколения и место
исполнения. `WorkflowEngine` использует локальный runner либо SSH-адаптер; сменить место
исполнения сохранённой задачи нельзя, в том числе через прямые методы Tests/Review/recover.

В режиме `ssh-vm` Coding получает pinned Git bundle и ограниченный снимок исходников. После
ответа контроллер заново проверяет identity запроса, raw guest process receipts, завершение
дочерних процессов, installation digest, артефакты и Git tree. Он сохраняет исходные байты
запроса/ответа и файлы в новом private staging, выполняет fsync и атомарный rename. Затем
одной SQLite-транзакцией фиксирует импорт, завершает фазу и освобождает её reservation.
Частичный staging не является результатом. Уже переименованный импорт проверяется заново,
никогда не перезаписывается; исходный checkout с базовым коммитом остаётся неизменным.

Tests запускают внутри guest штатный controller-owned unittest harness. Python-дети входят
в отдельную bubblewrap-песочницу, которой доступен только исходный снимок и минимальная ОС;
Codex credentials и guest journal туда не монтируются. Внешняя bubblewrap-обёртка вокруг Codex
не добавляется: Coding/Review используют native permission policy worker из этапа 1.
Tests связывают исходный NOT_RUN manifest с тем же деревом и новым PASS/FAIL manifest.
Review получает только PASS candidate, работает read-only, новым `codex exec` без resume.
Его session ID сверяется со всей сохранённой историей Coding/Review этой задачи.

Сохраняется лимит двух исправлений. Следующий Coding получает реальные файлы предыдущего
проверенного кандидата и сохранённые замечания; bundle по-прежнему содержит исходный base.
Только прежний контроллерный Publisher выполняет COMMIT/PUSH/POST Draft PR. На каждой своей
границе он заново проверяет remote journal, исходные квитанции, файлы, тесты, Review и frozen
candidate. Локальные PID/start events для удалённого исполнения не выдумываются. Merge нет.

## Неопределённость и Owner-команды

Intent WorkflowEngine и одноразовый transport claim сохраняются одной SQLite-транзакцией
**до** SSH. Повторный submit после потери ответа/перезапуска невозможен. Ни RUNNING, ни
NOT_FOUND, ни плохая квитанция не дают разрешения на повтор Coding/Tests/Review.
Резервирование сохраняется; очередь переходит в NEEDS_OWNER.

Команды выполняются вручную, под тем же service account и с service lock:

```
APP check-remote CONFIG ISSUE_NUMBER ATTEMPT_ID
APP resume-remote CONFIG ISSUE_NUMBER ATTEMPT_ID EXACT_EVIDENCE_SHA256
```

`check-remote` делает только query сохранённого attempt/request digest (или проверяет уже
сохранённый ответ). Не исполняет модель, не импортирует результат и не снимает workflow hold.
Ответ `EVIDENCE_STORED:<sha256>` — ещё не Tests/Review PASS. Другой ответ оставляет NEEDS_OWNER.
`resume-remote` повторно проверяет текущие Owner-разрешения/Issue/policy/profiles и принимает
только завершённый результат с указанным digest. Импорт и переход выполняются без нового
submit. После сбоя сразу перед hold он адресно восстанавливает именно сохранённый pending
intent. После сбоя между внутренней run.db и внешней service.db повтор Owner-команды
идемпотентен по сохранённому digest. Оба журнала сохраняют решение и доказательства.

Если одновременно потерян ответ на status-comment, сначала нужны существующие `check-status`
и `resume-status`. Подтверждение комментария восстанавливает нижележащий remote hold, не
снимая его; затем отдельно согласуется remote result. Ни комментарий, ни результат не
переотправляются. Существующие UNKNOWN публикации не сбрасываются этими командами.

Повреждение SQLite/импорта, нарушения состояния и другие внутренние исключения остаются
внутренними ошибками и останавливают службу с диагностикой типов/мест в коде без токенов.
Универсального reset/retry/force нет.

## Конфигурация и следующая живая проверка

[Пример ssh-vm](../config/personal-ssh-vm.properties.example) выключен. Endpoint и SSH command
по-прежнему фиксированы: `vboxuser@127.0.0.1:2222`, без agent/port forwarding, с отдельными
private identity/known_hosts. Содержимое known_hosts и путь ключа входят в pinned peer.
Ни PAT, ни HOME, ни рабочие каталоги контроллера не входят в request или SSH environment.
`agentHome`/`codex` остаются совместимыми полями конфигурации; remote route не требует
локальной установки/авторизации Codex и не использует эти пути для исполнения.

Этот PR не устанавливает и не включает службы, не меняет VM, Samsung, HOME или сохранённые
задачи, не размещает постоянные токены на MSI. Guest resources/protocol не изменены относительно
PR #48. Уже успешные проверки SSH/установки/login и старый E2E не повторяются.

До одного настоящего запуска нужны Owner/Chief approval исходников и отдельный Owner GO на:
развёртывание согласованной сборки/worker (если ещё не развёрнут), временный запуск контроллера
на MSI с разрешённым scoped PAT и **одну** новую тестовую задачу → VM Coding/Tests/Review →
контроллерный Draft PR. Для управляемого рабочего MSI сохраняется требование разрешения
Owner/администратора на credentials. Постоянная служба и постоянные токены требуют отдельного
решения. Песочницу ОС не отключать. Samsung пока не обновлять и его службу не включать.
Живой VM E2E этим PR **не подтверждён**, production-ready не заявляется. #43/#44 вне scope.

## Проверки

`RemoteWorkflowIntegrationTest` использует настоящие Kotlin service/engine/publisher, SQLite,
Git bundle/tree, Python guest worker/supervisor/harness и bwrap. Только Codex, SSH transport и
GitHub endpoints заменены локальными фикстурами; inference/SSH/реального POST нет. Проверяются:
полная цепочка и исправления, Python canary, подмена identity/импорта/receipt, отдельная Review
session, лимит исправлений, потеря ответа на каждой роли, crash на границах fsync/rename/commit,
согласование двух БД, двойная неопределённость status+remote и отсутствие повторного Draft POST.
Точные результаты, CI и независимое source review указаны в новом Draft PR.
