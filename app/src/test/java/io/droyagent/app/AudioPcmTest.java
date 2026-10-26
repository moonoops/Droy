package io.droyagent.app;

import org.junit.Test;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class AudioPcmTest {
    @Test public void independentCursorsReadTheSameSamplesBeforeCaptureEnds() {
        PcmFrameBuffer buffer = new PcmFrameBuffer(16, 2);
        buffer.append(new byte[]{1,2,3,4,5,6}, 6);
        PcmFrameBuffer.Read first = buffer.read(0, 4);
        assertArrayEquals(new byte[]{1,2,3,4}, first.data);
        assertFalse(first.discontinuity);
        assertArrayEquals(first.data, buffer.read(0, 4).data);
        assertArrayEquals(new byte[]{5,6}, buffer.read(first.nextCursor, 8).data);
        assertEquals(6, buffer.written());
    }

    @Test public void slowConsumerGetsAnExplicitGapAndCurrentAlignedData() {
        PcmFrameBuffer buffer = new PcmFrameBuffer(8, 2);
        buffer.append(new byte[]{0,1,2,3,4,5}, 6);
        buffer.append(new byte[]{6,7,8,9,10,11}, 6);
        PcmFrameBuffer.Read gap = buffer.read(0, 8);
        assertEquals(4, gap.cursor);
        assertEquals(12, gap.nextCursor);
        assertTrue(gap.discontinuity);
        assertArrayEquals(new byte[]{4,5,6,7,8,9,10,11}, gap.data);
        assertEquals(0, buffer.read(12, 8).data.length);
    }

    @Test public void aBlockLargerThanTheWindowRetainsOnlyItsLatestSamples() {
        PcmFrameBuffer buffer = new PcmFrameBuffer(4, 2);
        buffer.append(new byte[]{0,1,2,3,4,5,6,7}, 8);
        assertArrayEquals(new byte[]{4,5,6,7}, buffer.read(0, 4).data);
        assertThrows(IllegalArgumentException.class, () -> buffer.read(1, 2));
        assertThrows(IllegalArgumentException.class, () -> buffer.read(10, 2));
        assertThrows(IllegalArgumentException.class, () -> buffer.append(new byte[]{1}, 1));
    }

    @Test public void stoppingFinalizesAPlayablePcmWav() throws Exception {
        File directory = Files.createTempDirectory("audio-wav-test").toFile();
        File target = new File(directory, "recording.wav");
        try {
            try (PcmWavFile wav = new PcmWavFile(target, 48000, 1)) { wav.append(new byte[]{1,2,3,4}); }
            byte[] result = Files.readAllBytes(target.toPath());
            ByteBuffer header = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(48, result.length);
            assertEquals("RIFF", new String(result, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(40, header.getInt(4));
            assertEquals(48000, header.getInt(24));
            assertEquals(4, header.getInt(40));
            assertArrayEquals(new byte[]{1,2,3,4}, java.util.Arrays.copyOfRange(result, 44, 48));
        } finally { target.delete(); directory.delete(); }
    }

    @Test public void interruptedWavRecoversCommittedSamplesAndTrimsHalfSample() throws Exception {
        File directory = Files.createTempDirectory("audio-recovery-test").toFile();
        File target = new File(directory, "partial.wav");
        try {
            try (PcmWavFile wav = new PcmWavFile(target, 16000, 1)) { wav.append(new byte[]{1,2}); }
            try (RandomAccessFile interrupted = new RandomAccessFile(target, "rw")) {
                interrupted.seek(interrupted.length()); interrupted.write(new byte[]{3,4,5});
                interrupted.seek(40); interrupted.writeInt(0);
            }
            assertEquals(4, PcmWavFile.recover(target, 16000, 1));
            byte[] result = Files.readAllBytes(target.toPath());
            assertEquals(48, result.length);
            assertEquals(4, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(40));
        } finally { target.delete(); directory.delete(); }
    }

    @Test public void queuedWriterDrainsBeforeReportingFileCompletion() throws Exception {
        File directory = Files.createTempDirectory("audio-writer-test").toFile();
        File target = new File(directory, "recording.wav");
        try {
            QueuedWavWriter writer = new QueuedWavWriter(target, 16000, 1);
            for (int i=0; i<20; i++) assertTrue(writer.append(new byte[]{1,2,3,4}));
            writer.finish().get(3, TimeUnit.SECONDS);
            assertFalse(writer.append(new byte[]{1,2}));
            byte[] result = Files.readAllBytes(target.toPath());
            assertEquals(124, result.length);
            assertEquals(80, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(40));
        } finally { target.delete(); directory.delete(); }
    }
}
