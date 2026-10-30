package io.droyagent.app

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Explicit development-emulator fixture; synthetic sources and zero model calls. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionMemoryPurgeDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("messages")
    private val context get() = fixture.context

    @Test fun authorizedPurgeStopsHostRemovesEngineCopiesAndPreservesVisibleConversation() {
        val target = create("Synthetic memory purge target")
        val keep = create("Synthetic memory purge keeper")
        val input = JSONObject().put("target", target).put("keep", keep)
            .put("operationId", "fixture-purge-${UUID.randomUUID()}")
        stopRuntime()
        val expected = storageFixture("seed", input)
        input.put("memorySpaceId", expected.getString("targetMemorySpaceId"))
            .put("sourceCaptureBoundary", expected.getString("sourceCaptureBoundary"))
        fixture.startRuntime()
        val originalHost = NativeRuntimeHost.runtimeIdentity()
        assertTrue(originalHost.isNotEmpty())
        assertFalse(NativeCompanionMemoryPurge.pending(context))

        // Only this registry grant is written while the Host is live. Product
        // and Session stores are opened solely in the stopped seed/verify modes.
        val grant = storageFixture("grant", input)
        assertEquals("purging", grant.getString("state"))
        val purgeRequest = JSONObject().put("operation", "purge")
            .put("operationId", input.getString("operationId"))
            .put("memorySpaceId", input.getString("memorySpaceId"))
        val accepted = NativeCompanionMemoryPurge.request(context, purgeRequest)
        assertTrue(accepted.getBoolean("pending"))
        assertFalse(accepted.getBoolean("completed"))
        assertEquals(input.getString("memorySpaceId"), accepted.getString("memorySpaceId"))

        await(120_000, "Native purge must drain, stop, clean, persist its receipt and restart") {
            val status = purgeStatus(input)
            if (status.optString("phase") == "failed") fail("Native purge failed: ${status.optString("error")}")
            status.optBoolean("completed") && !status.optBoolean("pending") && !NativeCompanionMemoryPurge.pending(context) &&
                RuntimeService.ready && NativeRuntimeHost.inspect(context).ready && NativeRuntimeHost.runtimeIdentity() != originalHost
        }
        val completed = purgeStatus(input)
        assertEquals("complete", completed.getString("phase"))
        assertEquals(input.getString("operationId"), completed.getString("operationId"))
        assertEquals(input.getString("memorySpaceId"), completed.getString("memorySpaceId"))
        assertFalse(NativeCompanionDeletion.pending(context))

        // The durable native receipt is idempotent; replay cannot wipe the new space.
        val replacementHost = NativeRuntimeHost.runtimeIdentity()
        val replay = NativeCompanionMemoryPurge.request(context, purgeRequest)
        assertTrue(replay.getBoolean("completed")); assertFalse(replay.getBoolean("pending"))
        assertEquals(replacementHost, NativeRuntimeHost.runtimeIdentity())
        val profiles = request().getJSONArray("companions")
        assertTrue((0 until profiles.length()).any { profiles.getJSONObject(it).getString("id") == target })
        assertTrue((0 until profiles.length()).any { profiles.getJSONObject(it).getString("id") == keep })

        stopRuntime()
        val actual = storageFixture("verify", input)
        assertEquals(expected.getString("targetMessages"), actual.getString("targetMessages"))
        assertEquals(expected.getString("targetPersona"), actual.getString("targetPersona"))
        assertEquals(expected.getString("retainedMessages"), actual.getString("retainedMessages"))
        assertEquals(expected.getString("retainedMemory"), actual.getString("retainedMemory"))
        assertEquals(expected.getString("retainedSession"), actual.getString("retainedSession"))
        assertEquals(expected.getString("retainedMemorySpaceId"), actual.getString("retainedMemorySpaceId"))
        assertEquals("purged", actual.getString("oldMemoryState"))
        assertEquals("active", actual.getString("targetMemoryState"))
        assertNotEquals(expected.getString("targetMemorySpaceId"), actual.getString("targetMemorySpaceId"))
        assertEquals(0, actual.getInt("targetMemoryCount"))
        assertEquals(expected.getString("sourceCaptureBoundary"), actual.getString("sourceCaptureBoundary"))
        assertTrue(actual.getBoolean("targetEngineSessionAbsent"))
        assertTrue(actual.getBoolean("targetBindingsRetired"))
        assertTrue(actual.getBoolean("targetProfilePresent"))
        assertTrue(actual.getBoolean("targetWorkspacePresent"))
        assertTrue(actual.getBoolean("oldVaultAbsent"))
        assertTrue(actual.getBoolean("recalledTextAbsentFromSessionFiles"))
        for (name in listOf("native-fixture-calls.jsonl", "native-fixture-companion.jsonl")) {
            val calls = File(fixture.home, name)
            assertTrue("Purge fixture must make zero model calls: $name", !calls.exists() || calls.length() == 0L)
        }
        fixture.startRuntime()
        assertFalse(NativeCompanionMemoryPurge.pending(context))
        assertTrue(purgeStatus(input).getBoolean("completed"))
    }

    private fun create(name: String): String = request(method = "POST", body = JSONObject()
        .put("requestId", UUID.randomUUID().toString()).put("templateId", "kaka").put("name", name).put("model", JSONObject.NULL)
        .put("settings", JSONObject().put("proactiveEnabled", false).put("memoryEnabled", false)))
        .getJSONObject("profile").getString("id")

    private fun request(path: String = "", method: String = "GET", body: JSONObject? = null): JSONObject =
        runBlocking { fixture.runtimeTransport.json("/droy/companions$path", method, body) }

    private fun purgeStatus(input: JSONObject): JSONObject = NativeCompanionMemoryPurge.request(context, JSONObject()
        .put("operation", "status").put("operationId", input.getString("operationId"))
        .put("memorySpaceId", input.getString("memorySpaceId")))

    private fun stopRuntime() {
        context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
        await(30_000, "Storage inspection requires an exited Host") { RuntimeService.canManageHistory() }
    }

    private fun await(timeoutMs: Long, message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message, predicate())
    }

    private fun storageFixture(mode: String, input: JSONObject): JSONObject {
        if (mode == "grant") assertTrue(RuntimeService.ready) else assertTrue(RuntimeService.canManageHistory())
        val marker = JSONObject(File(context.filesDir, "native-workbench-fixture.json").readText())
        assertEquals("native-isolated-runtime-v1", marker.getString("fixture"))
        assertEquals("running", marker.getString("phase"))
        val script = File(fixture.home, "synthetic-memory-purge.mjs").apply { writeText(STORAGE_SCRIPT) }
        val request = File(fixture.home, "synthetic-memory-purge-input.json").apply { writeText(input.toString()) }
        val output = File(fixture.home, "synthetic-memory-purge-output.json")
        val log = File(fixture.home, "synthetic-memory-purge-log.txt")
        val native = context.applicationInfo.nativeLibraryDir
        val process = ProcessBuilder(File(native, "libdroy_node.so").absolutePath, "--expose-internals", script.absolutePath,
            mode, request.absolutePath, output.absolutePath).directory(fixture.home).redirectErrorStream(true).redirectOutput(log)
            .apply { environment().putAll(mapOf("DSH_HOME" to fixture.home.absolutePath, "HOME" to fixture.home.absolutePath,
                "DROY_RUNTIME_DIR" to File(context.filesDir, "runtime").absolutePath, "DROY_NATIVE_DIR" to native,
                "LD_LIBRARY_PATH" to native, "TMPDIR" to context.cacheDir.absolutePath)) }.start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            assertTrue("Stop the synthetic child before restoring the fixture", process.waitFor(10, TimeUnit.SECONDS))
            fail("Memory purge storage fixture timed out")
        }
        assertEquals("Synthetic memory purge storage fixture: ${log.readText().take(4000)}", 0, process.exitValue())
        return JSONObject(output.readText())
    }

    companion object {
        private val STORAGE_SCRIPT = """
            import assert from 'node:assert/strict';
            import {readFile,writeFile,mkdir,readdir,lstat} from 'node:fs/promises';
            import {existsSync} from 'node:fs';
            import {join,dirname} from 'node:path';
            import {pathToFileURL} from 'node:url';
            import {createRequire} from 'node:module';
            import {createHash} from 'node:crypto';
            const home=process.env.DSH_HOME,runtime=process.env.DROY_RUNTIME_DIR,mode=process.argv[2];
            const marker=JSON.parse(await readFile(join(dirname(home),'native-workbench-fixture.json'),'utf8'));
            assert.equal(marker.fixture,'native-isolated-runtime-v1');assert.equal(marker.phase,'running');
            assert.equal(home,join(dirname(home),'dsh-home'));assert.ok(['seed','grant','verify'].includes(mode));
            const input=JSON.parse(await readFile(process.argv[3],'utf8')),root=join(home,'droy-companions');
            assert.ok(input.target.startsWith('companion-'));assert.ok(input.keep.startsWith('companion-'));assert.notEqual(input.target,input.keep);
            const load=relative=>import(pathToFileURL(join(runtime,'node_modules',relative)).href);
            const {CompanionMemorySpaces}=await load('@droyagent/mobile/companions/memory-spaces.mjs');
            const spaces=await CompanionMemorySpaces.open({directory:root});
            if(mode==='grant'){
              const binding=await spaces.current(input.target);assert.equal(binding.memorySpaceId,input.memorySpaceId);assert.equal(binding.state,'active');
              const pending=await spaces.beginPurge(binding.memorySpaceId,{expectedRevision:binding.revision,operationId:input.operationId,sourceCaptureBoundary:input.sourceCaptureBoundary});
              await writeFile(process.argv[4],JSON.stringify({state:pending.state,memorySpaceId:pending.memorySpaceId}));
            }else{
              const {CompanionStore}=await load('@droyagent/mobile/companions/store.mjs');
              const {MemosMemoryStore}=await load('@droyagent/memory/memos/store.mjs');
              const {createOfflineJsonlProvider}=await load('@droyagent/mobile/companions/maintenance-sessions.mjs');
              const require=createRequire(join(runtime,'package.json'));
              const {createUserMessage,createAssistantMessage,createToolResultMessage}=await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-llm')).href);
              const store=await CompanionStore.open({directory:root});
              const sessions=await createOfflineJsonlProvider()({root:join(home,'sessions'),compression:'none'});
              const workspaceFile=join(home,'storages','workspace.json'),workspace=JSON.parse(await readFile(workspaceFile,'utf8'));
              const digest=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
              const sid=id=>'purge-fixture-session-'+id.slice(-12),wid=id=>'purge-fixture-space-'+id.slice(-12);
              const source=id=>'purge-fixture-message-'+id.slice(-12),recalled='SYNTHETIC_MEMORY_RECALL_BODY_target';
              async function readSession(id){const reader=await sessions.store.open(sid(id),'read');try{return {header:reader.header,...await reader.read()};}finally{await reader.close();}}
              async function memorySummary(binding){
                if(!existsSync(join(binding.directory,'memory.sqlite')))return {count:0,digest:digest([])};
                const memory=await MemosMemoryStore.open({directory:binding.directory,companionId:binding.companionId,memorySpaceId:binding.memorySpaceId});
                try{const records=await memory.query();return {count:records.total,digest:digest(records)};}finally{await memory.close();}
              }
              async function hasText(path,text){
                if(!existsSync(path))return false;
                const value=await lstat(path);assert.equal(value.isSymbolicLink(),false);
                if(value.isDirectory()){for(const name of await readdir(path))if(await hasText(join(path,name),text))return true;return false;}
                return (await readFile(path)).includes(Buffer.from(text));
              }
              const persona=profile=>Object.fromEntries(['id','name','personality','behaviorStyle','replyStyle','personalityOverride','behaviorStyleOverride','replyStyleOverride','personaRevision','settings']
                .map(key=>[key,profile[key]]));
              try{
                if(mode==='seed'){
                  for(const [kind,id] of [['target',input.target],['keep',input.keep]]){
                    const directory=join(root,id),cwd=join(directory,'chat');await mkdir(cwd,{recursive:true});await mkdir(join(directory,'work'),{recursive:true});
                    workspace.tables.workspaces[wid(id)]={path:join(directory,'work'),title:'Synthetic '+kind,sessionIds:[],createdAt:new Date(0).toISOString(),updatedAt:new Date(0).toISOString()};
                    workspace.global.workspaceIds.push(wid(id));
                    await store.updateProfile(id,{workspaceId:wid(id),chatSessionId:sid(id),settings:{proactiveEnabled:false,memoryEnabled:false}});
                    await store.bindSession(id,{workspaceId:wid(id),sessionId:sid(id),kind:'chat'});
                    await store.appendMessage(id,{id:source(id),role:'user',type:'text',text:'Synthetic '+kind+' visible chat',status:'sent'});
                    const binding=await spaces.ensureActiveSpace(id,{profile:await store.getProfile(id)});
                    const memory=await MemosMemoryStore.open({directory:binding.directory,companionId:id,memorySpaceId:binding.memorySpaceId,initialize:true});
                    try{await memory.record({operationId:'synthetic-fact',record:{kind:'fact',payload:{text:kind==='target'?recalled:'Synthetic keeper memory'},sourceRefs:['im:'+id+':'+source(id)]}});}finally{await memory.close();}
                    const rows=[],append=(type,data,surfaceOp)=>rows.push({type,data,seq:rows.length,time:rows.length+1,...(surfaceOp?{surfaceOp}:{})});
                    const callId='fixture-recall-'+kind,raw=kind==='target'?recalled:'SYNTHETIC_MEMORY_RECALL_BODY_keep';
                    append('turn/start',{turn:1});append('user/message',createUserMessage({content:[{type:'text',text:'Synthetic recall request'}],source:{kind:'user'}}),'append');
                    append('step/start',{turn:1,step:1});
                    append('assistant/message',{turn:1,step:1,message:createAssistantMessage({content:[{type:'tool-call',id:callId,name:'companion_recall',arguments:'{"query":"synthetic"}'}],source:{provider:'droy-native-fixture',model:'native-local'}}),stream:[]},'append');
                    append('tool/call',{turn:1,step:1,callId,name:'companion_recall',arguments:'{"query":"synthetic"}'});
                    append('tool/result',{turn:1,step:1,message:createToolResultMessage({callId,isError:false,content:[{type:'text',text:raw}]})},'append');
                    append('step/end',{turn:1,step:1});append('step/start',{turn:1,step:2});
                    append('assistant/message',{turn:1,step:2,message:createAssistantMessage({content:[{type:'text',text:'Synthetic completed response'}],source:{provider:'droy-native-fixture',model:'native-local'}}),stream:[]},'append');
                    append('step/end',{turn:1,step:2});append('turn/end',{turn:1,reason:{kind:'completed'}});
                    const writer=await sessions.store.create({version:4,id:sid(id),cwd,createdAt:1,isSeeded:false,delegationDepth:0},{inheritedEventCount:0});
                    try{await writer.append(rows);await writer.flush();}finally{await writer.close();}
                  }
                  await writeFile(workspaceFile,JSON.stringify(workspace));
                }
                const target=await spaces.current(input.target),keep=await spaces.current(input.keep),targetMemory=await memorySummary(target),keepMemory=await memorySummary(keep);
                const profile=await store.getProfile(input.target),old=mode==='verify'?await spaces.get(input.memorySpaceId):null;
                const result={targetMemorySpaceId:target.memorySpaceId,targetMemoryState:target.state,targetMemoryCount:targetMemory.count,
                  retainedMemorySpaceId:keep.memorySpaceId,retainedMemory:keepMemory.digest,retainedSession:digest(await readSession(input.keep)),
                  targetMessages:digest(await store.listMessages(input.target)),retainedMessages:digest(await store.listMessages(input.keep)),targetPersona:digest(persona(profile)),
                  sourceCaptureBoundary:mode==='seed'?(await store.listMessages(input.target,{limit:1})).nextAfter:target.sourceCaptureBoundary};
                if(mode==='verify')Object.assign(result,{oldMemoryState:old.state,oldVaultAbsent:!existsSync(dirname(old.directory)),
                  targetEngineSessionAbsent:!(await sessions.store.stat(sid(input.target))),targetBindingsRetired:(await store.listSessions(input.target)).length===0,
                  targetProfilePresent:profile.id===input.target,targetWorkspacePresent:!!workspace.tables.workspaces[wid(input.target)],
                  recalledTextAbsentFromSessionFiles:!await hasText(join(home,'sessions'),recalled)});
                else assert.equal(await hasText(join(home,'sessions'),recalled),true);
                await writeFile(process.argv[4],JSON.stringify(result));
              }finally{await sessions.close();await store.close();}
            }
        """.trimIndent()
    }
}
