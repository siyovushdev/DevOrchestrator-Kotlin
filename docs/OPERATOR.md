# Запуск, отмена, лимиты и диагностика

Один персональный сервис. Интерфейс задачи — GitHub Issue в
`siyovushdev/devorch-kotlin-e2e-smoke`. Текст Issue не даёт прав и не меняет лимиты.
Merge всегда вручную. Команды ниже не открывают HTTP-порт и не ставят службу сами.

## Подготовка

1. JDK 21, Git, Python 3, bubblewrap (`/usr/bin/bwrap` с user/pid/net namespaces).
2. Учётная запись `devorch-e2e`. Каталоги state и agent-home — разные, mode 700, владелец этой учётной записи.
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

`cancel` записывает намерение и посылает сигнал локальному сироте и отдельному SSH-гостю.
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
