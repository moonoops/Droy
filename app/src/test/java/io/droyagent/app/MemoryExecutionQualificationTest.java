package io.droyagent.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MemoryExecutionQualificationTest {
    private JSONObject ready() throws Exception {
        JSONObject value = new JSONObject().put("verified",true).put("uid",80000).put("gid",10123)
            .put("workloadPid",877).put("pid",876).put("topLevelPid",876).put("initDumpable",false).put("guestProxyPort",0)
            .put("pidNamespace",false).put("ipcNamespace",false).put("processIsolation","dedicated-uid").put("procIsolation","private-hidepid-subset").put("processLimit",128);
        for (String field : new String[]{"mountNamespace","netNamespace","utsNamespace","rootFilesystemPrivate","capabilitiesEmpty","noNewPrivs","inheritedHostFdsClosed","seccomp","ipcSyscallsDenied","uidLease","descendantsManaged"}) value.put(field,true);
        return value;
    }
    private JSONObject exit() throws Exception { return new JSONObject().put("descendantsReaped",true).put("namespaceCreated",true).put("exitCode",0).put("signal",0).put("timedOut",false).put("cancelled",false); }
    private byte[] frames(Object... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(out);
        for (int i=0;i<parts.length;i+=2) { byte[] body=parts[i+1].toString().getBytes(StandardCharsets.UTF_8); data.writeInt(body.length+1); data.writeByte((int)parts[i]); data.write(body); }
        return out.toByteArray();
    }
    @Test public void qualificationRequiresVerifiedBoundaryOutputAndCompleteExit() throws Exception {
        byte[] valid=frames(16,ready(),17,"DROY_NATIVE_QUALIFIED_V1\n",20,exit());
        assertEquals("DROY_NATIVE_QUALIFIED_V1",MemoryExecutionQualification.readFrames(new ByteArrayInputStream(valid),10123).getString("probe"));
        for (byte[] invalid : new byte[][]{frames(16,ready()),frames(16,ready(),17,"unexpected",20,exit()),frames(16,ready(),17,"DROY_NATIVE_QUALIFIED_V1\n",20,exit().put("descendantsReaped",false)),frames(16,ready(),17,"DROY_NATIVE_QUALIFIED_V1\n",20,exit(),17,"late")}) {
            try { MemoryExecutionQualification.readFrames(new ByteArrayInputStream(invalid),10123); fail(); } catch (IllegalStateException expected) { }
        }
    }
    @Test public void nativeAppUidAndMissingIsolationNeverQualify() throws Exception {
        for (JSONObject invalid : new JSONObject[]{ready().put("uid",10123),ready().put("uid",65534),ready().put("uid",80008),ready().put("netNamespace",false),ready().put("ipcSyscallsDenied",false),ready().put("uidLease",false),ready().put("initDumpable",true),ready().put("guestProxyPort",8888)}) {
            try { MemoryExecutionQualification.validateReady(invalid,10123); fail(); } catch (IllegalStateException expected) { assertEquals("MEMORY_EXECUTION_NOT_ISOLATED",expected.getMessage()); }
        }
    }
    @Test public void cleanupRequiresExactMainIdentityZeroSurvivorsAndTransportEnd() throws Exception {
        JSONObject receipt=new JSONObject().put("ok",true).put("appUid",10123).put("mainPid",77).put("mainStartTime","1234")
            .put("remainingAppUidProcesses",0).put("remainingNonJava",0).put("preservedOnlyMainJava",true).put("preservedTransportPid",88).put("preservedTransportStartTime","1235").put("rootProcessCoverage","host-registry-required");
        JSONObject exit=exit().put("namespaceCreated",false);
        assertTrue(MemoryExecutionQualification.cleanupFrames(new ByteArrayInputStream(frames(24,receipt,20,exit)),10123,77,"1234",88,"1235"));
        for (JSONObject invalid:new JSONObject[]{new JSONObject(receipt.toString()).put("mainStartTime","1235"),new JSONObject(receipt.toString()).put("remainingAppUidProcesses",1)}) {
            try { MemoryExecutionQualification.cleanupFrames(new ByteArrayInputStream(frames(24,invalid,20,exit)),10123,77,"1234",88,"1235"); fail(); } catch (IllegalStateException expected) { }
        }
    }
    @Test public void startFrameUsesBoundedPrivateWorkspaceAndNoProxy() throws Exception {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(MemoryExecutionQualification.startFrame("/data/user/0/io.droyagent.app/files","0".repeat(64),"/data/app/example/lib/arm64/libdroy_node.so","process.exit(0)",10123)));
        assertTrue(in.readInt()>1); assertEquals(1,in.readUnsignedByte()); assertEquals(1,in.readInt()); assertEquals(10123,in.readInt());
        assertEquals(15000,in.readInt()); assertEquals(0,in.readInt()); assertEquals(80,in.readInt()); assertEquals(24,in.readInt()); assertEquals(0,in.readInt());
    }
}
