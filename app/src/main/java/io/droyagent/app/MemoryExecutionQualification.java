package io.droyagent.app;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Fixed native qualification only; no command, environment or path comes from UI JSON. */
final class MemoryExecutionQualification {
    record RootResult(boolean authorized, String version) {}
    record Result(String reportHash) {}
    private static final List<String> BOUNDARIES = List.of("mountNamespace", "netNamespace", "utsNamespace",
        "rootFilesystemPrivate", "capabilitiesEmpty", "noNewPrivs", "inheritedHostFdsClosed", "seccomp", "ipcSyscallsDenied", "uidLease", "descendantsManaged");
    private static final int MAX_FRAME = 65536, MAX_TOTAL = 131072;
    private static final Set<Process> ACTIVE = ConcurrentHashMap.newKeySet();
    private MemoryExecutionQualification() {}
    static boolean hasActiveProcesses() { return !ACTIVE.isEmpty(); }
    static void cancelAll() {
        if(ACTIVE.isEmpty())return;
        new Thread(()->{for(Process process:ACTIVE)close(process.getOutputStream());},"droy-memory-cancel-qualification").start();
    }

    static RootResult checkRoot() throws InterruptedException {
        try {
            String uid = command(List.of("su", "-c", "id -u"), 12000).trim();
            if (!uid.equals("0")) return new RootResult(false, "");
            String version;
            try { version = command(List.of("su", "-v"), 3000).trim(); }
            catch (IOException failedVersion) { version = "unavailable"; }
            if (!version.matches("[A-Za-z0-9 ._()+:/-]{1,96}")) version = "unavailable";
            return new RootResult(true, version);
        } catch (IOException unavailable) { return new RootResult(false, ""); }
    }
    private static String command(List<String> argv, int timeoutMs) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        ACTIVE.add(process);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            process.getOutputStream().close();
            Future<byte[]> output = reader.submit(() -> bounded(process.getInputStream(), 1024));
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) throw new IOException("root_timeout");
            if (process.exitValue() != 0) throw new IOException("root_denied");
            try { return new String(output.get(500, TimeUnit.MILLISECONDS), StandardCharsets.UTF_8); }
            catch (ExecutionException | TimeoutException invalid) { throw new IOException("root_output", invalid); }
        } finally { process.destroyForcibly(); close(process.getInputStream()); reader.shutdownNow(); ACTIVE.remove(process); }
    }
    static Result run(Context context) throws Exception {
        File nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        File launcher = new File(nativeDirectory, "libdroy_exec.so"), node = new File(nativeDirectory, "libdroy_node.so"), bash = new File(nativeDirectory, "libdroy_bash.so");
        if (!launcher.isFile() || !node.isFile() || !bash.isFile()) throw new IllegalStateException("MEMORY_EXECUTION_COMPONENT_MISSING");
        String files = filesDirectory(context);
        String workspaceId = "0".repeat(64);
        Path canary = Files.createTempFile(context.getFilesDir().toPath(), ".memory-qualification-", ".synthetic");
        Files.write(canary, "synthetic qualification input".getBytes(StandardCharsets.UTF_8));
        String script = "const fs=require('fs'),cp=require('child_process');"
            + "function ok(v){if(!v)process.exit(73)};const uid=process.getuid();ok(uid>=" + ((android.os.Process.myUid()/100000)*100000+80000)
            + "&&uid<=" + ((android.os.Process.myUid()/100000)*100000+80007) + "&&process.geteuid()===uid&&process.getgid()===" + android.os.Process.myUid() + ");"
            + "const s=fs.readFileSync('/proc/self/status','utf8');for(const k of ['CapInh','CapPrm','CapEff','CapBnd','CapAmb'])ok(new RegExp('^'+k+':\\\\s*0+$','m').test(s));"
            + "ok(/^NoNewPrivs:\\s*1$/m.test(s));let denied=false;try{process.setuid(0)}catch{denied=true};ok(denied);"
            + "let hidden=false;try{fs.readFileSync(" + JSONObject.quote(canary.toString()) + ")}catch{hidden=true};ok(hidden);"
            + "for(const p of [" + JSONObject.quote(files + "/droy-memory") + "," + JSONObject.quote(files + "/droy-history-spool") + ",'/proc/1/fd']){let blocked=false;try{fs.readdirSync(p)}catch{blocked=true};ok(blocked)};"
            + "const guest=cp.spawnSync(" + JSONObject.quote(launcher.getAbsolutePath()) + ",['--guest-check'],{encoding:'utf8',timeout:3000});ok(guest.status===0);const check=JSON.parse(guest.stdout);"
            + "ok(check.uid===uid);for(const k of ['ok','capabilitiesEmpty','noNewPrivs','initDescriptorsDenied','initMemoryDenied','keyringsDenied','vsockDenied','ioUringDenied','rootDriverDenied','rootReacquisitionDenied','hostServicePathsHidden','procSubsetPid'])ok(check[k]===true);"
            + "for(const p of ['su','/system/bin/su','/system/xbin/su','/sbin/su']){const r=cp.spawnSync(p,['-c','id -u'],{encoding:'utf8',timeout:1000});ok(r.status!==0||r.stdout.trim()!=='0')};"
            + "fs.writeFileSync('native-qualification.synthetic','synthetic');const child=cp.spawnSync(" + JSONObject.quote(bash.getAbsolutePath())
            + ",['--noprofile','--norc','-c','cat native-qualification.synthetic'],{encoding:'utf8',timeout:3000,env:{PATH:'/system/bin'}});"
            + "ok(child.status===0&&child.stdout==='synthetic');fs.unlinkSync('native-qualification.synthetic');process.stdout.write('DROY_NATIVE_QUALIFIED_V1\\n');";
        Process process = null;
        ExecutorService readers = Executors.newFixedThreadPool(2);
        boolean complete = false;
        try {
            process = new ProcessBuilder("su", "-c", "exec " + shellQuote(launcher.getAbsolutePath())).start();
            ACTIVE.add(process);
            Process child = process;
            Future<JSONObject> frames = readers.submit(() -> readFrames(child.getInputStream(), android.os.Process.myUid()));
            Future<byte[]> diagnostics = readers.submit(() -> bounded(child.getErrorStream(), 8192));
            DataOutputStream output = new DataOutputStream(process.getOutputStream());
            output.write(startFrame(files, workspaceId, node.getAbsolutePath(), script, android.os.Process.myUid()));
            output.writeInt(1); output.writeByte(3); output.flush();
            JSONObject report;
            try { report = frames.get(20000, TimeUnit.MILLISECONDS); }
            catch (TimeoutException timeout) { throw new IllegalStateException("MEMORY_EXECUTION_QUALIFICATION_TIMEOUT"); }
            catch (ExecutionException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                throw new IllegalStateException("MEMORY_EXECUTION_QUALIFICATION_FAILED");
            }
            if (!process.waitFor(1000, TimeUnit.MILLISECONDS) || process.exitValue() != 0) throw new IllegalStateException("MEMORY_EXECUTION_EXIT_UNCONFIRMED");
            diagnostics.get(1000, TimeUnit.MILLISECONDS);
            complete = true;
            return new Result(RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(report.toString().getBytes(StandardCharsets.UTF_8))));
        } finally {
            if (process != null) {
                // EOF cancels the namespace. Killing a su wrapper alone is not a cleanup receipt.
                close(process.getOutputStream());
                if (!complete && !process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                close(process.getInputStream()); close(process.getErrorStream());
                ACTIVE.remove(process);
            }
            readers.shutdownNow(); Files.deleteIfExists(canary);
        }
    }
    static String filesDirectory(Context context) throws Exception {
        String files = filesDirectory(context.getPackageName(), android.os.Process.myUid());
        if (!Files.isSameFile(Paths.get(files),context.getFilesDir().toPath())) throw new IllegalStateException("MEMORY_EXECUTION_PACKAGE_UNSUPPORTED");
        return files;
    }
    static String filesDirectory(String packageName, int uid) {
        if ((!packageName.equals("io.droyagent.app") && !packageName.equals("io.droyagent.app.dev"))
                || uid % 100000 < 10000 || uid % 100000 > 19999)
            throw new IllegalStateException("MEMORY_EXECUTION_PACKAGE_UNSUPPORTED");
        return "/data/user/" + (uid/100000) + "/" + packageName + "/files";
    }
    static void cleanup(Context context) throws Exception {
        int uid = android.os.Process.myUid(), pid = android.os.Process.myPid();
        String startTime = processStartTime("self");
        File launcher = new File(context.getApplicationInfo().nativeLibraryDir, "libdroy_exec.so");
        if (!launcher.isFile()) throw new IllegalStateException("MEMORY_EXECUTION_COMPONENT_MISSING");
        String fixed = "IFS= read -r droy_stat </proc/$$/stat || exit 123; droy_stat=${droy_stat##*) }; set -- $droy_stat; "
            + "printf 'DROY_CLEANUP_CLIENT:%s:%s\\n' \"$$\" \"${20}\"; exec su -c " + shellQuote("exec " + shellQuote(launcher.getAbsolutePath()) + " --cleanup");
        Process process = new ProcessBuilder("/system/bin/sh", "-c", fixed).start();
        ACTIVE.add(process);
        ExecutorService readers = Executors.newFixedThreadPool(2);
        try {
            Future<byte[]> errors = readers.submit(() -> bounded(process.getErrorStream(), 8192));
            Future<String> prelude = readers.submit(() -> {
                ByteArrayOutputStream line = new ByteArrayOutputStream(); int next;
                while ((next=process.getInputStream().read())!=-1 && next!='\n') {
                    if (line.size()>=80) throw new IllegalStateException("MEMORY_CLEANUP_IDENTITY"); line.write(next);
                }
                return line.toString("UTF-8");
            });
            String identity = prelude.get(3000,TimeUnit.MILLISECONDS);
            if (!identity.matches("DROY_CLEANUP_CLIENT:[0-9]{1,10}:[0-9]{1,20}")) throw new IllegalStateException("MEMORY_CLEANUP_IDENTITY");
            String[] client=identity.split(":");int clientPid=Integer.parseInt(client[1]);String clientStartTime=client[2];
            Future<Boolean> result = readers.submit(() -> cleanupFrames(process.getInputStream(), uid, pid, startTime, clientPid, clientStartTime));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream payload = new DataOutputStream(bytes);
            payload.writeInt(1); payload.writeInt(uid); payload.writeInt(pid); string(payload,startTime);
            payload.writeInt(clientPid); string(payload,clientStartTime);
            DataOutputStream output = new DataOutputStream(process.getOutputStream());
            output.writeInt(bytes.size()+1); output.writeByte(32); output.write(bytes.toByteArray()); output.flush();
            try { result.get(12000,TimeUnit.MILLISECONDS); }
            catch (ExecutionException failure) {
                if (failure.getCause() instanceof IllegalStateException code) throw code;
                throw new IllegalStateException("MEMORY_LEGACY_CLEANUP_UNCONFIRMED");
            }
            catch (TimeoutException failure) { throw new IllegalStateException("MEMORY_LEGACY_CLEANUP_TIMEOUT"); }
            if (!process.waitFor(1000,TimeUnit.MILLISECONDS) || process.exitValue()!=0) throw new IllegalStateException("MEMORY_LEGACY_CLEANUP_UNCONFIRMED");
            errors.get(1000,TimeUnit.MILLISECONDS);
        } finally {
            close(process.getOutputStream());
            if (!process.waitFor(1000,TimeUnit.MILLISECONDS)) process.destroyForcibly();
            close(process.getInputStream()); close(process.getErrorStream()); readers.shutdownNow(); ACTIVE.remove(process);
        }
    }
    private static String processStartTime(String pid) throws Exception {
        String stat = new String(Files.readAllBytes(Paths.get("/proc/"+pid+"/stat")),StandardCharsets.UTF_8);
        String value = stat.substring(stat.lastIndexOf(')')+2).split(" ")[19];
        if (!value.matches("[0-9]{1,24}")) throw new IllegalStateException("MEMORY_CLEANUP_IDENTITY"); return value;
    }
    static boolean cleanupFrames(InputStream stream, int uid, int pid, String startTime, int clientPid, String clientStartTime) throws Exception {
        DataInputStream input = new DataInputStream(stream); JSONObject receipt = null, exit = null; int total = 0;
        for (;;) {
            int first = input.read(); if (first < 0) break;
            int length = first << 24 | input.readUnsignedByte() << 16 | input.readUnsignedByte() << 8 | input.readUnsignedByte();
            if (length < 1 || length > MAX_FRAME || total + length > MAX_TOTAL || exit != null) throw new IllegalStateException("MEMORY_CLEANUP_PROTOCOL");
            total += length; int type = input.readUnsignedByte(); byte[] body = new byte[length-1]; input.readFully(body);
            JSONObject value = new JSONObject(new String(body,StandardCharsets.UTF_8));
            if (type == 21) throw new IllegalStateException(nativeCode(value,"MEMORY_CLEANUP_"));
            if (type == 24 && receipt == null) receipt = value;
            else if (type == 20) exit = value;
            else throw new IllegalStateException("MEMORY_CLEANUP_PROTOCOL");
        }
        if (receipt == null || exit == null || !receipt.getBoolean("ok") || receipt.getInt("appUid") != uid || receipt.getInt("mainPid") != pid
            || !receipt.getString("mainStartTime").equals(startTime) || receipt.getInt("remainingAppUidProcesses") != 0
            || receipt.getInt("remainingNonJava") != 0 || !receipt.getBoolean("preservedOnlyMainJava")
            || receipt.getInt("preservedTransportPid") != clientPid
            || !receipt.getString("preservedTransportStartTime").equals(clientStartTime)
            || !receipt.getString("rootProcessCoverage").equals("host-registry-required") || !exit.getBoolean("descendantsReaped")
            || exit.getBoolean("namespaceCreated") || exit.getInt("exitCode") != 0) throw new IllegalStateException("MEMORY_LEGACY_CLEANUP_UNCONFIRMED");
        return true;
    }
    static byte[] startFrame(String files, String workspaceId, String node, String script, int uid) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream payload = new DataOutputStream(bytes);
        for (int value : new int[]{1,uid,15000,0,80,24,0}) payload.writeInt(value);
        for (String value : new String[]{files,workspaceId,files + "/workspace",node}) string(payload,value);
        payload.writeInt(3); for (String value : new String[]{node,"-e",script}) string(payload,value);
        payload.writeInt(1); string(payload,"PATH=/system/bin");
        ByteArrayOutputStream frame = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(frame);
        output.writeInt(bytes.size()+1); output.writeByte(1); output.write(bytes.toByteArray()); return frame.toByteArray();
    }
    private static void string(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); output.writeInt(bytes.length); output.write(bytes);
    }
    static JSONObject readFrames(InputStream stream, int uid) throws Exception {
        DataInputStream input = new DataInputStream(stream); JSONObject ready = null, exit = null;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream(); int total = 0; String error = null;
        for (;;) {
            int first = input.read(); if (first < 0) break;
            int length = first << 24 | input.readUnsignedByte() << 16 | input.readUnsignedByte() << 8 | input.readUnsignedByte();
            if (length < 1 || length > MAX_FRAME || total + length > MAX_TOTAL) throw new IllegalStateException("MEMORY_EXECUTION_FRAME_LIMIT");
            total += length; int type = input.readUnsignedByte(); byte[] body = new byte[length - 1]; input.readFully(body);
            if (exit != null) throw new IllegalStateException("MEMORY_EXECUTION_PROTOCOL_INVALID");
            switch (type) {
                case 16:
                    if (ready != null) throw new IllegalStateException("MEMORY_EXECUTION_PROTOCOL_INVALID");
                    ready = new JSONObject(new String(body, StandardCharsets.UTF_8)); validateReady(ready,uid); break;
                case 17: if (ready == null || stdout.size()+body.length > 4096) throw new IllegalStateException("MEMORY_EXECUTION_OUTPUT_INVALID"); stdout.write(body); break;
                case 18: if (body.length > 8192) throw new IllegalStateException("MEMORY_EXECUTION_OUTPUT_INVALID"); break;
                case 20: exit = new JSONObject(new String(body, StandardCharsets.UTF_8)); break;
                case 21: error = nativeCode(new JSONObject(new String(body,StandardCharsets.UTF_8)),"MEMORY_EXECUTION_"); break;
                default: throw new IllegalStateException("MEMORY_EXECUTION_PROTOCOL_INVALID");
            }
        }
        if (error != null) throw new IllegalStateException(error);
        if (ready == null || exit == null || !exit.getBoolean("descendantsReaped") || !exit.getBoolean("namespaceCreated")
            || exit.getInt("exitCode") != 0 || exit.getInt("signal") != 0 || exit.getBoolean("timedOut") || exit.getBoolean("cancelled"))
            throw new IllegalStateException("MEMORY_EXECUTION_EXIT_UNCONFIRMED");
        if (!stdout.toString("UTF-8").equals("DROY_NATIVE_QUALIFIED_V1\n")) throw new IllegalStateException("MEMORY_EXECUTION_QUALIFICATION_FAILED");
        return new JSONObject().put("ready",ready).put("exit",exit).put("probe","DROY_NATIVE_QUALIFIED_V1");
    }
    private static String nativeCode(JSONObject value, String prefix) {
        String code = value.optString("code");
        return code.matches("[A-Za-z0-9_:-]{1,120}") ? prefix + code.replace(':','_').replace('-','_').toUpperCase(Locale.ROOT) : prefix + "QUALIFICATION_FAILED";
    }
    static void validateReady(JSONObject ready, int uid) throws Exception {
        int guest=ready.getInt("uid"),base=(uid/100000)*100000;
        if (!ready.getBoolean("verified") || guest<base+80000 || guest>base+80007 || ready.getInt("gid") != uid
            || ready.getInt("workloadPid") <= 1 || ready.getInt("topLevelPid") <= 1 || ready.getInt("pid")!=ready.getInt("topLevelPid")
            || ready.getBoolean("initDumpable") || ready.getBoolean("pidNamespace") || ready.getBoolean("ipcNamespace")
            || !ready.getString("processIsolation").equals("dedicated-uid") || !ready.getString("procIsolation").equals("private-hidepid-subset")
            || ready.getInt("processLimit")!=128 || ready.getInt("guestProxyPort") != 0) throw new IllegalStateException("MEMORY_EXECUTION_NOT_ISOLATED");
        for (String field : BOUNDARIES) if (!ready.getBoolean(field)) throw new IllegalStateException("MEMORY_EXECUTION_NOT_ISOLATED");
    }
    private static byte[] bounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
        while ((count = input.read(buffer)) != -1) { if (output.size()+count > limit) throw new IOException("bounded_output_limit"); output.write(buffer,0,count); }
        return output.toByteArray();
    }
    private static String shellQuote(String path) { return "'" + path.replace("'", "'\\''") + "'"; }
    private static void close(Closeable stream) { try { stream.close(); } catch (IOException ignored) { } }
}
