package dev.orchestrator.service

import java.nio.file.Path

/** Explicit CLI only. Normal bootstrap and CI never start the autonomous worker. */
internal fun serviceCommand(args: Array<String>) {
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
        service.recover()
        do {
            val state=try { service.tick() } catch(_: Exception) { "GITHUB_UNAVAILABLE_NO_ACTION" }
            println("PERSONAL: $state")
            if(args[0]=="run" || !permitted()) return
            // Short stages advance immediately; idle/holds/network failures wait for the next poll.
            if(state in setOf("IDLE","NEEDS_OWNER","GITHUB_UNAVAILABLE_NO_ACTION","OFF","DONE"))
                Thread.sleep(config.pollSeconds*1000)
        } while(!Thread.currentThread().isInterrupted)
    }
}

internal fun readLocalServiceStatus(database: Path): String {
    if(!java.nio.file.Files.exists(database)) return "No tasks"
    return java.sql.DriverManager.getConnection("jdbc:sqlite:file:$database?mode=ro").use { c ->
        c.createStatement().use { statement ->
            statement.executeQuery("SELECT number,state,stage,reason,url FROM personal_tasks ORDER BY number").use { r ->
                buildList { while(r.next()) add("Issue #${r.getInt(1)}: ${r.getString(2)} / ${r.getString(3)} " +
                    "${r.getString(4).orEmpty()} ${r.getString(5).orEmpty()}") }.joinToString("\n").ifEmpty { "No tasks" }
            }
        }
    }
}
