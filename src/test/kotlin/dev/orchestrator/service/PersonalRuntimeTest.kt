package dev.orchestrator.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.agent.nativecli.*
import dev.orchestrator.publish.*
import dev.orchestrator.storage.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Production service + production runtime/engine/publisher. Only remote GitHub/model replaced. */
class PersonalRuntimeTest {
    @TempDir lateinit var temp: Path
    private val mapper=ObjectMapper()
    private val root get()=temp.resolve("state")
    private val bare get()=temp.resolve("remote.git")
    private val binary get()=temp.resolve("fake-codex")
    private val home get()=temp.resolve("agent-home")
    private val remotePrs=mutableMapOf<String,Map<String,Any?>>()
    private var posts=0
    private var sends=0
    private var losePr=false
    private var correctOnce=false
    private lateinit var base: String
    private fun git(cwd: Path,vararg args: String): String {
        val b=ProcessBuilder(listOf("/usr/bin/git","-c","core.hooksPath=/dev/null")+args).directory(cwd.toFile()).redirectErrorStream(true)
        b.environment().clear(); b.environment().putAll(mapOf("PATH" to "/usr/bin:/bin","HOME" to temp.toString(),
            "GIT_CONFIG_NOSYSTEM" to "1","GIT_CONFIG_GLOBAL" to "/dev/null"))
        val p=b.start(); p.outputStream.close(); val out=p.inputStream.readNBytes(100000).toString(Charsets.UTF_8)
        check(p.waitFor(10,TimeUnit.SECONDS) && p.exitValue()==0) { out }; return out.trim()
    }
    private fun setup() {
        listOf(root,home,home.resolve(".codex"),temp.resolve("template"),temp.resolve("seed")).forEach { Files.createDirectory(it) }
        val seed=temp.resolve("seed")
        Files.writeString(seed.resolve("README"),"Base\n")
        git(seed,"init","-q","--initial-branch=main","--template=${temp.resolve("template")}")
        git(seed,"add","README"); git(seed,"-c","user.name=Test","-c","user.email=test@example.invalid","commit","-qm","base")
        base=git(seed,"rev-parse","HEAD")
        git(temp,"init","--bare","--template=${temp.resolve("template")}",bare.toString())
        git(seed,"push",bare.toString(),"HEAD:refs/heads/main")
        Files.writeString(binary,"""#!/usr/bin/python3
import json, os, sys
from pathlib import Path
if sys.argv[1:] == ['--version']:
    print('fake-codex test'); sys.exit(0)
if sys.argv[1:] == ['exec','--help']:
    print('--sandbox --model --json --output-last-message --config'); sys.exit(0)
assert sys.argv[1]=='exec'
assert not any(k in os.environ for k in ['DEVORCH_PERSONAL_TOKEN','DEVORCH_FETCH_TOKEN','DEVORCH_PUBLISH_TOKEN','GH_TOKEN','GITHUB_TOKEN','SSH_AUTH_SOCK'])
prompt=sys.stdin.read()
last=Path(sys.argv[sys.argv.index('--output-last-message')+1])
mode=sys.argv[sys.argv.index('--sandbox')+1]
state=Path(os.environ['CODEX_HOME'])
count=state / ('coding-count' if mode=='workspace-write' else 'review-count')
n=int(count.read_text())+1 if count.exists() else 0
count.write_text(str(n))
if mode=='workspace-write':
    Path('answer.py').write_text('def add(a,b): return a+b\n# attempt '+str(n)+'\n')
    Path('tests').mkdir(exist_ok=True)
    Path('tests/test_answer.py').write_text('import unittest\nfrom answer import add\nclass TestSum(unittest.TestCase):\n def test_add(self): self.assertEqual(3,add(1,2))\n')
    session='coding-'+str(n)
    last.write_text('Done')
else:
    assert mode=='read-only'
    result=json.loads(prompt.split('Return only JSON: ')[1].split('\n')[0])
    result['verdict']='CHANGES_REQUIRED' if ${if(correctOnce) "True" else "False"} and n==0 else 'PASS'
    result['findings']=['Add a precise boundary assertion'] if result['verdict']=='CHANGES_REQUIRED' else []
    last.write_text(json.dumps(result)); session='review-'+str(n)
print(json.dumps({'type':'thread.started','thread_id':session}))
print(json.dumps({'type':'turn.completed','usage':{}}))
""")
        check(binary.toFile().setExecutable(true))
    }
    private fun issue(number: Int)=GitHubIssueRecord(1424152600,number,1000L+number,Instant.EPOCH,"Sum $number","Add tiny sum",base,true)
    private val http=PublicationHttp { method,path,body ->
        val api=SMOKE_TARGET.apiRoot
        val decoded=URLDecoder.decode(path,Charsets.UTF_8)
        fun json(value: Any,status: Int=200)=PublicationResponse(status,mapper.writeValueAsString(value))
        when {
            path==api -> json(mapOf("id" to 1424152600,"full_name" to SMOKE_TARGET.fullName,"default_branch" to "main","archived" to false))
            decoded.startsWith("$api/git/ref/heads/") -> {
                val branch=decoded.substringAfter("$api/git/ref/heads/")
                val sha=try { git(temp,"--git-dir=$bare","rev-parse","--verify","refs/heads/$branch") } catch(_: Exception) { null }
                if(sha==null) json(emptyMap<String,String>(),404) else json(mapOf("ref" to "refs/heads/$branch","object" to mapOf("type" to "commit","sha" to sha)))
            }
            path.startsWith("$api/git/commits/") -> {
                val sha=path.substringAfterLast('/'); val info=git(temp,"--git-dir=$bare","show","-s","--format=%T %P",sha).split(' ')
                json(mapOf("sha" to sha,"tree" to mapOf("sha" to info[0]),"parents" to info.drop(1).map { mapOf("sha" to it) }))
            }
            path.startsWith("$api/pulls?") -> {
                val branch=decoded.substringAfter("head=siyovushdev:").substringBefore('&')
                json(listOfNotNull(remotePrs[branch]))
            }
            method=="POST" && path=="$api/pulls" -> {
                posts++; val n=mapper.readTree(body); val branch=n.path("head").asText()
                val sha=git(temp,"--git-dir=$bare","rev-parse","refs/heads/$branch")
                val repo=mapOf("id" to 1424152600,"full_name" to SMOKE_TARGET.fullName)
                val pr=mapOf("number" to posts,"html_url" to "https://github.com/${SMOKE_TARGET.fullName}/pull/$posts",
                    "state" to "open","merged_at" to null,"draft" to true,
                    "head" to mapOf("repo" to repo,"ref" to branch,"sha" to sha),"base" to mapOf("repo" to repo,"ref" to "main","sha" to base))
                remotePrs[branch]=pr
                if(losePr) error("Lost POST response")
                json(pr,201)
            }
            path.startsWith("$api/pulls/") -> json(remotePrs.values.single { it["number"]==path.substringAfterLast('/').toInt() })
            else -> error("Unexpected $method $path")
        }
    }
    private fun runtime(): PersonalRuntime {
        val config=PersonalConfig(true,root,home,binary,"fake-model",1,10809,90)
        return PersonalRuntime(config,"FAKE_SECRET",{true},http,PublicationPush { plan,stage,artifacts ->
            sends++
            val args=GitHubBranchPush("FAKE").arguments(plan,stage).drop(1).map {
                if(it=="https://github.com/${SMOKE_TARGET.fullName}.git") bare.toString() else it
            }
            Files.writeString(artifacts.resolve("events.log"),git(temp,*args.toTypedArray()))
            ProcessObservation(AttemptState.SUCCEEDED,"EXITED_WITH_CLEANUP",null)
        },{ i,checkout,_ -> git(checkout,"-c","protocol.file.allow=always","fetch","--no-tags",bare.toString(),i.baseSha); Unit })
    }
    private fun service(records: List<GitHubIssueRecord>)=PersonalService(root.resolve("service.db"),object: ServiceGitHub {
        override fun issues()=records
        override fun authorized(issue: GitHubIssueRecord)=true
        override fun postStatus(number: Int,body: String) {}
    },runtime(),{true})
    @Test fun newIssuesRunCompleteProductionChainAndRestartDoesNotRepeat() {
        correctOnce=true; setup()
        val records=listOf(issue(17),issue(18))
        var s=service(records)
        repeat(20) {
            s.tick()
            s=service(records); s.recover() // Restart after EVERY completed action, including corrections/publish.
        }
        assertEquals("IDLE",s.tick()); assertEquals(2,posts); assertEquals(2,sends)
        assertEquals("2",Files.readString(home.resolve(".codex/coding-count"))) // 3 Coding invocations total.
        assertEquals("2",Files.readString(home.resolve(".codex/review-count")))
        for(i in records) {
            val db=root.resolve("task-${i.githubIssueId}/run.db")
            val w=SqliteTaskStore(db).findWorkflow(1424152600,i.issueNumber)!!
            assertEquals(WorkflowPhase.DRAFT_PR_CREATED,w.phase)
            assertEquals(if(i.issueNumber==17) 1 else 0,w.correctionsUsed)
            val plan=PublicationJournal(db).findPlan(w.id)!!
            assertEquals(i.issueNumber,plan.issueNumber)
            assertTrue(git(temp,"--git-dir=$bare","cat-file","commit",plan.commitSha).contains("Implement Issue #${i.issueNumber}"))
        }
    }
    @Test fun lostRealPublicationReceiptHoldsAcrossServiceRestartWithoutNewModelsOrPost() {
        setup(); losePr=true; val records=listOf(issue(17),issue(18)); var s=service(records)
        repeat(7) { s.tick() }
        assertEquals(1,posts); assertEquals(1,sends)
        s=service(records); s.recover(); repeat(3) { assertEquals("NEEDS_OWNER",s.tick()) }
        assertEquals(1,posts); assertEquals("0",Files.readString(home.resolve(".codex/coding-count")))
        assertEquals("0",Files.readString(home.resolve(".codex/review-count")))
    }
}
