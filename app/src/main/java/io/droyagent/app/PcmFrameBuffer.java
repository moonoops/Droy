package io.droyagent.app;

/** A sample-aligned rolling window. Each consumer keeps its own absolute byte cursor. */
final class PcmFrameBuffer {
    static final class Read {
        final byte[] data;
        final long cursor, nextCursor;
        final boolean discontinuity;
        Read(byte[] data, long cursor, boolean discontinuity) {
            this.data = data; this.cursor = cursor; this.nextCursor = cursor + data.length;
            this.discontinuity = discontinuity;
        }
    }

    private final byte[] bytes;
    private final int alignment;
    private long written;

    PcmFrameBuffer(int capacity, int alignment) {
        if (alignment < 1 || capacity < alignment || capacity % alignment != 0)
            throw new IllegalArgumentException("PCM buffer must be sample aligned");
        this.bytes = new byte[capacity]; this.alignment = alignment;
    }

    synchronized void append(byte[] input, int count) {
        if (count < 0 || count > input.length || count % alignment != 0)
            throw new IllegalArgumentException("Invalid PCM block length");
        int keep = Math.min(count, bytes.length), offset = count - keep;
        long start = written + offset;
        for (int i = 0; i < keep;) {
            int index = (int) ((start + i) % bytes.length);
            int copy = Math.min(keep - i, bytes.length - index);
            System.arraycopy(input, offset + i, bytes, index, copy); i += copy;
        }
        written += count;
    }

    synchronized Read read(long cursor, int maximum) {
        if (cursor < 0 || cursor % alignment != 0 || maximum < alignment || maximum % alignment != 0)
            throw new IllegalArgumentException("Invalid PCM cursor or limit");
        if (cursor > written) throw new IllegalArgumentException("PCM cursor is ahead of capture");
        long earliest = Math.max(0, written - bytes.length);
        long start = Math.max(cursor, earliest);
        int size = (int) Math.min(maximum, written - start);
        byte[] result = new byte[size];
        for (int i = 0; i < size;) {
            int index = (int) ((start + i) % bytes.length);
            int copy = Math.min(size - i, bytes.length - index);
            System.arraycopy(bytes, index, result, i, copy); i += copy;
        }
        return new Read(result, start, start != cursor);
    }

    synchronized long written() { return written; }
}
