package io.droyagent.app;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Explicit synthetic probe only; never activates protection or recording settings. */
public final class MemoryExecutionQualificationDeviceTest {
    @Test public void unsupportedPreflightDoesNotLatchProtectionOrEnableRecording() throws Exception {
        assumeTrue("Requires explicit unsupported-kernel fixture", "true".equals(InstrumentationRegistry.getArguments().getString("assertUnavailablePreflight")));
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(ProductPermissions.allowed(ProductPermissions.preferences(context),"allowRoot"));
        android.content.SharedPreferences prefs=context.getSharedPreferences("droy_memory",android.content.Context.MODE_PRIVATE);
        assumeFalse(prefs.getBoolean("privacyEverActivated",false));
        assumeFalse(prefs.getBoolean("enabled",false));
        String failure=null;
        try { MemoryExecutionQualification.run(context); }
        catch(IllegalStateException unavailable){failure=unavailable.getMessage();}
        assumeTrue("This check applies only to a proven unsupported namespace",failure!=null&&failure.startsWith("MEMORY_EXECUTION_NAMESPACE_UNAVAILABLE"));
        java.util.Map<String,?> before=new java.util.HashMap<>(prefs.getAll());
        try { MemoryRuntime.get(context).request(new org.json.JSONObject().put("operation","activation.prepare")); fail("Unsupported kernel unexpectedly activated"); }
        catch(IllegalStateException expected){assertTrue(expected.getMessage().startsWith("MEMORY_EXECUTION_NAMESPACE_UNAVAILABLE"));}
        assertTrue("Preflight failure changed persistent memory choices",before.equals(prefs.getAll()));
        assertFalse(MemoryRuntime.get(context).settings().getBoolean("privacyEverActivated"));
        assertFalse(MemoryRuntime.get(context).settings().getBoolean("enabled"));
    }
    @Test public void packagedHelperQualifiesSyntheticWorkspace() throws Exception {
        assumeTrue("Run only with explicit native qualification argument", "true".equals(InstrumentationRegistry.getArguments().getString("allowNativeQualification")));
        assertTrue(MemoryExecutionQualification.checkRoot().authorized());
        MemoryExecutionQualification.Result result=MemoryExecutionQualification.run(InstrumentationRegistry.getInstrumentation().getTargetContext());
        assertTrue(result.reportHash().matches("[a-f0-9]{64}"));
    }
    @Test public void installedDshProvidersRunFromRealApplicationDomain() throws Exception {
        assumeTrue("Explicit synthetic execution runner only","true".equals(InstrumentationRegistry.getArguments().getString("allowExecutionRunner")));
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File script=new java.io.File(context.getCacheDir(),"droy-verify-memory-execution.mjs");
        assertTrue("Stage the reviewed synthetic runner in the fixed cache path",script.isFile());
        assertFalse(java.nio.file.Files.isSymbolicLink(script.toPath()));
        assertTrue(script.length()>0&&script.length()<262144);
        java.io.File node=new java.io.File(context.getApplicationInfo().nativeLibraryDir,"libdroy_node.so");
        ProcessBuilder builder=new ProcessBuilder(node.getAbsolutePath(),"--expose-internals",script.getAbsolutePath(),"--files-dir",MemoryExecutionQualification.filesDirectory(context),
            "--native-dir",context.getApplicationInfo().nativeLibraryDir).directory(context.getFilesDir());
        builder.environment().clear();builder.environment().put("PATH","/system/bin");
        Process process=builder.start();process.getOutputStream().close();
        java.util.concurrent.ExecutorService readers=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<byte[]> stdout=readers.submit(()->bounded(process.getInputStream()));
        java.util.concurrent.Future<byte[]> stderr=readers.submit(()->bounded(process.getErrorStream()));
        try {
            assertTrue("Synthetic runner timed out",process.waitFor(120,java.util.concurrent.TimeUnit.SECONDS));
            String output=new String(stdout.get(2,java.util.concurrent.TimeUnit.SECONDS),java.nio.charset.StandardCharsets.UTF_8).trim();
            byte[] diagnostics=stderr.get(2,java.util.concurrent.TimeUnit.SECONDS);
            org.json.JSONObject report=null;StringBuilder verifiedLines=new StringBuilder();
            for(String line:output.split("\\R")){
                if(line.isBlank())continue;
                org.json.JSONObject item=new org.json.JSONObject(line);
                String type=item.getString("type");
                assertTrue("Unexpected runner record",java.util.Set.of("verification-step","verification-report").contains(type));
                assertNull("Runner emitted data after its final report",report);
                if(type.equals("verification-report"))report=item;
                verifiedLines.append(item.toString()).append('\n');
            }
            assertNotNull("Runner did not emit a final report",report);
            // Only the runner's bounded synthetic report is exported. Native or
            // process stderr is not copied into instrumentation output.
            verifiedLines.append(new org.json.JSONObject().put("type","runner-diagnostics").put("stderrBytes",diagnostics.length)).append('\n');
            java.nio.file.Files.write(new java.io.File(context.getCacheDir(),"droy-execution-verification.ndjson").toPath(),
                verifiedLines.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("Synthetic provider runner failed; inspect its local report",0,process.exitValue());
            assertTrue(report.getBoolean("passed"));assertTrue(report.getBoolean("syntheticOnly"));
            assertTrue(report.getBoolean("cleanupConfirmed"));assertFalse(report.getBoolean("settingsChanged"));
        } finally {
            process.destroy();if(!process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS))process.destroyForcibly();
            process.getInputStream().close();process.getErrorStream().close();readers.shutdownNow();
        }
    }
    private static byte[] bounded(java.io.InputStream stream) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
        while((count=stream.read(buffer))!=-1){if(bytes.size()+count>65536)throw new java.io.IOException("Synthetic runner output exceeded limit");bytes.write(buffer,0,count);}
        return bytes.toByteArray();
    }
}
