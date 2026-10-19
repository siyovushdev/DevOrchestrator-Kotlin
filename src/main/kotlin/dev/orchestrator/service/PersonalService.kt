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

internal class PersonalService(database: Path, private val github: ServiceGitHub,
    private val runtime: TaskRuntime, private val permitted: () -> Boolean) {
    private val db=RuntimeDatabase(database)
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
        c.mutate("UPDATE personal_tasks SET state='held',stage='held',reason=? WHERE issue_id=?",reason,i.githubIssueId)
    }
    fun recover() {
        requireHealthy()
        for(t in tasks()) {
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
            if (!status(t.issue,t.stage,t.url)) return "NEEDS_OWNER"
        }
        if(previous.any { it.state=="held" }) return "NEEDS_OWNER"
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
        }
        val i=task.issue
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
                require(progress.stage in setOf("coding","tests","review","done","held"))
                if(progress.stage=="done") require(progress.url?.matches(Regex("https://github.com/siyovushdev/devorch-kotlin-e2e-smoke/pull/[1-9][0-9]*"))==true)
                db.write { it.mutate("UPDATE personal_tasks SET state=?,stage=?,url=? WHERE issue_id=?",
                    if(progress.stage in setOf("done","held")) progress.stage else "active",progress.stage,progress.url,i.githubIssueId) }
            }
            val now=tasks().single { it.issue.githubIssueId==i.githubIssueId }
            status(i,now.stage,now.url)
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
            "done" -> "Готов черновик PR: $url . Слияние выполняет владелец."
            else -> {
                val reason=db.read { c -> c.rows("SELECT reason FROM personal_tasks WHERE issue_id=?",i.githubIssueId) { it.getString(1) }.singleOrNull() }
                when(reason) {
                    "OWNER_LABEL_OR_ISSUE_CHANGED" -> "Остановлено: задача или разрешение изменились. Владельцу нужно сверить согласованное ТЗ и решить, нужна ли новая задача."
                    "STATUS_OUTCOME_UNKNOWN" -> "Остановлено: не удалось подтвердить сообщение в GitHub. Владельцу нужно проверить Issue и сохранённый статус перед продолжением. Повторной отправки не будет."
                    "PREPARATION_INTERRUPTED" -> "Остановлено: подготовка прервана перезапуском. Владельцу нужно проверить сохранённую задачу и завершение старого процесса. Повторного запуска не будет."
                    else -> "Остановлено: результат последнего шага не подтверждён. Владельцу нужно проверить сохранённые тесты, ревью и GitHub PR перед продолжением. Автоматического повтора не будет."
                }
            }
        }
        // Intent BEFORE POST. Lost response or crash never permits another POST for this stage.
        db.write { it.mutate("INSERT INTO personal_status(issue_id,stage,state) VALUES (?,?,'sending')",i.githubIssueId,stage) }
        try {
            github.postStatus(i.issueNumber,"DevOrchestrator: $text")
        } catch(failure: Exception) {
            db.write { it.mutate("UPDATE personal_status SET state='uncertain' WHERE issue_id=? AND stage=?",i.githubIssueId,stage) }
            hold(i,"STATUS_OUTCOME_UNKNOWN")
            if (failure is GitHubUnavailable || failure is GitHubRejected) return false
            throw failure
        }
        db.write { check(it.mutate("UPDATE personal_status SET state='confirmed' WHERE issue_id=? AND stage=? AND state='sending'",i.githubIssueId,stage)==1) }
        return true
    }
    fun localStatus(): String = tasks().joinToString("\n") { "Issue #${it.issue.issueNumber}: ${it.state} / ${it.stage} ${it.reason.orEmpty()}${it.url?.let { u -> " $u" } ?: ""}" }.ifEmpty { "No tasks" }
}
