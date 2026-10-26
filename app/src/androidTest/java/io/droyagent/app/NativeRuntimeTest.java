package io.droyagent.app;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

@RunWith(AndroidJUnit4.class)
public class NativeRuntimeTest {
    @Test public void testNodeAsOrdinaryAppUid() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject result = runNode("console.log(JSON.stringify({version:process.version,platform:process.platform,uid:process.getuid()}))");
        assertTrue(result.getInt("uid") > 10000);
        assertTrue(result.getString("version").startsWith("v24."));
    }

    @Test public void testTlsAndDns() throws Exception {
        JSONObject result = runNode("fetch('https://registry.npmjs.org/@deepseek-ai/dsh/latest').then(async r=>{if(!r.ok)throw Error('HTTP '+r.status);let p=await r.json();console.log(JSON.stringify({name:p.name}))}).catch(e=>{console.error(e);process.exit(1)})");
        assertEquals("@deepseek-ai/dsh", result.getString("name"));
    }

    @Test public void testNativeLocksAndNoOverwrite() throws Exception {
        JSONObject result = runNode("const fs=require('fs');const path=require('path');const m={exports:{}};process.dlopen(m,process.env.DROY_NATIVE_DIR+'/libdroy_system.so');const n=m.exports;const a=path.join(process.env.TMPDIR,'napi-lock-test');const fd1=fs.openSync(a,'w');const fd2=fs.openSync(a,'r+');let first,second;n.tryLock(fd1,e=>first=e);n.tryLock(fd2,e=>second=e);fs.closeSync(fd1);fs.closeSync(fd2);const x=a+'.source',y=a+'.target';fs.writeFileSync(x,'new');fs.writeFileSync(y,'old');const renameError=n.renameNoReplace(x,y);console.log(JSON.stringify({first,second,renameError,retained:fs.readFileSync(y,'utf8')}));fs.unlinkSync(a);fs.unlinkSync(x);fs.unlinkSync(y)");
        assertEquals(0, result.getInt("first"));
        assertTrue(result.getInt("second") != 0);
        assertEquals(17, result.getInt("renameError"));
        assertEquals("old", result.getString("retained"));
    }

    private JSONObject runNode(String script) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RuntimeInstaller.installCertificate(context);
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        ProcessBuilder builder = new ProcessBuilder(nativeDir + "/libdroy_node.so", "-e", script);
        builder.directory(context.getFilesDir()); builder.redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH", nativeDir);
        builder.environment().put("DROY_NATIVE_DIR", nativeDir);
        builder.environment().put("HOME", context.getFilesDir().getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        builder.environment().put("NODE_EXTRA_CA_CERTS", new File(context.getFilesDir(), "ca-certificates.crt").getAbsolutePath());
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        reader.start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Node runtime timed out"); }
        reader.join(2000);
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(text, 0, process.exitValue());
        return new JSONObject(text.substring(text.lastIndexOf('\n', text.length() - 2) + 1).trim());
    }
}

