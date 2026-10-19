package dev.orchestrator.service

import java.nio.file.Path

/** Explicit CLI only. Normal bootstrap and CI never start the autonomous worker. */
internal class ServiceStopped(message: String) : IllegalStateException(message)

internal fun serviceCommand(args: Array<String>) {
    try { executeServiceCommand(args) }
    catch (failure: ServiceStopped) { throw failure }
    catch (failure: Exception) { throw ServiceStopped("SERVICE_STOPPED: ${safeServiceDiagnostic(failure)}; check local configuration/state") }
}

private fun executeServiceCommand(args: Array<String>) {

    require(args.size==2 && args[0] in setOf("service","run","status")) {
        "Usage: dev-orchestrator-kotlin service|run|status /absolute/personal.properties"
    }
    val file=Path.of(args[1]); require(file.isAbsolute)
    val config=PersonalConfig.read(file)
    if(args[0]=="status") {
        println(readLocalServiceStatus(config.root.resolve("service.db")))
        return
    }
    ServiceLock(config.root).use {
        if(!config.enabled) { println("PERSONAL autonomy OFF"); return }
        check(System.getProperty("user.name")=="devorch-e2e") { "Dedicated PERSONAL account required" }
        val token=requireNotNull(System.getenv("DEVORCH_PERSONAL_TOKEN")) { "PERSONAL scoped token required" }
        val github=PersonalGitHub(token)
        val permitted={ PersonalConfig.read(file)==config && config.enabled }
        val runtime=PersonalRuntime(config,token,{ issue -> permitted() && github.authorized(issue) })
        val service=PersonalService(config.root.resolve("service.db"),github,runtime,permitted)
        dev.orchestrator.agent.nativecli.LinuxIsolation.requireAvailable()
        guardedServiceCall(service) { service.recover(); "RECOVERED" }
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
        listOf(tasks,failures).filter { it.isNotBlank() }.joinToString("\n")
    }
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

internal fun safeServiceDiagnostic(failure: Throwable): String =
    generateSequence(failure) { it.cause }.take(8).joinToString(" <- ") { e ->
        (if (e is GitHubRejected) "GITHUB_REJECTED_HTTP_${e.status}" else e.javaClass.name) +
            e.stackTrace.filter { it.className.startsWith("dev.orchestrator.") }.take(4)
                .joinToString(prefix=" [",postfix="]") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    }
