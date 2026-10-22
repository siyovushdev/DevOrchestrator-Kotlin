package dev.orchestrator.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.storage.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.time.Instant

/** This lock spans the WHOLE process lifetime, including recovery and all network/model calls. */
internal class ServiceLock(root: Path) : AutoCloseable {
    private val channel=FileChannel.open(root.resolve("service.lock"),CREATE,WRITE)
    private val lock=try { checkNotNull(channel.tryLock()) { "PERSONAL service already running" } }
        catch(e: Exception) { channel.close(); throw e }
    override fun close() { lock.release(); channel.close() }
}

internal class PersonalService(private val file: Path, private val github: ServiceGitHub,
    private val runtime: TaskRuntime, private val permitted: () -> Boolean) {
    private val db=RuntimeDatabase(file)
    private val mapper=ObjectMapper()
    init { db.write { c ->
        c.mutate("""CREATE TABLE IF NOT EXISTS personal_tasks(
            issue_id INTEGER PRIMARY KEY, number INTEGER NOT NULL UNIQUE, source TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('accepted','preparing','active','done','held')),
            reason TEXT, stage TEXT NOT NULL DEFAULT 'accepted', url TEXT)""")
        c.mutate("""CREATE TABLE IF NOT EXISTS personal_status(
            issue_id INTEGER NOT NULL REFERENCES personal_tasks(issue_id), stage TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('sending','confirmed','uncertain')),
            PRIMARY KEY(issue_id,stage))""")
        val statusColumns=c.rows("PRAGMA table_info(personal_status)") { it.getString("name") }.toSet()
        if ("body" !in statusColumns) c.mutate("ALTER TABLE personal_status ADD COLUMN body TEXT")
        val taskColumns=c.rows("PRAGMA table_info(personal_tasks)") { it.getString("name") }.toSet()
        if ("resume_state" !in taskColumns) c.mutate("ALTER TABLE personal_tasks ADD COLUMN resume_state TEXT")
        if ("resume_reason" !in taskColumns) c.mutate("ALTER TABLE personal_tasks ADD COLUMN resume_reason TEXT")
        if ("summary" !in taskColumns) c.mutate("ALTER TABLE personal_tasks ADD COLUMN summary TEXT")
        ensureOwnerSchema(c)
        c.mutate("""CREATE TABLE IF NOT EXISTS personal_reconciliations(
            id INTEGER PRIMARY KEY, issue_id INTEGER NOT NULL, stage TEXT NOT NULL,
            recorded_at TEXT NOT NULL, actor TEXT NOT NULL, evidence TEXT NOT NULL)""")
        c.mutate("""CREATE TABLE IF NOT EXISTS personal_failures(
            id INTEGER PRIMARY KEY, recorded_at TEXT NOT NULL, diagnostic TEXT NOT NULL)""")
    } }
    /** Store types and source locations, never arbitrary exception messages (may contain credentials). */
    fun recordFailure(failure: Exception): String {
        val diagnostic = safeServiceDiagnostic(failure)
        db.write { it.mutate("INSERT INTO personal_failures(recorded_at,diagnostic) VALUES (?,?)",Instant.now().toString(),diagnostic) }
        return diagnostic
    }
    private fun requireHealthy() {
        val failure=db.read { it.rows("SELECT diagnostic FROM personal_failures ORDER BY id LIMIT 1") { it.getString(1) }.singleOrNull() }
        if (failure!=null) throw ServiceStopped("SERVICE_STOPPED_REQUIRES_OWNER: $failure")
    }
    private data class Task(val issue: GitHubIssueRecord,val state: String,val stage: String,val url: String?,val reason: String?)
    private fun tasks(): List<Task> = db.read { c -> c.rows("SELECT source,state,stage,url,reason FROM personal_tasks ORDER BY number") {
        val n=mapper.readTree(it.getString(1))
        Task(GitHubIssueRecord(n["repositoryId"].asLong(),n["issueNumber"].asInt(),n["githubIssueId"].asLong(),
            Instant.parse(n["sourceUpdatedAt"].asText()),n["title"].asText(),n["body"].asText(),n["baseSha"].asText(),true),
            it.getString(2),it.getString(3),it.getString(4),it.getString(5))
    } }
    private fun hold(i: GitHubIssueRecord,reason: String) = db.write { c ->
        if (reason=="STATUS_OUTCOME_UNKNOWN") {
            c.mutate("""UPDATE personal_tasks SET resume_state=CASE WHEN reason!='STATUS_OUTCOME_UNKNOWN' OR reason IS NULL THEN state ELSE resume_state END,
                resume_reason=CASE WHEN reason!='STATUS_OUTCOME_UNKNOWN' OR reason IS NULL THEN reason ELSE resume_reason END,
                state='held',reason=? WHERE issue_id=?""",reason,i.githubIssueId)
        } else c.mutate("UPDATE personal_tasks SET state='held',stage='held',reason=?,resume_state=NULL,resume_reason=NULL WHERE issue_id=?",reason,i.githubIssueId)
    }
    private val terminalStages=setOf("cancelled","cancel-refused","released","timed_out","unattested")
    fun recover() {
        requireHealthy()
        for(t in tasks()) {
            if (t.stage in terminalStages) continue
            val pending=db.read { c -> c.rows("SELECT 1 FROM personal_status WHERE issue_id=? AND state!='confirmed'",t.issue.githubIssueId) { 1 }.isNotEmpty() }
            if (pending) {
                db.write { it.mutate("UPDATE personal_status SET state='uncertain' WHERE issue_id=? AND state='sending'",t.issue.githubIssueId) }
                hold(t.issue,"STATUS_OUTCOME_UNKNOWN")
            }
            if(t.state=="preparing") hold(t.issue,"PREPARATION_INTERRUPTED")
            if(t.state=="active" && !pending) runtime.recover(t.issue)
        }
    }
    fun tick(): String {
        requireHealthy()
        if(!permitted()) return "OFF"
        // An unresolved task blocks the entire one-worker queue, including different task databases.
        val previous=tasks()
        for (t in previous.filter { it.state in setOf("done","held") }) {
            if (t.reason=="STATUS_OUTCOME_UNKNOWN" || !status(t.issue,t.stage,t.url)) return "NEEDS_OWNER"
        }
        noteBlockedCancels(previous)
        if(previous.any { it.state=="held" }) return "NEEDS_OWNER"
        val pendingCancel=previous.firstOrNull { cancellable(it) }
        if (pendingCancel!=null) return applyOwnerCancel(pendingCancel)
        var task=previous.firstOrNull { it.state!="done" }
        if(task==null) {
            val issues=github.issues()
            require(issues.all { it.repositoryId==1424152600L })
            require(issues.groupBy { it.githubIssueId }.values.all { it.size==1 } &&
                issues.groupBy { it.issueNumber }.values.all { it.size==1 })
            val next=issues.sortedBy { it.issueNumber }.firstOrNull { i ->
                i.open && previous.none { it.issue.githubIssueId==i.githubIssueId || it.issue.issueNumber==i.issueNumber } && github.authorized(i)
            } ?: return "IDLE"
            check(permitted())
            db.write { it.mutate("INSERT INTO personal_tasks(issue_id,number,source,state) VALUES (?,?,?,'accepted')",
                next.githubIssueId,next.issueNumber,mapper.writeValueAsString(mapOf("repositoryId" to next.repositoryId,"issueNumber" to next.issueNumber,
                    "githubIssueId" to next.githubIssueId,"sourceUpdatedAt" to next.sourceUpdatedAt.toString(),
                    "title" to next.title,"body" to next.body,"baseSha" to next.baseSha))) }
            task=tasks().single { it.issue.githubIssueId==next.githubIssueId }
            if (ownerCancelRequested(file.parent, task.issue.issueNumber)) return applyOwnerCancel(task)
        }
        val i=task.issue
        if (cancellable(task)) return applyOwnerCancel(task)
        if(!github.authorized(i)) { hold(i,"OWNER_LABEL_OR_ISSUE_CHANGED"); status(i,"held"); return "NEEDS_OWNER" }
        try {
            if(!status(i,task.stage,task.url)) return "NEEDS_OWNER"
            if(!permitted()) return "OFF"
            if(task.state=="accepted") {
                db.write { it.mutate("UPDATE personal_tasks SET state='preparing' WHERE issue_id=? AND state='accepted'",i.githubIssueId) }
                runtime.prepare(i)
                db.write { it.mutate("UPDATE personal_tasks SET state='active',stage='coding' WHERE issue_id=?",i.githubIssueId) }
            } else {
                val progress=runtime.step(i)
                if (progress.stage=="cancelled" || progress.reason=="ALREADY_PUBLISHED") return applyOwnerCancel(task)
                if (progress.stage in setOf("timed_out","unattested")) return finishTerminal(i, progress.stage, progress.reason ?: progress.stage)
                require(progress.stage in setOf("coding","tests","review","done","held"))
                if(progress.stage=="done") require(progress.url?.matches(Regex("https://github.com/siyovushdev/devorch-kotlin-e2e-smoke/pull/[1-9][0-9]*"))==true)
                val summary=if (progress.stage=="done") runtime.summary(i).take(1200) else null
                db.write { it.mutate("UPDATE personal_tasks SET state=?,stage=?,url=?,reason=?,summary=? WHERE issue_id=?",
                    if(progress.stage in setOf("done","held")) progress.stage else "active",progress.stage,progress.url,progress.reason,summary,i.githubIssueId) }
            }
            val now=tasks().single { it.issue.githubIssueId==i.githubIssueId }
            if (!status(i,now.stage,now.url)) return "NEEDS_OWNER"
            return now.state.uppercase()
        } catch(failure: Exception) {
            hold(i,"INTERNAL_ACTION_ERROR")
            throw IllegalStateException("ACTION_STOPPED_REQUIRES_OWNER",failure)
        }
    }
    private fun status(i: GitHubIssueRecord,stage: String,url: String?=null): Boolean {
        val old=db.read { c -> c.rows("SELECT state FROM personal_status WHERE issue_id=? AND stage=?",i.githubIssueId,stage) { it.getString(1) }.singleOrNull() }
        if(old!=null) return old=="confirmed"
        if(!permitted()) return false
        val text=when(stage) {
            "accepted" -> "Задача принята. Запущу работу по согласованному описанию."
            "coding" -> "Работаю над задачей."
            "tests" -> "Проверяю результат тестами."
            "review" -> "Результат проходит независимое ревью. При необходимости исправлю замечания."
            "done" -> {
                val summary=db.read { c -> c.rows("SELECT summary FROM personal_tasks WHERE issue_id=?",i.githubIssueId) { it.getString(1) }.singleOrNull() }.orEmpty()
                val base="Готов черновик PR: $url . Слияние выполняет владелец."
                if (summary.isBlank()) base else "$base\n$summary"
            }
            "cancelled" -> "Задача отменена владельцем. Новые агенты и публикация не запускаются. Уже отправленные действия GitHub не удаляются и не повторяются."
            "cancel-refused" -> "Отмена не закрывает уже созданный черновик PR: $url . Закрытие PR остаётся решением владельца."
            "timed_out" -> "Стадия превысила лимит времени и остановлена. Повтора не будет. Следующая задача в очереди может начаться."
            "unattested" -> "Запуск не подтверждён как настоящий Codex. Повтора не будет. Следующая задача в очереди может начаться."
            "released" -> "Владелец закрыл застрявшую задачу. Публикация не повторялась. Очередь свободна."
            else -> {
                val reason=db.read { c -> c.rows("SELECT reason FROM personal_tasks WHERE issue_id=?",i.githubIssueId) { it.getString(1) }.singleOrNull() }
                when(reason) {
                    "OWNER_LABEL_OR_ISSUE_CHANGED" -> "Остановлено: задача или разрешение изменились. Владельцу нужно сверить согласованное ТЗ и решить, нужна ли новая задача."
                    "STATUS_OUTCOME_UNKNOWN" -> "Остановлено: не удалось подтвердить сообщение в GitHub. Владельцу нужно проверить Issue и сохранённый статус перед продолжением. Повторной отправки не будет."
                    "PREPARATION_INTERRUPTED" -> "Остановлено: подготовка прервана перезапуском. Владельцу нужно проверить сохранённую задачу и завершение старого процесса. Повторного запуска не будет."
                    "EFFECT_OR_PROCESS_UNCONFIRMED" -> "Отмена запрошена, но процесс или отправка в GitHub ещё не подтверждены. Это не успешная отмена и не повтор. Владельцу нужно сверить сохранённый результат."
                    "CANCEL_UNKNOWN" -> "Отмена не подтверждена. Автоматического повтора и новой публикации не будет."
                    else -> "Остановлено: результат последнего шага не подтверждён. Владельцу нужно проверить сохранённые тесты, ревью и GitHub PR перед продолжением. Автоматического повтора не будет."
                }
            }
        }
        // Intent BEFORE POST. Lost response or crash never permits another POST for this stage.
        val marker="\n\n<!-- devorch-status:${java.util.UUID.randomUUID()} -->"
        val body="DevOrchestrator: ${text.take(2000-marker.length)}"+marker
        db.write { it.mutate("INSERT INTO personal_status(issue_id,stage,state,body) VALUES (?,?,'sending',?)",i.githubIssueId,stage,body) }
        try {
            github.postStatus(i.issueNumber,body)
        } catch(failure: Exception) {
            db.write { it.mutate("UPDATE personal_status SET state='uncertain' WHERE issue_id=? AND stage=?",i.githubIssueId,stage) }
            hold(i,"STATUS_OUTCOME_UNKNOWN")
            if (failure is GitHubUnavailable || failure is GitHubRejected) return false
            throw failure
        }
        db.write { check(it.mutate("UPDATE personal_status SET state='confirmed' WHERE issue_id=? AND stage=? AND state='sending'",i.githubIssueId,stage)==1) }
        return true
    }
    private fun unresolved(number: Int, stage: String): Pair<Task,String> {
        requireHealthy()
        val task=tasks().single { it.issue.issueNumber==number }
        check(task.state=="held" && task.reason=="STATUS_OUTCOME_UNKNOWN") { "Only uncertain status comments can be reconciled" }
        val body=db.read { c -> c.rows("SELECT body FROM personal_status WHERE issue_id=? AND stage=? AND state='uncertain'",
            task.issue.githubIssueId,stage) { it.getString(1) }.singleOrNull() }
        check(!body.isNullOrBlank()) { "No exact saved comment intent; NEEDS_OWNER (legacy or unknown)" }
        return task to body
    }

    /** Read-only local/remote inspection. A failed/ambiguous search never clears the hold. */
    fun checkStatus(number: Int, stage: String): StatusEvidence? {
        val (task,body)=unresolved(number,stage)
        return github.findStatus(task.issue,body)
    }

    /** Explicit local Owner command, scoped to one exact comment, never a generic UNKNOWN reset. */
    fun resumeStatus(number: Int, stage: String, commentId: Long): Boolean {
        val (task,body)=unresolved(number,stage)
        val evidence=github.findStatus(task.issue,body) ?: return false
        if (commentId!=evidence.id) return false
        db.write { c ->
            check(c.mutate("UPDATE personal_status SET state='confirmed' WHERE issue_id=? AND stage=? AND state='uncertain' AND body=?",
                task.issue.githubIssueId,stage,body)==1)
            c.mutate("INSERT INTO personal_reconciliations(issue_id,stage,recorded_at,actor,evidence) VALUES (?,?,?,?,?)",
                task.issue.githubIssueId,stage,Instant.now().toString(),"owner:local-command",mapper.writeValueAsString(evidence))
            val pending=c.rows("SELECT 1 FROM personal_status WHERE issue_id=? AND state!='confirmed'",task.issue.githubIssueId) { 1 }.isNotEmpty()
            if (!pending) check(c.mutate("""UPDATE personal_tasks SET state=resume_state,reason=CASE WHEN resume_state='held' THEN resume_reason ELSE NULL END,resume_state=NULL,resume_reason=NULL
                WHERE issue_id=? AND state='held' AND reason='STATUS_OUTCOME_UNKNOWN' AND (resume_state IN ('accepted','active','done') OR (resume_state='held' AND resume_reason IS NOT NULL AND resume_reason!='STATUS_OUTCOME_UNKNOWN'))""",
                task.issue.githubIssueId)==1)
        }
        return true
    }

    fun checkRemote(number: Int,id: String): String {
        requireHealthy()
        val task=tasks().single { it.issue.issueNumber==number }
        check(task.state=="active" || (task.state=="held" && task.reason=="REMOTE_ACTION_UNKNOWN:$id"))
        return runtime.checkRemote(task.issue,id)
    }
    /** Cross-database restart is idempotent: the inner import retains the exact Owner digest. */
    fun resumeRemote(number: Int,id: String,digest: String): Boolean {
        requireHealthy()
        val task=tasks().single { it.issue.issueNumber==number }
        check(task.state=="active" || (task.state=="held" && task.reason=="REMOTE_ACTION_UNKNOWN:$id"))
        if(!permitted() || !github.authorized(task.issue)) return false
        if(db.read { c -> c.rows("SELECT 1 FROM personal_status WHERE issue_id=? AND state!='confirmed'",task.issue.githubIssueId) { 1 }.isNotEmpty() }) return false
        val progress=runtime.resumeRemote(task.issue,id,digest) ?: return false
        db.write { c ->
            check(c.mutate("""UPDATE personal_tasks SET state=?,stage=?,reason=?,resume_state=NULL
                WHERE issue_id=? AND (state='active' OR (state='held' AND reason=?))""",
                if(progress.stage=="held") "held" else "active",progress.stage,progress.reason,task.issue.githubIssueId,"REMOTE_ACTION_UNKNOWN:$id")==1)
            c.mutate("INSERT INTO personal_reconciliations(issue_id,stage,recorded_at,actor,evidence) VALUES(?,?,?,?,?)",
                task.issue.githubIssueId,"remote",Instant.now().toString(),"owner:local-command",mapper.writeValueAsString(mapOf("attemptId" to id,"evidenceSha256" to digest)))
        }
        return true
    }

    fun localStatus(): String = tasks().joinToString("\n") { "Issue #${it.issue.issueNumber}: ${it.state} / ${it.stage} ${it.reason.orEmpty()}${it.url?.let { u -> " $u" } ?: ""}" }.ifEmpty { "No tasks" }

    /** Leaves workflow holds and publication rows in place. Does not delete service.db or send PUSH/POST. */
    fun releaseHeld(number: Int, ssh: dev.orchestrator.remote.SshWorkerConfig? = null): String {
        requireHealthy()
        val task=tasks().singleOrNull { it.issue.issueNumber==number } ?: return "NO_TASK"
        if (task.state=="done" && task.stage in terminalStages) return "ALREADY_TERMINAL"
        if (task.state!="held") return "NOT_HELD"
        val runDb=file.parent.resolve("task-${task.issue.githubIssueId}/run.db")
        dev.orchestrator.agent.nativecli.LocalProcessControl.stopOrphans(runDb)
        if (ssh!=null) dev.orchestrator.agent.nativecli.LocalProcessControl.cancelGuests(runDb, ssh)
        db.write { c ->
            c.mutate("""UPDATE personal_tasks SET state='done',stage='released',reason='OWNER_RELEASED',resume_state=NULL,resume_reason=NULL
                WHERE issue_id=? AND state='held'""", task.issue.githubIssueId)
            c.mutate("INSERT INTO owner_audit(recorded_at,actor,action,scope,detail) VALUES(?,?,?,?,?)",
                Instant.now().toString(),"owner:local-command","RELEASE","issue:$number","queue released; no replay")
        }
        return "RELEASED"
    }
    private fun finishTerminal(i: GitHubIssueRecord, stage: String, reason: String): String {
        db.write { c -> c.mutate("""UPDATE personal_tasks SET state='done',stage=?,reason=?,resume_state=NULL,resume_reason=NULL WHERE issue_id=?""",
            stage, reason, i.githubIssueId) }
        if (!status(i, stage, null)) return "NEEDS_OWNER"
        return stage.uppercase()
    }

    private fun cancellable(task: Task)=ownerCancelRequested(file.parent, task.issue.issueNumber) &&
        task.state!="held" && task.stage !in terminalStages
    private fun noteBlockedCancels(previous: List<Task>) {
        for (t in previous.filter { it.state=="held" && ownerCancelRequested(file.parent, it.issue.issueNumber) }) {
            val state=db.read { c -> c.rows("SELECT state FROM owner_cancels WHERE issue_number=?", t.issue.issueNumber) { it.getString(1) }.singleOrNull() }
            if (state==null || state=="requested") recordOwnerCancel(file, t.issue.issueNumber, "blocked", "HELD_NOT_CLEARED")
        }
    }
    private fun applyOwnerCancel(task: Task): String {
        val i=task.issue
        if (task.state=="done" && task.stage=="cancelled") {
            if (!status(i,"cancelled",task.url)) return "NEEDS_OWNER"
            return "CANCELLED"
        }
        if (task.state=="done" && task.stage in terminalStages) {
            if (!status(i, task.stage, task.url)) return "NEEDS_OWNER"
            return "TERMINAL"
        }
        val outcome=runtime.cancel(i)
        if (outcome.stage=="timed_out" || outcome.reason=="STAGE_TIMEOUT") return finishTerminal(i, "timed_out", "STAGE_TIMEOUT")
        if (outcome.stage=="unattested") return finishTerminal(i, "unattested", outcome.reason ?: "CODEX_NOT_ATTESTED")
        when (outcome.reason) {
            "NOT_STARTED","OWNER_CANCELLED" -> {
                db.write { c -> c.mutate("""UPDATE personal_tasks SET state='done',stage='cancelled',reason='OWNER_CANCELLED',url=NULL,
                    resume_state=NULL,resume_reason=NULL WHERE issue_id=?""", i.githubIssueId) }
                recordOwnerCancel(file, i.issueNumber, "cancelled", outcome.reason)
                if (!status(i,"cancelled",null)) return "NEEDS_OWNER"
                return "CANCELLED"
            }
            "ALREADY_PUBLISHED" -> {
                val url=outcome.url ?: task.url
                db.write { c -> c.mutate("""UPDATE personal_tasks SET state='done',stage='cancel-refused',reason='ALREADY_PUBLISHED',url=?,
                    resume_state=NULL,resume_reason=NULL WHERE issue_id=?""", url, i.githubIssueId) }
                recordOwnerCancel(file, i.issueNumber, "blocked", "ALREADY_PUBLISHED")
                if (!status(i,"cancel-refused",url)) return "NEEDS_OWNER"
                return "ALREADY_PUBLISHED"
            }
            else -> {
                val reason=outcome.reason ?: "CANCEL_UNKNOWN"
                hold(i, reason)
                recordOwnerCancel(file, i.issueNumber, "unknown", reason)
                if (!status(i,"held",null)) return "NEEDS_OWNER"
                return "NEEDS_OWNER"
            }
        }
    }
}
