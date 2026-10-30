package io.droyagent.app

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.media.MediaInputStore
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Explicit emulator fixture only. No message is submitted and no model is invoked. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionDeletionDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val mediaIsolation = CompanionDeletionMediaIsolation()
    @get:Rule(order = 2) val fixture = NativeIsolatedRuntimeFixture("messages")
    private val context get() = fixture.context
    private lateinit var companions: NativeCompanionViewModel

    @Test fun confirmedDeletionStopsHostRetiresOnlyTargetAndRestartsWithoutPendingJournal() {
        bindModels()
        compose.waitUntil(20_000) { companions.state.value.runtime.ready }
        val target = create("Synthetic deletion target")
        val keep = create("Synthetic retained companion")
        val input = JSONObject().put("target", target).put("keep", keep)
        stopRuntime()
        val expected = storageFixture("seed", input)
        fixture.startRuntime()
        fixture.recreate(); bindModels()
        compose.waitUntil(20_000) { companions.state.value.companions.any { it.id == target } && companions.state.value.companions.any { it.id == keep } }
        fixture.scenario.onActivity {
            companions.selectCompanion(keep); companions.setDraft("Synthetic retained unsent draft")
            companions.selectCompanion(target); companions.setDraft("Synthetic target unsent draft")
        }
        val preferences = context.getSharedPreferences("native_companions", 0)
        assertTrue(preferences.edit().commit())
        val retainedDraft = requireNotNull(preferences.getString("draft:$keep", null))
        assertTrue(preferences.contains("draft:$target"))

        // Unbound shares belong to the user. The manifest must survive even without a destination.
        val shareId = UUID.randomUUID().toString()
        val shareStore = MediaInputStore.nativeDrafts(context)
        shareStore.importUrisForOperation(shareId, "share", null, emptyList(), "Synthetic retained shared text", false, 0)
        val shareFile = File(context.filesDir, "media-input/native-v1/$shareId/manifest.json")
        val retainedShare = shareFile.readText()
        val targetImportId = UUID.randomUUID().toString()
        MediaInputStore(context).importUrisForOperation(targetImportId, "share", "im:$target", emptyList(), "Synthetic target legacy share", false, 0, "companion")
        val targetImport = File(context.filesDir, "media-input/v1/$targetImportId")
        assertTrue(targetImport.isDirectory)
        val cache = File(context.filesDir, "media-input/captures/native-open/${UUID.randomUUID()}/synthetic-open.txt")
        assertTrue(cache.parentFile!!.isDirectory || cache.parentFile!!.mkdirs())
        cache.writeText("Synthetic rebuildable open cache")

        val originalHost = NativeRuntimeHost.runtimeIdentity()
        assertTrue(originalHost.isNotEmpty())
        awaitNode("native-companion-conversation:$target")
        compose.onNodeWithTag("native-companion-conversation:$target").performClick()
        awaitNode("native-open-companion-profile")
        compose.onNodeWithTag("native-open-companion-profile").performClick()
        awaitNode("native-companion-delete-profile")
        compose.onNodeWithTag("native-companion-delete-profile").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.native_companion_confirm_delete)).performClick()
        await(120_000, "Deletion must complete, release both journals and restart the real Host") {
            val state = NativeCompanionDeletion.inspect(context)
            state.phase() in setOf("complete", "idle") && !NativeCompanionDeletion.pending(context) &&
                !context.getSharedPreferences("native_companion_local_maintenance", 0).contains("plan") &&
                RuntimeService.ready && NativeRuntimeHost.inspect(context).ready && NativeRuntimeHost.runtimeIdentity() != originalHost
        }
        assertFalse(NativeCompanionDeletion.inspect(context).blocksEditing())
        assertFalse(preferences.contains("draft:$target"))
        assertEquals(retainedDraft, preferences.getString("draft:$keep", null))
        assertEquals(retainedShare, shareFile.readText())
        assertFalse(targetImport.exists())
        assertFalse(cache.exists())
        assertFalse(File(fixture.home, "droy-companions/$target").exists())
        val profiles = request().getJSONArray("companions")
        assertFalse((0 until profiles.length()).any { profiles.getJSONObject(it).getString("id") == target })
        assertTrue((0 until profiles.length()).any { profiles.getJSONObject(it).getString("id") == keep })
        fixture.capture("native-companion-deletion-complete")

        // Read the same provider APIs after the actual restart to prove retained events and memory survived.
        stopRuntime()
        val actual = storageFixture("verify", input)
        assertEquals(expected.getString("retainedSession"), actual.getString("retainedSession"))
        assertEquals(expected.getString("retainedMemory"), actual.getString("retainedMemory"))
        assertEquals(expected.getString("retainedMessages"), actual.getString("retainedMessages"))
        assertTrue(actual.getBoolean("targetAbsent"))
        assertTrue(actual.getBoolean("workspaceAbsent"))
        for (name in listOf("native-fixture-calls.jsonl", "native-fixture-companion.jsonl")) {
            val calls = File(fixture.home, name)
            assertTrue("Deletion fixture must make zero model calls: $name", !calls.exists() || calls.length() == 0L)
        }
        fixture.startRuntime()
        assertFalse(NativeCompanionDeletion.pending(context))
        fixture.recreate(); bindModels()
        compose.waitUntil(20_000) { companions.state.value.runtime.ready && companions.state.value.companions.none { it.id == target } }
        assertEquals(retainedDraft, preferences.getString("draft:$keep", null))
        assertEquals(retainedShare, shareFile.readText())
    }

    private fun create(name: String): String = request(method = "POST", body = JSONObject()
        .put("requestId", UUID.randomUUID().toString()).put("templateId", "kaka").put("name", name).put("model", JSONObject.NULL)
        .put("settings", JSONObject().put("proactiveEnabled", false).put("memoryEnabled", false)))
        .getJSONObject("profile").getString("id")
    private fun request(path: String = "", method: String = "GET", body: JSONObject? = null): JSONObject =
        runBlocking { fixture.runtimeTransport.json("/droy/companions$path", method, body) }
    private fun bindModels() { fixture.scenario.onActivity { companions = ViewModelProvider(it)[NativeCompanionViewModel::class.java] } }
    private fun awaitNode(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
        compose.waitForIdle()
    }
    private fun stopRuntime() {
        context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
        await(30_000, "Storage fixture must never run alongside the Host") { RuntimeService.canManageHistory() }
    }
    private fun await(timeout: Long, message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("$message; phase=${NativeCompanionDeletion.inspect(context).phase()}, error=${NativeCompanionDeletion.inspect(context).error()}", predicate())
    }
    private fun storageFixture(mode: String, input: JSONObject): JSONObject {
        assertTrue(RuntimeService.canManageHistory())
        val marker = JSONObject(File(context.filesDir, "native-workbench-fixture.json").readText())
        assertEquals("native-isolated-runtime-v1", marker.getString("fixture"))
        assertEquals("running", marker.getString("phase"))
        val script = File(fixture.home, "synthetic-companion-deletion.mjs").apply { writeText(STORAGE_SCRIPT) }
        val requestFile = File(fixture.home, "synthetic-companion-deletion-input.json").apply { writeText(input.toString()) }
        val output = File(fixture.home, "synthetic-companion-deletion-output.json")
        val log = File(fixture.home, "synthetic-companion-deletion-log.txt")
        val native = context.applicationInfo.nativeLibraryDir
        val process = ProcessBuilder(File(native, "libdroy_node.so").absolutePath, "--expose-internals", script.absolutePath,
            mode, requestFile.absolutePath, output.absolutePath).directory(fixture.home).redirectErrorStream(true).redirectOutput(log)
            .apply { environment().putAll(mapOf("DSH_HOME" to fixture.home.absolutePath, "HOME" to fixture.home.absolutePath,
                "DROY_RUNTIME_DIR" to File(context.filesDir, "runtime").absolutePath, "DROY_NATIVE_DIR" to native,
                "LD_LIBRARY_PATH" to native, "TMPDIR" to context.cacheDir.absolutePath)) }.start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            assertTrue("Stop the fixture child before any restore", process.waitFor(10, TimeUnit.SECONDS))
            fail("Storage fixture timed out")
        }
        assertEquals("Synthetic storage fixture: ${log.readText().take(4000)}", 0, process.exitValue())
        return JSONObject(output.readText())
    }

    companion object {
        private val STORAGE_SCRIPT = """
            import assert from 'node:assert/strict';
            import {readFile,writeFile,mkdir} from 'node:fs/promises';
            import {join,dirname} from 'node:path';
            import {pathToFileURL} from 'node:url';
            import {createRequire} from 'node:module';
            import {createHash} from 'node:crypto';
            const home=process.env.DSH_HOME,runtime=process.env.DROY_RUNTIME_DIR;
            const marker=JSON.parse(await readFile(join(dirname(home),'native-workbench-fixture.json'),'utf8'));
            assert.equal(marker.fixture,'native-isolated-runtime-v1');assert.equal(marker.phase,'running');
            assert.equal(home,join(dirname(home),'dsh-home'));
            const load=relative=>import(pathToFileURL(join(runtime,'node_modules',relative)).href);
            const {CompanionStore}=await load('@droyagent/mobile/companions/store.mjs');
            const {createOfflineJsonlProvider}=await load('@droyagent/mobile/companions/maintenance-sessions.mjs');
            const {MemoryStore}=await load('@droyagent/memory/store.mjs');
            const require=createRequire(join(runtime,'package.json'));
            const {createUserMessage,createAssistantMessage}=await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-llm')).href);
            const input=JSON.parse(await readFile(process.argv[3],'utf8')),root=join(home,'droy-companions');
            const store=await CompanionStore.open({directory:root});
            const sessions=await createOfflineJsonlProvider()({root:join(home,'sessions'),compression:'none'});
            const workspaceFile=join(home,'storages','workspace.json');
            const workspace=JSON.parse(await readFile(workspaceFile,'utf8'));
            const digest=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
            const sid=id=>'fixture-chat-'+id.slice(-12),wid=id=>'fixture-workspace-'+id.slice(-12);
            async function readSession(id){const reader=await sessions.store.open(sid(id),'read');try{return {header:reader.header,...await reader.read()};}finally{await reader.close();}}
            try {
              if(process.argv[2]==='seed'){
                for(const [kind,id] of Object.entries(input)){
                  const directory=join(root,id),cwd=join(directory,'chat');
                  await mkdir(cwd,{recursive:true});await mkdir(join(directory,'work'),{recursive:true});
                  await writeFile(join(directory,'work','synthetic.txt'),'Synthetic '+kind+' workspace');
                  workspace.tables.workspaces[wid(id)]={path:join(directory,'work'),title:'Synthetic '+kind,sessionIds:[],createdAt:new Date(0).toISOString(),updatedAt:new Date(0).toISOString()};
                  workspace.global.workspaceIds.push(wid(id));
                  await store.updateProfile(id,{workspaceId:wid(id),chatSessionId:sid(id),settings:{proactiveEnabled:false,memoryEnabled:false}});
                  await store.bindSession(id,{workspaceId:wid(id),sessionId:sid(id),kind:'chat'});
                  await store.appendMessage(id,{id:'synthetic-message-'+kind,role:'assistant',type:'text',text:'Synthetic '+kind+' companion body'});
                  const header={version:4,id:sid(id),cwd,createdAt:1,isSeeded:false,delegationDepth:0};
                  const rows=[],append=(type,data,surfaceOp)=>rows.push({type,data,seq:rows.length,time:rows.length+1,...(surfaceOp?{surfaceOp}:{})});
                  append('turn/start',{turn:1});
                  append('user/message',createUserMessage({content:[{type:'text',text:'Synthetic '+kind+' session body'}],source:{kind:'user'}}),'append');
                  append('step/start',{turn:1,step:1});
                  append('assistant/message',{turn:1,step:1,message:createAssistantMessage({content:[{type:'text',text:'Synthetic completed reply'}],source:{provider:'droy-native-fixture',model:'native-local'}}),stream:[]},'append');
                  append('step/end',{turn:1,step:1});append('turn/end',{turn:1,reason:{kind:'completed'}});
                  const writer=await sessions.store.create(header,{inheritedEventCount:0});
                  try{await writer.append(rows);await writer.flush();}finally{await writer.close();}
                  const memory=await MemoryStore.open({directory:join(directory,'memory'),initialize:true});
                  try{await memory.record({operationId:'synthetic-fact',record:{kind:'fact',payload:{text:'Synthetic '+kind+' memory'}}});}finally{await memory.close();}
                }
                await writeFile(workspaceFile,JSON.stringify(workspace));
              } else assert.equal(process.argv[2],'verify');
              const memory=await MemoryStore.open({directory:join(root,input.keep,'memory')});
              let retainedMemory;try{retainedMemory=digest(await memory.query());}finally{await memory.close();}
              const result={retainedSession:digest(await readSession(input.keep)),retainedMemory,
                retainedMessages:digest(await store.listMessages(input.keep)),targetAbsent:!(await sessions.store.stat(sid(input.target))),
                workspaceAbsent:!workspace.tables.workspaces[wid(input.target)]};
              await writeFile(process.argv[4],JSON.stringify(result));
            } finally {await sessions.close();await store.close();}
        """.trimIndent()
    }
}

/** Runs outside the shared home fixture so every original media/cache byte is restored afterward. */
class CompanionDeletionMediaIsolation : ExternalResource() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var backup: File
    private lateinit var marker: File
    private var hadRoot = false
    private var ownsRoot = false
    private var ownsMarker = false
    override fun before() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeWorkbenchFixture") == "true")
        assertEquals("io.droyagent.app.dev", context.packageName)
        assertTrue(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("emulator"))
        assertTrue(RuntimeService.canManageHistory())
        assertFalse(NativeCompanionDeletion.pending(context))
        assertTrue(AudioCapture.get(context).uiStatus().isNull("active"))
        assertFalse("Recover the previous home fixture first", File(context.filesDir, "native-workbench-fixture.json").exists())
        root = File(context.filesDir, "media-input")
        backup = File(context.filesDir, "native-deletion-media-${System.nanoTime()}")
        marker = File(context.filesDir, "native-deletion-media-fixture.json")
        assertTrue("Recover the previous media fixture first", marker.createNewFile()); ownsMarker = true
        try {
            hadRoot = root.exists()
            marker.writeText(JSONObject().put("fixture", "native-deletion-media-v1").put("backup", backup.name).put("hadRoot", hadRoot).toString())
            if (hadRoot) assertTrue("Preserve the original media and native-open cache", root.renameTo(backup))
            ownsRoot = true
        } catch (error: Throwable) {
            if (!ownsRoot && ownsMarker) { assertTrue(marker.delete()); ownsMarker = false }
            throw error
        }
    }
    override fun after() {
        if (!ownsMarker) return
        // A failed runtime restore leaves both backups available for deliberate recovery.
        assertFalse("Restore the unfinished home fixture before media", File(context.filesDir, "native-workbench-fixture.json").exists())
        assertTrue(RuntimeService.canManageHistory())
        if (ownsRoot) {
            assertTrue("Remove only isolated fixture media", !root.exists() || root.deleteRecursively())
            if (hadRoot) assertTrue("Restore original media", backup.renameTo(root))
            ownsRoot = false
        }
        assertTrue(marker.delete()); ownsMarker = false
    }
}
