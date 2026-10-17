# ADR-005 — Agent Adapter, независимый Review и динамические профили

**Статус:** APPROVED (Owner, 2026-10-17), как контракт и последовательность реализации; реальный Codex E2E ещё не выполнен. **Этап:** M0 · [Issue #5](https://github.com/siyovushdev/DevOrchestrator-Kotlin/issues/5).  
**Основа:** ADR-001/002 APPROVED, S-04 Native Codex CLI APPROVED для первого MVP. Без Kotlin-кода на данном этапе.

## 1. Сначала рабочий E2E, потом второй агент

**MVP-M2/M3:** один настоящий Native Codex CLI Adapter для двух **отдельных** ролей:
- Coding — изменяет код в временном PERSONAL worktree.
- Independent Review — **новый отдельный процесс/сессия**, получает pinned candidate diff/tree, возвращает findings/verdict и не меняет публикуемый результат.

После успешной цепочки Issue → Coding → Tests → Review → Draft PR подключаем второй adapter и автоматический routing (M4), не блокируя выпуск M3.

## 2. Один контракт вместо `if (agent == codex)`
- `AgentProfile`: `profile_id`, `version`, `adapter_id`, `role`, `provider`, `model`, `reasoning_effort`, `sandbox_profile`, `timeout`, `budget`, `capabilities`.
- `AgentAdapter`: `capabilities/preflight/launch/observe/stop/readArtifacts`. Реализация может быть CLI/SDK, но Core не знает конкретных аргументов Codex/Cursor.
- `AttemptInput`: workflow, exact repo/base/tree, role, task text, immutable agent profile snapshot.
- `AttemptResult`: attempt ID, process/exit/cleanup evidence, logs/artifacts, test status и candidate/reviewed tree IDs. Report агента не заменяет проверки Runner/Controller.
- `SelectionDecision`: MANUAL / AUTO_RULES / AUTO_AI, выбранный разрешённый профиль и версия, reason, estimated budget, trace ID.

**Инвариант:** новая модель/effort/profile применяется **к следующему attempt**, не перестраивает запущенный Codex и не сбрасывает историю. Auto Selector не может менять Project Registry, доступы/секреты или давать WORK Owner GO.

## 3. Native Codex Adapter: минимум

- Использовать штатный **`codex exec`** для non-interactive запуска, с проверенными локально флагами и фактической версией CLI. Кодирование — native `workspace-write`, Review — отдельный read-only context на frozen tree. **Не использовать host `--dangerously-bypass-approvals-and-sandbox` / `danger-full-access`**.
- Kotlin `ProcessBuilder` передаёт `cwd` на разрешённую временную PERSONAL копию, безопасный env, входной prompt, собирает stdout/stderr/JSON events, exit, timeout и end-of-process evidence. Не эмулировать работу с интерактивным TUI.
- Настройки model/effort проверяются по фактическому CLI `--help`, доступным моделям и профилю. Невалидное значение → конкретный `BLOCKED`; не выбирать более слабую модель молча.
- Для первого MVP допускаются существующие проверенные Codex auth/config из пользовательского сеанса, **но это не изолирует локальные credentials** от того же UID. Поэтому Native MVP — только disposable PERSONAL test repo, WORK запрещён.
- `codex exec` завершился с кодом 0 ≠ Tests PASS, Review PASS или безопасный cleanup. Итог определяется файлами, реальным diff, проверкой команд тестов и evidence.

## 4. Независимый Review и исправления

1. После Coding Controller фиксирует exact candidate tree и тестовые evidence.
2. Второй `codex exec` получает **не самоотчёт Coding**, а pinned diff/tree и Issue requirements; вердикт отдельный.
3. Даже если Review возвращает PASS, Controller проверяет, что ответ относится к **актуальному candidate tree**, reviewer не изменил публикуемую копию, а verification policy выполнена.
4. `CHANGES_REQUIRED` → максимум **2 correction cycles** (ADR-002/R-03): новая Coding attempt + новый независимый Review. Не делать третий автоматически.
5. Review FAILED/UNKNOWN без доказанного end/cleanup → ADR-002 `NEEDS_OWNER`; не повторять попытки наугад.
6. Для PERSONAL Chief не нужен перед Draft PR по ADR-002/R-04; WORK исключён из native MVP.

## 5. Динамический выбор — запланирован, но не на критическом пути

- Каталог профилей и типизированная схема появляется в M1; конфигурация Owner меняется без переписывания Core и без перезапуска сервиса.
- В M4 добавить минимум один реально проверенный **другой adapter** и проверить переключение ролей, модели и reasoning effort на двух задачах.
- `MANUAL` и `AUTO_RULES` — первые режимы; `AUTO_AI` затем выбирает только из разрешённых профилей, учитывая сложность, availability и стоимость, с объяснением и audit.
- **Второй adapter/AI routing не блокируют первый E2E M3.** Возможность расширения заложена в интерфейсе заранее.

## 6. Приёмка

- Реальный `codex exec` запущен из Kotlin в отдельном PERSONAL repo без TUI; изменённый файл и Git diff существуют.
- Отдельный реальный Review процесс на pinned tree не импортирует свои изменения в кандидата.
- Вердикт Review привязан к exact tree; stale PASS не публикуется.
- Stop/timeout/UNKNOWN не запускают второго worker без сверки (ADR-002).
- При 2 correction циклах → NEEDS_OWNER; при PASS → Draft PR (ADR-004).
- Конфигурация второго адаптера/модели не меняет Kotlin WorkflowEngine и не поднимает права проекта.

**Решение APPROVED:** один универсальный interface с первым Codex-native adapter, независимая новая Review сессия, настоящий Draft PR как результат M3, расширение/автовыбор — после этого.
