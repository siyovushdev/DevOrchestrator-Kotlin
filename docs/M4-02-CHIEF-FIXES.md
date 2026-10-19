# M4-02: изоляция PERSONAL execution

Дополняет ADR-003 Native PERSONAL MVP по явному Owner-заданию #45 / Chief review #42.
Kotlin, SQLite, Codex CLI, контроллерный unittest и Publisher сохранены. Docker,
новые службы, UI, отмена #43 и динамические лимиты #44 не добавляются.

## Граница Linux

Обязателен пакет Ubuntu `bubblewrap` (`/usr/bin/bwrap`). Все Coding/Review CLI
и их потомки запускаются в новом mount/user/PID/IPC/UTS/cgroup namespace с пустым
корнем, private `/proc`, `/dev`, `/tmp`, без capabilities. Никакого bind `/`,
host `/proc`, пользовательского HOME, controller state, sockets или токена GitHub.
Доступны read-only системные `/usr`, библиотеки и необходимые SSL/DNS файлы;
только checkout (Coding RW, Review RO), только `.codex` отдельного HOME модели и выходной каталог.
Supervisor, его launch/receipt/log и SQLite остаются снаружи.

Диспетчер unittest остаётся доверенным и не импортирует кандидатный Python.
Каждый discovery/test interpreter получает отдельный bubblewrap с RO снимком
кода, без HOME модели и без сети. Его дочерние процессы наследуют границу.
Путь отчёта и supervisor receipt в namespace отсутствуют.

`.codex` HOME модели — **намеренно переданное полномочие** Codex CLI: CLI должен читать
свою inference auth. Только отдельная auth модели, никакого GitHub PAT, SSH,
общих credentials, MCP или каталогов пользователя. От test worker скрыта и она.
Сеть Codex сохранена для уже существующего localhost model proxy; это не egress
allowlist и не VM. Не размещать controller/state API на доступных localhost
портах. Изоляция файлов и процессов обеспечивается ядром, а не Python `-I`,
очисткой env или chmod. Штатный sandbox Codex не отключён.

Недоступность bwrap/namespaces блокирует запуск. Нет unsafe fallback.
Preflight перед сервисом и durable Coding/Tests/Review action не запускает модель.
Внешний supervisor наблюдает bwrap; завершение PID namespace уничтожает его
потомков. PID внутри namespace нельзя интерпретировать как PID хоста.

## Ubuntu / Samsung: только инструкция Owner

Не устанавливать и не запускать ничего на Samsung из этой ветки автоматически.
После Owner/Chief approval:

1. Оставить службу остановленной. Сделать резервную копию существующих конфигурации,
   SQLite и artifacts обычным доверенным способом; не удалять/сбрасывать задачи.
2. На Ubuntu установить официальный пакет: `sudo apt-get update`, затем
   `sudo apt-get install bubblewrap`. Не менять глобальные sysctl, не выключать
   AppArmor, не делать bwrap setuid и не давать контроллеру sudo.
3. От выделенного PERSONAL пользователя выполнить **без credentials**:
   `/usr/bin/bwrap --unshare-user --unshare-pid --unshare-net --ro-bind /usr /usr --symlink usr/lib /lib --symlink usr/lib64 /lib64 --proc /proc --dev /dev -- /usr/bin/true`.
   Если AppArmor/userns запрещает операцию, остановиться. Нужен поддерживаемый
   Ubuntu пакет/profile для `/usr/bin/bwrap`, проверенный администратором;
   разрешение конкретного пакета не заменять глобальным отключением защиты.
4. Подготовить новую disposable копию approved source и прогнать адресный
   canary regression (fake secrets only). Проверить доступность native Codex и
   `code-mode-host` в поддерживаемой установке: системный `/usr` либо native binary
   с соседним helper. Произвольные Node wrappers/скрытые зависимости не пробрасываются;
   несовместимость должна остановить запуск, а не расширить mounts HOME.
5. Только после успешных локальных проверок заменить дистрибутив приложения,
   сохранив прежние config/DB/artifacts. Новые поля служебной БД добавляются
   аддитивно; неизвестные старые эффекты не превращаются в новые попытки.
6. Реальный запуск Codex внутри новой границы и включение службы требуют отдельного
   Owner GO. Предыдущий успешный Issue #4 → Draft PR #5 не повторять как формальность.

Локальный canary доказывает запрет доступа в проверенном Linux окружении, включая
дочерний Python вместо модели. Он не доказывает совместимость реального Codex,
proxy/auth и AppArmor на Samsung. До этой проверки эксплуатационная готовность
новой границы на Samsung не подтверждена.

Справка механизма: [официальный bubblewrap](https://github.com/containers/bubblewrap),
[параметры bwrap](https://github.com/containers/bubblewrap/blob/main/bwrap.xml).

## Ошибки и безопасная остановка

Только явная транспортная ошибка GitHub GET / HTTP 429 или 5xx до действия
даёт `GITHUB_UNAVAILABLE_NO_ACTION` и обычный следующий опрос. HTTP отказ доступа
даёт `GITHUB_ACCESS_STOPPED_HTTP_<code>`, не ошибку контроллера. SQLite, нарушение
инвариантов, повреждённый JSON и исключения runtime останавливают процесс.

`personal_failures` сохраняет время, классы причин, места в исходниках и
безопасный HTTP code. Тексты исключений/HTTP body исключены: они могут содержать
credentials. Локальная команда `status` показывает сохранённый запрет. После
ошибки существует durable latch: рестарт службы не повторит Coding/Review/PUSH/
POST. При неработающей SQLite процесс всё равно завершается с безопасным указанием
типа сбоя и невозможности сохранить диагностику. CLI завершает такой запуск с кодом 70; пример systemd unit запрещает его
автоматический restart. Owner переносит это поле в unit только при согласованном
обновлении. Универсальной команды reset нет:
Owner/Chief разбирают причину и подтверждённые durable effects перед отдельным
исправлением. Не редактировать SQLite вручную ради повторного запуска.

## Согласование неопределённого комментария

Новый status intent сохраняет точное тело и случайный UUID marker **до POST**.
Потеря ответа удерживает задачу и очередь. Автоматического POST этого stage больше
нет, в том числе после crash между POST и записью подтверждения. Старые Coding,
Review и Publisher journals и их UNKNOWN не сбрасываются.

В существующем доверенном окружении выделенного пользователя (токен только через
прежний приватный env-канал), пока служба остановлена:

```
app/bin/dev-orchestrator-kotlin status /absolute/personal.properties
app/bin/dev-orchestrator-kotlin check-status /absolute/personal.properties 17 tests
app/bin/dev-orchestrator-kotlin resume-status /absolute/personal.properties 17 tests 123456789
```

Числа и stage взять из **своего** `status` и результата проверки, это пример.
`check-status` открывает SQLite read-only без миграций и делает только GitHub GET:
точная Issue identity, полная история до 1000 comments, единственное полное тело,
затем GET конкретного comment с проверкой id/body/owner/Issue URL/comment URL.
`CONFIRMED_REMOTE` само по себе ничего не продолжает. Нулевой/неоднозначный ответ,
неполная история, изменённое сообщение или отсутствие сохранённого тела/marker
оставляют `NEEDS_OWNER`. Сетевой сбой проверки также ничего не разрешает.

`resume-status` требует тот же OS lock, выполняет обычный recovery журналов без
запуска моделей/PUSH/POST, **ещё раз делает GET** и сверяет указанный comment ID.
В одной SQLite transaction подтверждает только этот status intent, записывает
`personal_reconciliations` (время, Owner-команда, полное evidence) и возвращает
сохранённое accepted/active/done состояние только при отсутствии других pending
comments. Команда не запускает очередь: службу Owner включает отдельно.
Следующий обычный шаг снова проверяет текущие Owner-разрешения и runtime journals.

У старых неопределённых комментариев без сохранённого UUID-тела достоверного
автоматического matching нет. Они остаются `NEEDS_OWNER`; миграция не выдумывает
доказательства. Нет команд force/retry/reset для неизвестных model/PUSH/PR effects,
ошибок подготовки, внутренних ошибок или иных holds.
