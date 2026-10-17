# ADR-004 — GitHub intake и публикация Draft PR (минимальный MVP)

**Статус:** DRAFT / требуется подтверждение Owner. **Этап:** M0 · [Issue #4](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/4).  
**Основа:** ADR-001/002 APPROVED, ADR-003/S-04 Native Codex CLI APPROVED как путь MVP. Не разрешает запуск или публикацию.

## 1. Цель
Без ручного копирования: GitHub Issue разрешённого PERSONAL test repo → Coding → Tests → Independent Review → **один проверенный Draft PR**. Не смешивать задачи с GitHub-доступами, не создавать второй PR из-за потери ответа.

## 2. Приём задач — самый простой путь
- MVP использует **GitHub polling**, без webhook-сервера/публичного inbound-порта. Цикл опрашивает только явно зарегистрированные `repository_id` и Issues.
- Авторитетные идентификаторы: `(github_repository_id, issue_number)`; в SQLite это UNIQUE ключ workflow. Номер/название проекта внутри текста Issue не выдают разрешение.
- Изменение/закрытие Issue после claim **не перезапускает** агента вслепую; pinned issue body/version + HEAD SHA сохраняются. Новые данные требуют явной политики обработки (в MVP — `NEEDS_OWNER`).
- Только специально разрешённый **PERSONAL тестовый репозиторий без секретов**. Рабочие/1МТ и остальные репозитории Native MVP не исполняет.
- Intake и чтение статуса **не создают внешние write-эффекты**.

## 3. Исполнение и публикация
1. Controller создаёт отдельную копию Git проекта из точного base SHA; Codex получает только задачу и рабочий каталог, не GitHub токен через prompt/env.
2. После реальных тестов и независимого Review PASS Controller проверяет точные `base_ref/base_sha`, candidate tree SHA и отсутствие чужих изменений.
3. **Publisher** — отдельный компонент оркестратора — формирует предсказуемое имя ветки по `repo_id/issue_number/workflow_id`, проверяет target, затем делает Commit/Push/Create PR с `draft=true`.
4. После каждого внешнего write выполняется **read-after-write**: Git ref/commit SHA/PR head/base/draft должны совпасть; только тогда SQLite фиксирует `CONFIRMED` и показывает успех.
5. `UNCERTAIN` после timeout/crash → read-only reconciliation; не повторять Commit/Push/Create PR вслепую. При доказанном совпадении — восстановить, при неизвестном исходе — `NEEDS_OWNER`. См. ADR-002/F08–F11.
6. **Merge запрещён в Publisher.** Owner решает вручную. Существующий Draft PR нельзя создавать второй раз по тому же workflow.

## 4. Реальные права Native MVP
- Для первого E2E: отдельная **fine-grained GitHub credential только на тестовый репозиторий**, c Contents(write) и Pull requests(write), только если они действительно нужны интеграции; никаких токенов с доступом к WORK/1МТ.
- Агент и Publisher на **одном Linux UID**: разделение Kotlin-классов и отсутствие токена в env агента **не являются надёжной OS-изоляцией** от файлов `HOME`/credential helpers. Это осознанное ограничение раннего MVP. Не объявлять «агент не может читать ключ» гарантией.
- Перед live E2E проверить `git credential helper`, `gh auth` и `HOME` на риск доступа к более широким токенам. Если широкие креды доступны агенту и их нельзя ограничить — **не запускать автономный native smoke с реальными секретами**; использовать безопасный отдельный тестовый контекст или вернуться к Owner-решению об изоляции.
- Не считать отсутствие кнопки Merge в UI или кода Publisher ограничением GitHub-токена: права могут позволять и другие GitHub write-действия. Реальное исполнение допускается лишь при явном понимании этой границы.

## 5. Минимальное durable state
`github_repository_id`, `issue_number`, `issue_version`, `base_sha`, `candidate_tree`, `workflow_id`, `phase`, `attempt_id`, `effect_id`, `target_branch`, `commit_sha`, `pr_number`, `pr_url`, `effect_status`, `policy_version`.

**Внешний эффект:** `PREPARED → SENDING → CONFIRMED/REJECTED/UNCERTAIN` с durable записью **до** отправки. Все transitions — по ADR-002. Не создавать отдельную параллельную safety/permission базу.

## 6. Приёмка до закрытия M3
- Повтор polling одной Issue создаёт **один** workflow.
- Незнакомый repo ID и WORK без Owner GO не запускают Codex и не создают PR.
- Настоящая разрешённая тестовая Issue заканчивается реальным **Draft PR** с правильными head/base/SHA, без Merge.
- Crash после Push или после Create PR без ответа не создаёт дублей.
- Несовпадение reviewed tree блокирует публикацию с конкретной причиной.
- Краткий отчёт: Issue → Coding PASS/FAIL → Tests → Review → PR URL или один blocker.

## 7. Что отложить
Webhook, сложный GitHub App provisioning, labels как источник полномочий, multi-repo publishing, automatic Merge, PR updates after terminal workflow, управление WORK projects в нативном небезопасном профиле.

**Решение:** минимальный GitHub polling + однозначная identity + отдельный логический Publisher + подтверждение Draft PR. Сложную инфраструктуру GitHub не разрабатывать до первого E2E.
