package io.droyagent.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** A recoverable PCM WAV: the header can always be rebuilt from committed file length. */
final class PcmWavFile implements AutoCloseable {
    static final long MAX_DATA_BYTES = 0xffff_ffffL - 36;
    private final RandomAccessFile file;
    private final int sampleRate, channels;
    private long bytes;
    private boolean closed;

    PcmWavFile(File target, int sampleRate, int channels) throws IOException {
        if (sampleRate < 1 || channels < 1) throw new IllegalArgumentException("Invalid PCM format");
        this.sampleRate = sampleRate; this.channels = channels;
        file = new RandomAccessFile(target, "rw");
        file.setLength(44); header(file, 0, sampleRate, channels); file.seek(44);
    }

    void append(byte[] input) throws IOException {
        if (closed) throw new IOException("WAV is closed");
        if (input.length % (channels * 2) != 0 || bytes + input.length > MAX_DATA_BYTES)
            throw new IOException("WAV capacity or sample alignment exceeded");
        file.write(input); bytes += input.length;
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { header(file, bytes, sampleRate, channels); file.getFD().sync(); }
        finally { file.close(); }
    }

    static long recover(File target, int sampleRate, int channels) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(target, "rw")) {
            if (file.length() < 44) throw new IOException("Incomplete WAV header");
            long bytes = file.length() - 44;
            bytes -= bytes % (channels * 2L);
            if (bytes > MAX_DATA_BYTES) throw new IOException("WAV exceeds capacity");
            file.setLength(44 + bytes); header(file, bytes, sampleRate, channels); file.getFD().sync();
            return bytes;
        }
    }

    private static void header(RandomAccessFile file, long bytes, int sampleRate, int channels) throws IOException {
        file.seek(0); file.writeBytes("RIFF"); le32(file, bytes + 36); file.writeBytes("WAVEfmt ");
        le32(file, 16); le16(file, 1); le16(file, channels); le32(file, sampleRate);
        le32(file, sampleRate * channels * 2L); le16(file, channels * 2); le16(file, 16);
        file.writeBytes("data"); le32(file, bytes);
    }

    private static void le16(RandomAccessFile file, int value) throws IOException {
        file.write(value & 255); file.write((value >>> 8) & 255);
    }
    private static void le32(RandomAccessFile file, long value) throws IOException {
        file.write((int) value & 255); file.write((int) (value >>> 8) & 255);
        file.write((int) (value >>> 16) & 255); file.write((int) (value >>> 24) & 255);
    }
}
