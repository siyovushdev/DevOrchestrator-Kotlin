package dev.orchestrator.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.publish.*
import dev.orchestrator.storage.GitHubIssueRecord
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

class PersonalReconciliationTest {
    private val mapper=ObjectMapper()
    private val root=SMOKE_TARGET.apiRoot
    private val issue=GitHubIssueRecord(1424152600,17,1017,Instant.EPOCH,"Task","body","a".repeat(40),true)
    private val body="DevOrchestrator: status\n<!-- devorch-status:unique-intent -->"
    private var ids=listOf(9L)
    private var author="siyovushdev"
    private var changed=false
    private var full=false
    private val calls=mutableListOf<String>()
    private fun client()=PersonalGitHub("FAKE",PublicationHttp { method,path,_ ->
        calls += "$method $path"
        assertEquals("GET",method)
        val value: Any=when {
            path.endsWith("/issues/17") -> mapOf("id" to 1017)
            path.contains("comments?") -> if(full) (1..100).map { mapOf("id" to (path.substringAfterLast("page=").toInt()*100+it),"body" to "other") }
                else ids.map { mapOf("id" to it,"body" to body) }
            path.endsWith("/comments/9") -> mapOf("id" to 9,"body" to if(changed) "changed" else body,
                "user" to mapOf("login" to author),"issue_url" to "https://api.github.com$root/issues/17",
                "html_url" to "https://github.com/${SMOKE_TARGET.fullName}/issues/17#issuecomment-9")
            else -> error("Unexpected GET")
        }
        PublicationResponse(200,mapper.writeValueAsString(value))
    })
    @Test fun exactUniqueCommentRequiresReadbackAndOwnerAuthor() {
        assertEquals(9,client().findStatus(issue,body)?.id)
        assertEquals(3,calls.size)
        author="intruder"; assertNull(client().findStatus(issue,body)); author="siyovushdev"
        changed=true; assertNull(client().findStatus(issue,body))
    }
    @Test fun absentDuplicateAndIncompleteHistoryNeverConfirm() {
        ids=emptyList(); assertNull(client().findStatus(issue,body))
        ids=listOf(9,10); assertNull(client().findStatus(issue,body))
        ids=listOf(9,9); assertFails { client().findStatus(issue,body) }
        full=true; assertNull(client().findStatus(issue,body)) // 1000 entries do not prove a complete history
    }
    @Test fun transientHttpAndInternalTransportDefectRemainDistinct() {
        val unavailable=PersonalGitHub("FAKE",PublicationHttp { _,_,_ -> PublicationResponse(503,"untrusted error body") })
        assertEquals("GITHUB_HTTP_503",assertFailsWith<GitHubUnavailable> { unavailable.authorized(issue) }.code)
        val broken=PersonalGitHub("FAKE",PublicationHttp { _,_,_ -> error("internal defect") })
        assertFailsWith<IllegalStateException> { broken.authorized(issue) }
        val forbidden=PersonalGitHub("FAKE",PublicationHttp { _,_,_ -> PublicationResponse(403,"untrusted error body") })
        assertEquals(403,assertFailsWith<GitHubRejected> { forbidden.authorized(issue) }.status)
    }
}
