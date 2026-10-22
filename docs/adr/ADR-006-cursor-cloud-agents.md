# ADR-006 — Исполнитель: Cursor Cloud Agents API

**Статус: принято для контура `runtimeMode=cursor-cloud`.**  
**Область:** личный тестовый репозиторий `siyovushdev/devorch-kotlin-e2e-smoke`. WORK и production вне допуска.

## Решение

1. Исполнитель Coding и Review — официальный Cloud Agents API `https://api.cursor.com/v1/agents`. Cursor запускает агентов. Kotlin не содержит своего цикла модели, инструментов и песочницы агента.
2. TypeScript SDK `@cursor/sdk` не используется. Для JVM это был бы второй рантайм вокруг того же API. Прямой HTTPS проще и не добавляет Node.
3. Coding и Review — два разных `POST /v1/agents`, два `agentId`. Исправление — новая сессия Coding, не продолжение Review.
4. `autoCreatePR=false` и `workOnCurrentBranch=false`. Агент не открывает pull request и не пишет в `main`. Единственный Draft PR создаёт прежний Publisher.
5. Тесты остаются контроллерными: `verify-python-unittest.py` внутри bubblewrap. В процесс тестов не попадают `CURSOR_API_KEY` и `DEVORCH_PERSONAL_TOKEN`.
6. Намерение создать агента записывается до POST. Потерянный ответ, 401/403 и обрыв изоляции не создают второго агента. `check-remote` только читает сохранённый id. `resume-remote` импортирует уже сохранённый результат и не вызывает create.
7. Отмена — `POST /v1/agents/{id}/runs/{runId}/cancel`. Неподтверждённая остановка не считается успешной отменой.
8. Лимиты циклов 0..2 и времени стадии 30..3600 с сохраняются. У API v1 нет поля денежного потолка, поэтому `monetary_cap_enforced` остаётся false. Новый ключ и платный запуск сверх уже выданного бюджета — отдельное решение владельца.
9. Ключ читается только из `CURSOR_API_KEY`. Сессионные токены текущего агента не извлекаются. В конфиг, Issue и argv ключ не пишется.

## Что не меняется

Журнал попыток, один Draft PR, UNKNOWN без повтора публикации, `exclude` для Issue #6 без `resume-status` и `resume-remote`, запрет merge. Локальный Codex и SSH в этом режиме не запускаются.
