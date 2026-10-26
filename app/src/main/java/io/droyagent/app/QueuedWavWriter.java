package io.droyagent.app;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Disk latency must not stall AudioRecord. A full queue fails this writer explicitly. */
final class QueuedWavWriter {
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(128);
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private volatile boolean inputClosed;
    private volatile IOException failure;

    QueuedWavWriter(File file, int sampleRate, int channels) throws IOException {
        PcmWavFile output = new PcmWavFile(file, sampleRate, channels);
        Thread worker = new Thread(() -> {
            IOException error = null;
            try {
                while (!inputClosed || !queue.isEmpty()) {
                    byte[] chunk = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (chunk != null) output.append(chunk);
                }
            } catch (IOException e) { error = e; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); error = new IOException("WAV writer interrupted", e); }
            finally {
                try { output.close(); } catch (IOException e) { if (error == null) error = e; else error.addSuppressed(e); }
                if (error == null) error = failure;
                failure = error;
                if (error == null) completion.complete(null); else completion.completeExceptionally(error);
            }
        }, "droy-audio-file");
        worker.start();
    }

    boolean append(byte[] data) {
        if (inputClosed || failure != null) return false;
        if (queue.offer(data)) return true;
        failure = new IOException("Recording storage cannot keep up"); inputClosed = true; return false;
    }

    CompletableFuture<Void> finish() { inputClosed = true; return completion; }
}
