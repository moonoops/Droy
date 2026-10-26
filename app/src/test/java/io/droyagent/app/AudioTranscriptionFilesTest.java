package io.droyagent.app;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import static org.junit.Assert.*;

public class AudioTranscriptionFilesTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void acceptsAppFileAndRejectsExternalOrSymlink() throws Exception {
        File files=temporary.newFolder("files"),cache=temporary.newFolder("cache");
        File wave=new File(files,"sound.wav"); Files.write(wave.toPath(),new byte[4]);
        assertEquals(wave.getCanonicalFile(),AudioTranscription.controlledFile(files,cache,wave.getAbsolutePath()));
        File outside=temporary.newFile("outside.wav");
        try { AudioTranscription.controlledFile(files,cache,outside.getAbsolutePath()); fail(); } catch(IOException expected) { }
        Path link=files.toPath().resolve("link.wav"); Files.createSymbolicLink(link,wave.toPath());
        try { AudioTranscription.controlledFile(files,cache,link.toString()); fail(); } catch(IOException expected) { }
    }
    @Test public void rejectsTraversalAndRelativePath() throws Exception {
        File files=temporary.newFolder("files"),cache=temporary.newFolder("cache");
        File outside=temporary.newFile("outside.wav");
        for(String value:new String[]{"sound.wav",new File(files,"../outside.wav").getAbsolutePath()}) {
            try { AudioTranscription.controlledFile(files,cache,value); fail(); } catch(IOException expected) { }
        }
    }
    @Test public void readsPcmWavWithUnknownChunksWithoutLoadingWholeFile() throws Exception {
        File file=temporary.newFile("test.wav");
        try(RandomAccessFile out=new RandomAccessFile(file,"rw")) {
            out.writeBytes("RIFF"); out.writeInt(Integer.reverseBytes(52)); out.writeBytes("WAVE");
            out.writeBytes("JUNK"); out.writeInt(Integer.reverseBytes(3)); out.write(new byte[4]);
            out.writeBytes("fmt "); out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short)1)); out.writeShort(Short.reverseBytes((short)1));
            out.writeInt(Integer.reverseBytes(16000)); out.writeInt(Integer.reverseBytes(32000));
            out.writeShort(Short.reverseBytes((short)2)); out.writeShort(Short.reverseBytes((short)16));
            out.writeBytes("data"); out.writeInt(Integer.reverseBytes(4)); out.write(new byte[]{0,64,0,-64});
        }
        final int[] count={0};
        AudioTranscriptionDecoder.decode(file,(samples,rate)-> {
            assertEquals(16000,rate); assertArrayEquals(new float[]{.5f,-.5f},samples,.00001f); count[0]+=samples.length;
        },()->{});
        assertEquals(2,count[0]);
    }
}
