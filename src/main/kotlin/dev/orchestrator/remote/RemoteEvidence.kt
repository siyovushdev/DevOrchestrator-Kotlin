package dev.orchestrator.remote

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.Comparator

/** Validates transport evidence in a NEW scratch image. Never imports into a workflow checkout.
 * EVIDENCE_STORED is deliberately not Tests PASS / Review PASS / a Publisher grant.
 */
internal class RemoteEvidence(private val scratchRoot: Path) {
    init {
        require(scratchRoot.toRealPath()==scratchRoot && Files.isDirectory(scratchRoot))
        require(Files.getPosixFilePermissions(scratchRoot).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") })
    }
    fun check(request: RemoteRequest, bytes: ByteArray) {
        require(bytes.size<=RemoteProtocol.LIMIT)
        val n=RemoteProtocol.mapper.readTree(bytes); val r=request.json; val i=r["identity"]
        check(n.isObject && n.fieldNames().asSequence().toSet()==setOf("version","state","requestSha256","workerSha256","identity","started","receipt","outputTree","files","artifacts","nativePolicyReceipt","codexPath"))
        check(n["version"].isInt && n["version"].asInt()==1 && n["state"].asText()=="FINISHED" && n["requestSha256"].asText()==request.sha256 &&
            n["workerSha256"]==r["workerSha256"] && n["identity"]==i)
        val raw=n["receipt"]; val birth=n["started"]
        check(raw["cleanup_echild"]?.isBoolean==true && raw["cleanup_echild"].asBoolean() && raw["cause"]?.asText()=="exited" && raw["exit_code"]?.isInt==true)
        for(key in listOf("boot_id","machine_id")) {
            check(raw[key]?.isTextual==true && raw[key].asText().isNotBlank() && raw[key]==birth[key])
        }
        fun process(p: JsonNode?) { check(p?.isObject==true && p["pid"]?.isIntegralNumber==true && p["pid"].asLong()>0 && p["start_ticks"]?.isTextual==true && p["start_ticks"].asText().matches(Regex("[0-9]+"))) }
        process(raw["supervisor"]); process(raw["child"])
        check(raw["supervisor"]==birth["supervisor"] && raw["child"]["pid"]!=raw["supervisor"]["pid"])
        check(raw["reaped"]?.isIntegralNumber==true && raw["reaped"].asLong()>=1)
        if(i["role"].asText()!="TESTS") {
            check(n.has("codexBinary")==false) { "GUEST_BINARY_ATTESTATION" }
            check(n["codexPath"]?.asText()?.matches(Regex("/[A-Za-z0-9._+/-]{1,400}"))==true) { "CODEX_PATH" }
            val policy=n["nativePolicyReceipt"]
            check(policy?.isObject==true && policy["cleanup_echild"]?.asBoolean()==true && policy["exit_code"]?.isInt==true && policy["exit_code"].asInt()==0 && policy["cause"]?.asText()=="exited")
            for(key in listOf("boot_id","machine_id","supervisor")) check(policy[key]==birth[key])
            process(policy["child"])
        } else {
            check(n["nativePolicyReceipt"]?.isNull==true)
            check(n["codexPath"]?.isNull==true)
            check(n.has("codexBinary")==false)
        }
        val scratch=Files.createTempDirectory(scratchRoot,"receipt-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        try {
            val image=scratch.resolve("image"); RemoteProtocol.materialize(n["files"],image)
            val hash=Files.createDirectory(scratch.resolve("hash"))
            val tree=RemoteProtocol.tree(image,hash)
            check(tree==n["outputTree"].asText())
            if(i["role"].asText()!="CODING") check(tree==i["sourceTree"].asText())
            val artifacts=n["artifacts"]
            check(artifacts.isObject && artifacts.fieldNames().asSequence().all { it in setOf("events.log","stderr.log","last-message.txt","tests.json","tests.test-ids.json") })
            val decoded=artifacts.fields().asSequence().associate { (name,value) ->
                check(value.isObject && value.fieldNames().asSequence().toSet()==setOf("data","sha256") && value["data"].isTextual && value["sha256"].isTextual)
                val content=Base64.getDecoder().decode(value["data"].asText())
                check(content.size<=1_048_576 && RemoteProtocol.digest(content)==value["sha256"].asText())
                name to content
            }
            check("events.log" in decoded && "stderr.log" in decoded)
            if(raw["exit_code"].asInt()==0) {
                if(i["role"].asText()=="TESTS") {
                    val report=RemoteProtocol.mapper.readTree(requireNotNull(decoded["tests.json"]))
                    val ids=requireNotNull(decoded["tests.test-ids.json"])
                    check(report["discovery_completed"]?.asBoolean()==true && report["test_ids_sha256"]?.asText()==RemoteProtocol.digest(ids))
                    check(report["tests_run"]?.isInt==true && report["skipped"]?.isInt==true && report["skipped"].asInt() in 0..report["tests_run"].asInt() &&
                        report["tests_run"].asInt()-report["skipped"].asInt()>=r["minimumTests"].asInt() && report["completed_invocations"]==report["tests_run"])
                    for(key in listOf("failures","errors","unexpected_successes")) check(report[key]?.isInt==true && report[key].asInt()==0)
                } else {
                    val events=requireNotNull(decoded["events.log"]).toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map(RemoteProtocol.mapper::readTree).toList()
                    check(events.count { it.path("type").asText()=="turn.completed" }==1 && events.none { it.path("type").asText() in setOf("turn.failed","error") })
                    val session=events.single { it.path("type").asText()=="thread.started" }.path("thread_id").asText()
                    check(RemoteProtocol.id(session) && (i["role"].asText()!="REVIEW" || session!=i["codingSessionId"].asText()))
                    check(requireNotNull(decoded["last-message.txt"]).isNotEmpty())
                    // Binding of Review verdict to FrozenCandidate is still the future atomic bridge.
                }
            }
        } finally {
            Files.walk(scratch).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
