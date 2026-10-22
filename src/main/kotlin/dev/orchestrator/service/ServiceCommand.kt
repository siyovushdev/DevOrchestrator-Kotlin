package dev.orchestrator.service

import dev.orchestrator.agent.nativecli.LocalProcessControl
import java.nio.file.Files
import java.nio.file.Path

/** Explicit CLI only. Normal bootstrap and CI never start the autonomous worker. */
internal class ServiceStopped(message: String) : IllegalStateException(message)

internal fun serviceCommand(args: Array<String>) {
    try { executeServiceCommand(args) }
    catch (failure: ServiceStopped) { throw failure }
    catch (failure: Exception) { throw ServiceStopped("SERVICE_STOPPED: ${safeServiceDiagnostic(failure)}; check local configuration/state") }
}

private fun executeServiceCommand(args: Array<String>) {
    val command=args.firstOrNull()
    require((command in setOf("service","run","status","doctor") && args.size==2) ||
        (command in setOf("cancel","release") && args.size==3) || (command=="limits" && args.size==6) ||
        (command in setOf("check-status","check-remote") && args.size==4) || (command in setOf("resume-status","resume-remote") && args.size==5)) {
        "Usage: service|run|status|doctor CONFIG; cancel|release CONFIG ISSUE; limits CONFIG default|ISSUE MAX CODING_SECONDS REVIEW_SECONDS; check-status CONFIG ISSUE STAGE; resume-status CONFIG ISSUE STAGE COMMENT_ID; check-remote CONFIG ISSUE ATTEMPT; resume-remote CONFIG ISSUE ATTEMPT EVIDENCE_SHA256"
    }
    val file=Path.of(args[1]); require(file.isAbsolute)
    val config=PersonalConfig.read(file)
    if (command=="doctor") { println(diagnose(config)); return }
    if (command=="limits") {
        val scope=if (args[2]=="default") "default" else "issue:${args[2].toInt().also { require(it>0) }}"
        println(writeExecutionLimits(config.root.resolve("service.db"), scope,
            ExecutionLimits(args[3].toInt(), args[4].toInt(), args[5].toInt())))
        return
    }
    if (command=="cancel") {
        val number=args[2].toInt(); require(number>0)
        println(requestOwnerCancel(config.root.resolve("service.db"), config.root, number))
        stopInFlight(config)
        println("Отмена записана. Локальный сирота и отдельный SSH-гость получают сигнал остановки. Результат гостя после отмены не импортируется. Повтора публикации нет.")
        return
    }
    if (command=="release") {
        val number=args[2].toInt(); require(number>0)
        println(PersonalService(config.root.resolve("service.db"), idleGitHub(), idleRuntime(), { true }).releaseHeld(number))
        return
    }
    if(args[0]=="status") {
        println(readLocalServiceStatus(config.root.resolve("service.db")))
        return
    }
    if (command=="check-status") {
        val token=requireNotNull(System.getenv("DEVORCH_PERSONAL_TOKEN")) { "PERSONAL scoped token required" }
        val result=checkSavedStatus(config.root.resolve("service.db"),PersonalGitHub(token),args[2].toInt(),args[3])
        println(result?.let { "CONFIRMED_REMOTE: ${it.id} ${it.url}; use resume-status explicitly" } ?: "NEEDS_OWNER: exact unique comment not confirmed")
        return
    }
    ServiceLock(config.root).use {
        if(!config.enabled && command !in setOf("resume-status","check-remote","resume-remote")) { println("PERSONAL autonomy OFF"); return }
        check(System.getProperty("user.name")=="devorch-e2e") { "Dedicated PERSONAL account required" }
        val token=requireNotNull(System.getenv("DEVORCH_PERSONAL_TOKEN")) { "PERSONAL scoped token required" }
        val github=PersonalGitHub(token)
        val permitted={ PersonalConfig.read(file)==config && config.enabled }
        val runtime=PersonalRuntime(config,token,{ issue -> permitted() && github.authorized(issue) })
        val service=PersonalService(config.root.resolve("service.db"),github,runtime,permitted)
        if (command=="resume-status") {
            guardedServiceCall(service) { service.recover(); "RECOVERED" }
            println(if (service.resumeStatus(args[2].toInt(),args[3],args[4].toLong())) "STATUS_RECONCILED: start service separately" else "NEEDS_OWNER: not confirmed")
            return
        }
        if(command=="check-remote") {
            println(service.checkRemote(args[2].toInt(),args[3])); return
        }
        if(command=="resume-remote") {
            println(if(service.resumeRemote(args[2].toInt(),args[3],args[4])) "REMOTE_RESULT_ACCEPTED: start service separately" else "NEEDS_OWNER: exact result not accepted")
            return
        }
        if(config.runtimeMode=="local") dev.orchestrator.agent.nativecli.LinuxIsolation.requireAvailable()
        guardedServiceCall(service) { service.recover(); "RECOVERED" }
        Runtime.getRuntime().addShutdownHook(Thread { try { stopInFlight(config) } catch (_: Exception) {} })
        do {
            val state=guardedServiceCall(service) { service.tick() }
            println("PERSONAL: $state")
            if(args[0]=="run" || !permitted()) return
            // Short stages advance immediately; idle/holds/network failures wait for the next poll.
            if(state.startsWith("GITHUB_UNAVAILABLE_NO_ACTION") || state in setOf("IDLE","NEEDS_OWNER","GITHUB_UNAVAILABLE_NO_ACTION","OFF","DONE"))
                Thread.sleep(config.pollSeconds*1000)
        } while(!Thread.currentThread().isInterrupted)
    }
}

internal fun readLocalServiceStatus(database: Path): String {
    if(!java.nio.file.Files.exists(database)) return "No tasks"
    return java.sql.DriverManager.getConnection("jdbc:sqlite:file:$database?mode=ro").use { c ->
        fun table(name: String)=c.createStatement().use { statement ->
            statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='$name'").use { it.next() }
        }
        if (!table("personal_tasks")) return describeOwnerControls(database).ifBlank { "No tasks" }
        val failures=c.createStatement().use { statement ->
            val exists=statement.executeQuery("SELECT 1 FROM sqlite_master WHERE name='personal_failures'").use { it.next() }
            if (exists) statement.executeQuery("SELECT diagnostic FROM personal_failures ORDER BY id").use { r ->
                buildList { while(r.next()) add("SERVICE_STOPPED_REQUIRES_OWNER: ${r.getString(1)}") }.joinToString("\n")
            } else ""
        }
        val tasks=c.createStatement().use { statement ->
            statement.executeQuery("SELECT number,state,stage,reason,url FROM personal_tasks ORDER BY number").use { r ->
                buildList { while(r.next()) add("Issue #${r.getInt(1)}: ${r.getString(2)} / ${r.getString(3)} " +
                    "${r.getString(4).orEmpty()} ${r.getString(5).orEmpty()}") }.joinToString("\n").ifEmpty { "No tasks" }
            }
        }
        listOf(tasks,failures,describeOwnerControls(database)).filter { it.isNotBlank() }.joinToString("\n")
    }
}

internal fun stopInFlight(config: PersonalConfig) {
    if (!Files.isDirectory(config.root)) return
    val tasks=Files.list(config.root).use { stream ->
        stream.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("task-") }.toList()
    }
    for (dir in tasks) {
        val database=dir.resolve("run.db")
        LocalProcessControl.stopOrphans(database)
        if (config.ssh!=null) LocalProcessControl.cancelGuests(database, config.ssh)
    }
}

private fun idleGitHub(): ServiceGitHub = object: ServiceGitHub {
    override fun issues()=emptyList<dev.orchestrator.storage.GitHubIssueRecord>()
    override fun authorized(issue: dev.orchestrator.storage.GitHubIssueRecord)=false
    override fun postStatus(number: Int, body: String)=error("RELEASE_DOES_NOT_POST")
}
private fun idleRuntime(): TaskRuntime = object: TaskRuntime {
    override fun prepare(issue: dev.orchestrator.storage.GitHubIssueRecord)=error("RELEASE_DOES_NOT_RUN")
    override fun recover(issue: dev.orchestrator.storage.GitHubIssueRecord) {}
    override fun step(issue: dev.orchestrator.storage.GitHubIssueRecord)=error("RELEASE_DOES_NOT_RUN")
}

internal fun diagnose(config: PersonalConfig): String {
    val db=config.root.resolve("service.db")
    val isolation=try { dev.orchestrator.agent.nativecli.LinuxIsolation.requireAvailable(); "available" }
        catch (_: Exception) { "unavailable" }
    val codex=if (java.nio.file.Files.isExecutable(config.codex)) "executable" else "missing"
    val tasks=if (java.nio.file.Files.isRegularFile(db)) readLocalServiceStatus(db) else "No service.db"
    return """enabled=${config.enabled}
runtime=${config.runtimeMode}
repository=siyovushdev/devorch-kotlin-e2e-smoke id=1424152600
isolation=$isolation
codex=$codex
lock=${java.nio.file.Files.exists(config.root.resolve("service.lock"))}
$tasks"""
}

/** Only explicit GitHub GET availability errors keep polling. Everything else terminates. */
internal fun guardedServiceCall(service: PersonalService, action: () -> String): String = try {
    action()
} catch (failure: ServiceStopped) {
    throw failure
} catch (failure: GitHubUnavailable) {
    "GITHUB_UNAVAILABLE_NO_ACTION: ${failure.code}"
} catch (failure: Exception) {
    val diagnostic = try { service.recordFailure(failure) }
        catch (storage: Exception) { "${failure.javaClass.name}; DIAGNOSTIC_STORAGE_FAILED:${storage.javaClass.name}" }
    val rejection=generateSequence<Throwable>(failure) { it.cause }.take(8).filterIsInstance<GitHubRejected>().firstOrNull()
    throw ServiceStopped(if (rejection!=null) "GITHUB_ACCESS_STOPPED_HTTP_${rejection.status}: $diagnostic" else "INTERNAL_ERROR_STOPPED: $diagnostic")
}

internal fun checkSavedStatus(database: Path, github: ServiceGitHub, number: Int, stage: String): StatusEvidence? =
    java.sql.DriverManager.getConnection("jdbc:sqlite:file:$database?mode=ro").use { c ->
        val hasIntent=c.createStatement().use { st -> st.executeQuery("PRAGMA table_info(personal_status)").use { rows ->
            var found=false
            while(rows.next()) if (rows.getString("name")=="body") found=true
            found
        } }
        if (!hasIntent) return@use null // Old intents cannot be reconstructed reliably.
        c.prepareStatement("""SELECT t.source,s.body FROM personal_tasks t JOIN personal_status s ON t.issue_id=s.issue_id
            WHERE t.number=? AND s.stage=? AND
            ((t.state='held' AND t.reason='STATUS_OUTCOME_UNKNOWN' AND s.state='uncertain') OR
             (s.state='sending' AND t.state IN ('accepted','active','done')))
            AND NOT EXISTS(SELECT 1 FROM personal_failures)""").use { query ->
            query.setInt(1,number); query.setString(2,stage)
            query.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                val body=rows.getString(2) ?: return@use null
                val n=com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getString(1))
                val issue=dev.orchestrator.storage.GitHubIssueRecord(n["repositoryId"].asLong(),n["issueNumber"].asInt(),
                    n["githubIssueId"].asLong(),java.time.Instant.parse(n["sourceUpdatedAt"].asText()),
                    n["title"].asText(),n["body"].asText(),n["baseSha"].asText(),true)
                github.findStatus(issue,body)
            }
        }
    }

internal fun safeServiceDiagnostic(failure: Throwable): String =
    generateSequence(failure) { it.cause }.take(8).joinToString(" <- ") { e ->
        (if (e is GitHubRejected) "GITHUB_REJECTED_HTTP_${e.status}" else e.javaClass.name) +
            e.stackTrace.filter { it.className.startsWith("dev.orchestrator.") }.take(4)
                .joinToString(prefix=" [",postfix="]") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    }
