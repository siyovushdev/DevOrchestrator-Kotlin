package dev.orchestrator.remote

import dev.orchestrator.storage.RuntimeDatabase
import dev.orchestrator.storage.mutate
import dev.orchestrator.storage.rows
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

/** Transport facts ONLY. Does not complete workflow phases, release claims or grant publication. */
internal class RemoteAttemptJournal(file: Path) {
    private val db=RuntimeDatabase(file)
    init { db.write { c -> c.createStatement().use { s ->
        s.execute("""CREATE TABLE IF NOT EXISTS remote_attempts(
            attempt_id TEXT PRIMARY KEY, workflow_id TEXT NOT NULL, role TEXT NOT NULL, generation INTEGER NOT NULL,
            request BLOB NOT NULL, digest TEXT NOT NULL, state TEXT NOT NULL, evidence BLOB,
            UNIQUE(workflow_id,role,generation))""")
        s.execute("""CREATE TABLE IF NOT EXISTS remote_attempt_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT, attempt_id TEXT NOT NULL REFERENCES remote_attempts(attempt_id),
            at TEXT NOT NULL, state TEXT NOT NULL, evidence_digest TEXT, diagnostic TEXT)""")
    } } }
    data class Saved(val request: RemoteRequest,val state: String,val evidence: ByteArray?)
    fun find(id: String): Saved?=db.read { c -> c.rows("SELECT request,state,evidence FROM remote_attempts WHERE attempt_id=?",id) {
        Saved(RemoteRequest(it.getBytes(1)),it.getString(2),it.getBytes(3))
    }.singleOrNull() }
    /** False means NO transport call: same attempt/slot is never executed a second time. */
    fun claim(r: RemoteRequest): Boolean=db.write { c ->
        val i=r.json["identity"]
        val previous=c.rows("SELECT attempt_id,digest FROM remote_attempts WHERE attempt_id=? OR (workflow_id=? AND role=? AND generation=?)",
            r.attemptId,i["workflowId"].asText(),i["role"].asText(),i["generation"].asInt()) { it.getString(1) to it.getString(2) }
        previous.filter { it.first==r.attemptId }.forEach { check(it.second==r.sha256) { "REMOTE_ATTEMPT_IDENTITY_CHANGED" } }
        if(previous.isNotEmpty()) return@write false
        check(c.rows("SELECT attempt_id FROM remote_attempts WHERE state IN ('SENDING','UNKNOWN','RUNNING')") { it.getString(1) }.isEmpty()) { "REMOTE_UNKNOWN_REQUIRES_OWNER" }
        c.mutate("INSERT INTO remote_attempts VALUES(?,?,?,?,?,?,'SENDING',NULL)",r.attemptId,i["workflowId"].asText(),i["role"].asText(),i["generation"].asInt(),r.bytes,r.sha256)
        c.mutate("INSERT INTO remote_attempt_events(attempt_id,at,state) VALUES(?,?,'SENDING')",r.attemptId,Instant.now().toString())
        true
    }
    fun record(id: String,state: String,evidence: ByteArray?=null,diagnostic: String?=null) = db.write { c ->
        require(state in setOf("UNKNOWN","RUNNING","EVIDENCE_STORED"))
        val current=c.rows("SELECT state,evidence FROM remote_attempts WHERE attempt_id=?",id) { it.getString(1) to it.getBytes(2) }.single()
        if(current.first=="EVIDENCE_STORED") {
            check(state==current.first && current.second.contentEquals(evidence)) { "REMOTE_IMMUTABLE_RESULT_CHANGED" }
            return@write
        }
        c.mutate("UPDATE remote_attempts SET state=?,evidence=? WHERE attempt_id=?",state,evidence,id)
        c.mutate("INSERT INTO remote_attempt_events(attempt_id,at,state,evidence_digest,diagnostic) VALUES(?,?,?,?,?)",id,Instant.now().toString(),state,evidence?.let(RemoteProtocol::digest),diagnostic)
    }
}

/** Not called by PERSONAL service yet: the atomic workflow/artifact bridge is a separate stage. */
internal class RemoteWorkerClient(private val journal: RemoteAttemptJournal,private val transport: WorkerTransport,
                                 private val evidence: RemoteEvidence) {
    fun submit(request: RemoteRequest,permitted: () -> Boolean): String {
        check(permitted()) { "OWNER_PERMISSION_REQUIRED" }
        if(!journal.claim(request)) return journal.find(request.attemptId)?.state ?: "DUPLICATE_SLOT"
        val wire=RemoteProtocol.mapper.writeValueAsBytes(mapOf("op" to "submit","requestBase64" to Base64.getEncoder().encodeToString(request.bytes)))
        // Any exception after durable intent leaves UNKNOWN; never infer that no remote exec occurred.
        return exchange(request,wire,request.json["timeoutMs"].asLong()+15000)
    }
    fun reconcile(id: String): String {
        val saved=requireNotNull(journal.find(id))
        if(saved.state=="EVIDENCE_STORED") return saved.state
        val wire=RemoteProtocol.mapper.writeValueAsBytes(mapOf("op" to "query","attemptId" to id,"requestSha256" to saved.request.sha256))
        return exchange(saved.request,wire,15000)
    }
    private fun exchange(request: RemoteRequest,wire: ByteArray,timeout: Long): String {
        val bytes: ByteArray
        val state: String
        try {
            bytes=transport.exchange(wire,timeout)
            check(bytes.size<=RemoteProtocol.LIMIT)
            val result=RemoteProtocol.mapper.readTree(bytes)
            state=when(result.path("state").asText()) {
                "FINISHED" -> { evidence.check(request,bytes); "EVIDENCE_STORED" }
                "RUNNING" -> "RUNNING"
                else -> "UNKNOWN" // NOT_FOUND/DENIED is not permission to resubmit.
            }
        } catch(e: Exception) {
            val diagnostic=e.javaClass.name+e.stackTrace.filter { it.className.startsWith("dev.orchestrator.") }.take(4)
                .joinToString(prefix=" [",postfix="]") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            // A failed SQLite write propagates as internal failure, never a transport diagnosis.
            journal.record(request.attemptId,"UNKNOWN",diagnostic=diagnostic)
            throw RemoteExchangeUnknown(diagnostic)
        }
        journal.record(request.attemptId,state,bytes)
        return state
    }
}
internal class RemoteExchangeUnknown(val diagnosticType: String): IllegalStateException("REMOTE_OUTCOME_UNKNOWN_NEEDS_OWNER")
