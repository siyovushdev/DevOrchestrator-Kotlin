package dev.orchestrator.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.publish.*
import dev.orchestrator.storage.GitHubIssueRecord
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

class PersonalGitHubTest {
    private val mapper=ObjectMapper()
    private val issue=GitHubIssueRecord(1424152600,17,1017,Instant.EPOCH,"Task","body","a".repeat(40),true)
    private var author="siyovushdev"
    private var label=true
    private var body="body"
    private var events=listOf(event("labeled","siyovushdev"))
    private var sent=""
    private var wrongReadback=false
    private var endless=false
    private fun event(type: String,actor: String)=mapOf("event" to type,"label" to mapOf("name" to "devorch:run"),"actor" to mapOf("login" to actor))
    private fun client()=PersonalGitHub("FAKE",PublicationHttp { method,path,payload ->
        fun json(value: Any,status: Int=200)=PublicationResponse(status,mapper.writeValueAsString(value))
        when {
            method=="GET" && path.endsWith("/issues/17") -> json(mapOf("id" to 1017,"state" to "open","title" to "Task","body" to body,
                "user" to mapOf("login" to author),"labels" to if(label) listOf(mapOf("name" to "devorch:run")) else emptyList<Any>()))
            method=="GET" && path.contains("/events?") -> json(if(endless) List(100) { events.first() } else events)
            method=="POST" -> { sent=mapper.readTree(payload).path("body").asText(); json(mapOf("id" to 9,"body" to sent),201) }
            method=="GET" && path.endsWith("/comments/9") -> json(mapOf("id" to 9,"body" to if(wrongReadback) "wrong" else sent,
                "issue_url" to "https://api.github.com${SMOKE_TARGET.apiRoot}/issues/17"))
            else -> error("Unexpected request")
        }
    })
    @Test fun onlyCurrentOwnerIssuedLabelAndUnchangedIssueAreAuthorized() {
        assertTrue(client().authorized(issue))
        author="intruder"; assertFalse(client().authorized(issue)); author="siyovushdev"
        label=false; assertFalse(client().authorized(issue)); label=true
        body="changed"; assertFalse(client().authorized(issue)); body="body"
        events=events+event("unlabeled","siyovushdev"); assertFalse(client().authorized(issue))
        events=events+event("labeled","intruder"); assertFalse(client().authorized(issue))
        events=events+event("labeled","siyovushdev"); assertTrue(client().authorized(issue))
    }
    @Test fun incompleteEventHistoryNeverAuthorizes() { endless=true; assertFalse(client().authorized(issue)) }
    @Test fun statusRequiresRemoteReadback() {
        client().postStatus(17,"Working"); assertEquals("Working",sent)
        wrongReadback=true; assertFails { client().postStatus(17,"Working") }
    }
}
