# Запуск, отмена, лимиты и диагностика

Один персональный сервис. Интерфейс задачи — GitHub Issue в
`siyovushdev/devorch-kotlin-e2e-smoke`. Текст Issue не даёт прав и не меняет лимиты.
Merge всегда вручную. Команды ниже не открывают HTTP-порт и не ставят службу сами.

## Профиль по умолчанию: Cursor CLI

Рабочий путь — `runtimeMode=cursor-cli` и `profile=default`. Пример: `config/personal-cursor-cli.properties.example`.
Роли coder и reviewer запускают уже залогиненный `agent` на контроллере: `-p --trust --force --sandbox disabled`.
Отдельной песочницы, bubblewrap и SSH на этом пути нет. Ключ `CURSOR_API_KEY` не нужен: auth профиля — `subscription`.
Сервис запускается от уже залогиненного пользователя. Отдельная учётная запись Unix на этом профиле не нужна.
Если бинарник `cursorCli` не исполняемый, стадия останавливается. Перехода на cloud или Codex нет.
Одна стадия — один адаптер. Coding и Review — две разные сессии. Тесты между ними запускает контроллер.
Строка стадии пишется до запуска. UNKNOWN эту сессию не повторяет. Draft PR по-прежнему один.
Exit 0 и результат `subtype=success` при `is_error=false` завершают стадию, даже если у CLI остались дочерние процессы: контроллер их снимает. Ненулевой exit или `is_error` стадию не принимают.
Вердикт внутри текста `result` принимается. Если вердикта нет, задача останавливается без падения сервиса. Уже сохранённый успешный ответ reviewer на следующем запуске разбирается снова и второго reviewer не запускает.

Профиль `cloud-api` (`runtimeMode=cursor-cloud`) сохраняет `POST https://api.cursor.com/v1/agents` и выбирается только явно.
У версии профиля есть runtime (`cli` или `cloud`), auth (`subscription` или `api`), модель, effort,
`maxCorrections` (по умолчанию 2, не больше 2) и таймаут стадии (по умолчанию 20 минут, потолок 60).
У `default` это `cli`, `subscription`, модель `account-default` и effort `DEFAULT`.
`account-default` не передаёт `--model`. У CLI нет флага effort: значение остаётся в каталоге и в журнале стадии.
Пара `cli`+`api` и пара `cloud`+`subscription` не сохраняются.
Новая версия не переписывает старую. Уже закреплённая за задачу версия не меняется.

## Исполнитель cloud-api: Cursor Cloud Agents API

Пример: `config/personal-cursor-cloud.properties.example`.
Код не вызывает Codex CLI, не читает ключ OpenAI и не открывает локальный SSH.

Ключ только в окружении процесса: `CURSOR_API_KEY`. Это пользовательский или сервисный ключ
Cursor Dashboard → API Keys. В файл конфигурации, Issue, аргументы и репозиторий его не писать.
`doctor` печатает `cursorKey=set` или `unset` и не показывает значение. `model=account-default`
оставляет выбор модели аккаунту Cursor. Другое значение уходит как `model.id`.

Coding и Review — два разных вызова `POST /v1/agents`. У каждого свой `agentId`.
`autoCreatePR=false`: агент не открывает PR. Контроллер после тестов и ревью публикует
не больше одного Draft PR. Тесты — прежний `verify-python-unittest.py` внутри bubblewrap,
без ключа Cursor и без токена GitHub в окружении процесса.

Потолок времени и число исправлений (0..2) те же. Денежный потолок провайдера API v1 не принимает:
`monetary_cap_enforced` остаётся false. Потерянный ответ создания агента — `CURSOR_OUTCOME_UNKNOWN`.
Повторного `POST /v1/agents` для той же попытки нет. Явные `check-remote` и `resume-remote`
только читают уже созданный агент и не создают новый.

`exclude CONFIG ISSUE GITHUB_ISSUE_ID` не даёт сервису взять Issue. Команда читает GitHub,
сверяет номер и id и ничего не публикует. Для незакрытого результата Issue #6:
`exclude CONFIG 6 5927591593`. Это не `resume-status` и не `resume-remote`.

Отмена посылает `POST /v1/agents/{id}/runs/{runId}/cancel`. Если остановка не подтверждена,
это не успешная отмена. Уже открытый Draft PR не закрывается.

## Подготовка

1. JDK 21, Git, Python 3, bubblewrap (`/usr/bin/bwrap` с user/pid/net namespaces).
2. Каталоги state и agent-home — разные, mode 700, владелец того пользователя, который запускает процесс.
   Профиль `default` (`cursor-cli`) — это уже залогиненный пользователь, без отдельной учётной записи.
   Путь `ssh-vm`, локальный Codex и `cloud-api` по-прежнему требуют пользователя `devorch-e2e`.
3. Скопировать `config/personal.properties.example` вне checkout и agent-home, `chmod 600`.
   `enabled` оставить `false`, пока нет отдельного разрешения на живой запуск.
4. Токен репозитория только в окружении процесса: `DEVORCH_PERSONAL_TOKEN`.
   Не записывать его в файл конфигурации, Issue, аргументы или репозиторий.
5. Сборка: `gradle clean test build installDist` (Gradle 9.5).
   Команда: `build/install/dev-orchestrator-kotlin/bin/dev-orchestrator-kotlin`.

## Команды

```
dev-orchestrator-kotlin doctor /absolute/personal.properties
dev-orchestrator-kotlin status /absolute/personal.properties
dev-orchestrator-kotlin exclude /absolute/personal.properties 6 5927591593
dev-orchestrator-kotlin limits /absolute/personal.properties default 2 1200 1200
dev-orchestrator-kotlin limits /absolute/personal.properties 17 1 900 900
dev-orchestrator-kotlin cancel /absolute/personal.properties 17
dev-orchestrator-kotlin run /absolute/personal.properties
dev-orchestrator-kotlin service /absolute/personal.properties
```

`doctor` и `status` ничего не запускают и не требуют токен.
`limits` и `cancel` тоже не требуют токен и не запускают модель.

Лимиты: число циклов исправления 0..2. Таймаут Coding и Review — от 30 секунд до 60 минут
(3600 с). По умолчанию 20 минут (1200 с) на каждую стадию. Это потолок всего процесса
`codex exec`: старт, прокси и все раунды инструментов, не один HTTP-запрос к модели.
Прежний пилот 120 с для такого хода не хватает. Выше 60 минут команда не поднимает.
Значение для номера Issue важнее `default` и действует на следующие стадии.
Уже запущенный процесс не продлевается. Завершённые стадии и отправленные PUSH/POST не меняются.
Загрузка Git и публикация ветки остаются на своём пределе 120 с; это не время модели.

`release CONFIG ISSUE` закрывает застрявшую задачу со статусом `held` и освобождает очередь.
База не удаляется, PUSH и POST не повторяются.

В режиме `ssh-vm` поле `codex` — абсолютный путь бинарника на VM, тот же, что в конфиге гостя.
Пока процесс жив, контроллер сам читает `/proc/<pid>/exe` у потомка worker и считает elf, размер
и sha256 по этим байтам. Строка argv гостя не принимается, и тот же путь не перечитывается после
выхода процесса. Если живой файл не совпал с `codex`, стадия не подтверждается. `codexSha256` —
необязательный pin из 64 hex, его задаёт оператор. Если pin задан, другое значение sha256
останавливает стадию. Если pin не задан, остаются только проверки ELF и размера не меньше 1 МиБ:
это слабее, и достаточно большой ELF на этом пути проходит. Хеш конкретной сборки в код не зашит.

`cancel` записывает намерение и посылает сигнал локальному сироте и отдельному SSH-гостю.
Ответ гостя `CANCELLED` закрывает задачу и не держит очередь.
Результат гостя после отмены не импортируется. Ожидающая задача не стартует. Идущая локальная стадия
останавливается только если supervisor доказал завершение всех потомков; иначе задача
остаётся `NEEDS_OWNER`, а не «успешно отменена». Потерянный PUSH/POST не повторяется
и не отменяется вслепую. Уже открытый Draft PR не закрывается.

Если сервис был остановлен, следующий `run` или `service` видит ту же запись и не
начинает новую стадию. Повторная отмена не шлёт второй комментарий.

Неясный статус комментария по-прежнему снимается только явными
`check-status` и `resume-status`. Потерянный ответ удалённой стадии — `check-remote`
и `resume-remote` с точным digest. Это не общий retry.

## Восстановление

Не удалять `service.db`, `task-*`, holds и артефакты, чтобы «начать заново».
`status` читает SQLite без сети. Если там `SERVICE_STOPPED_REQUIRES_OWNER` или
`NEEDS_OWNER`, автоматического продолжения нет: сверить процесс, GitHub и локальный
статус, затем только точную команду сверки. Второй процесс с тем же корнем не стартует
из-за `service.lock`.
