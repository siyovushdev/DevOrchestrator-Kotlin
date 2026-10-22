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
        s.execute("""CREATE TRIGGER IF NOT EXISTS remote_request_immutable BEFORE UPDATE OF
            attempt_id,workflow_id,role,generation,request,digest ON remote_attempts
            BEGIN SELECT RAISE(ABORT,'immutable remote request'); END""")
        s.execute("""CREATE TRIGGER IF NOT EXISTS remote_result_immutable BEFORE UPDATE ON remote_attempts
            WHEN OLD.state='EVIDENCE_STORED' BEGIN SELECT RAISE(ABORT,'immutable remote result'); END""")
        s.execute("""CREATE TRIGGER IF NOT EXISTS remote_attempt_no_delete BEFORE DELETE ON remote_attempts
            BEGIN SELECT RAISE(ABORT,'immutable remote attempt'); END""")
    } } }
    data class Saved(val request: RemoteRequest,val state: String,val evidence: ByteArray?)
    fun find(id: String): Saved?=db.read { c -> c.rows("SELECT * FROM remote_attempts WHERE attempt_id=?",id) {
        val request=RemoteRequest(it.getBytes("request")); val identity=request.json["identity"]
        check(request.attemptId==id && request.sha256==it.getString("digest") &&
            identity["workflowId"].asText()==it.getString("workflow_id") && identity["role"].asText()==it.getString("role") &&
            identity["generation"].asInt()==it.getInt("generation")) { "REMOTE_SAVED_REQUEST_CORRUPTED" }
        Saved(request,it.getString("state"),it.getBytes("evidence"))
    }.singleOrNull() }
    /** False means NO transport call: same attempt/slot is never executed a second time. */
    fun claim(r: RemoteRequest,prepare: (java.sql.Connection) -> Unit = {}): Boolean=db.write { c ->
        if(!claim(c,r)) false else { prepare(c); true }
    }
    private fun claim(c: java.sql.Connection,r: RemoteRequest): Boolean {
        val i=r.json["identity"]
        val previous=c.rows("SELECT attempt_id,digest FROM remote_attempts WHERE attempt_id=? OR (workflow_id=? AND role=? AND generation=?)",
            r.attemptId,i["workflowId"].asText(),i["role"].asText(),i["generation"].asInt()) { it.getString(1) to it.getString(2) }
        previous.filter { it.first==r.attemptId }.forEach { check(it.second==r.sha256) { "REMOTE_ATTEMPT_IDENTITY_CHANGED" } }
        if(previous.isNotEmpty()) return false
        check(c.rows("SELECT attempt_id FROM remote_attempts WHERE state IN ('SENDING','UNKNOWN','RUNNING')") { it.getString(1) }.isEmpty()) { "REMOTE_UNKNOWN_REQUIRES_OWNER" }
        c.mutate("INSERT INTO remote_attempts VALUES(?,?,?,?,?,?,'SENDING',NULL)",r.attemptId,i["workflowId"].asText(),i["role"].asText(),i["generation"].asInt(),r.bytes,r.sha256)
        c.mutate("INSERT INTO remote_attempt_events(attempt_id,at,state) VALUES(?,?,'SENDING')",r.attemptId,Instant.now().toString())
        return true
    }
    fun record(id: String,state: String,evidence: ByteArray?=null,diagnostic: String?=null) = db.write { c ->
        require(state in setOf("UNKNOWN","RUNNING","EVIDENCE_STORED","TIMEOUT","CANCELLED"))
        val current=c.rows("SELECT state,evidence FROM remote_attempts WHERE attempt_id=?",id) { it.getString(1) to it.getBytes(2) }.single()
        if(current.first=="EVIDENCE_STORED") {
            check(state==current.first && current.second.contentEquals(evidence)) { "REMOTE_IMMUTABLE_RESULT_CHANGED" }
            return@write
        }
        if(current.first=="CANCELLED") {
            check(state=="CANCELLED") { "REMOTE_IMMUTABLE_RESULT_CHANGED" }
            return@write
        }
        c.mutate("UPDATE remote_attempts SET state=?,evidence=? WHERE attempt_id=?",state,evidence,id)
        c.mutate("INSERT INTO remote_attempt_events(attempt_id,at,state,evidence_digest,diagnostic) VALUES(?,?,?,?,?)",id,Instant.now().toString(),state,evidence?.let(RemoteProtocol::digest),diagnostic)
    }
}

/** Transport facts remain separate from workflow completion. */
internal class RemoteWorkerClient(private val journal: RemoteAttemptJournal,private val transport: WorkerTransport,
                                 private val evidence: RemoteEvidence) {
    fun submit(request: RemoteRequest,permitted: () -> Boolean): String = submitPrepared(request,permitted) {}
    /** The callback pins the existing workflow in the SAME transaction as the transport claim. */
    fun submitPrepared(request: RemoteRequest,permitted: () -> Boolean,prepare: (java.sql.Connection) -> Unit): String {
        check(permitted()) { "OWNER_PERMISSION_REQUIRED" }
        if(!journal.claim(request,prepare)) return journal.find(request.attemptId)?.state ?: "DUPLICATE_SLOT"
        return executeFreshClaim(request,permitted)
    }
    /** Private: a consumed/reloaded intent can never call the submit exchange again. */
    private fun executeFreshClaim(request: RemoteRequest,permitted: () -> Boolean): String {
        check(permitted()) { "OWNER_PERMISSION_REQUIRED" }
        val wire=RemoteProtocol.mapper.writeValueAsBytes(mapOf("op" to "submit","requestBase64" to Base64.getEncoder().encodeToString(request.bytes)))
        // Any exception after durable intent leaves UNKNOWN; never infer that no remote exec occurred.
        return exchange(request,wire,request.json["timeoutMs"].asLong()+15000)
    }
    /** Ask the guest to stop. The response is not evidence and is never imported. */
    fun cancelGuest(request: RemoteRequest) {
        val wire=RemoteProtocol.mapper.writeValueAsBytes(mapOf("op" to "cancel","attemptId" to request.attemptId,"requestSha256" to request.sha256))
        try { transport.exchange(wire, 15_000) } catch (_: Exception) { }
    }
    fun reconcile(id: String): String {
        val saved=requireNotNull(journal.find(id))
        if(saved.state=="EVIDENCE_STORED" || saved.state=="CANCELLED") return saved.state
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
                "TIMEOUT" -> "TIMEOUT"
                "CANCELLED" -> "CANCELLED" // owner cancel and client death; not an unknown hold
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
