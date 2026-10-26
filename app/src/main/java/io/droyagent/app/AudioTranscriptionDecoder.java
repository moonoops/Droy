package io.droyagent.app;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.*;
import java.nio.*;

final class AudioTranscriptionDecoder {
    interface Frames { void accept(float[] mono, int rate) throws Exception; }
    interface Check { void check() throws Exception; }

    static void decode(File file, Frames frames, Check check) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() >= 12 && input.readInt() == 0x52494646) {
                input.skipBytes(4);
                if (input.readInt() != 0x57415645) throw new IOException(io.droyagent.app.NativeLocale.text("不支持的 RIFF 内容", "Unsupported RIFF content"));
                decodeWav(input, frames, check); return;
            }
        }
        decodeCompressed(file, frames, check);
    }

    private static void decodeWav(RandomAccessFile input, Frames frames, Check check) throws Exception {
        int channels = 0, rate = 0, bits = 0, format = 0;
        while (input.getFilePointer() + 8 <= input.length()) {
            check.check(); int chunk = input.readInt();
            long size = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
            long start = input.getFilePointer();
            if (size > input.length() - start) throw new IOException(io.droyagent.app.NativeLocale.text("WAV 数据不完整", "WAV data is incomplete"));
            if (chunk == 0x666d7420) {
                if (size < 16) throw new IOException(io.droyagent.app.NativeLocale.text("WAV 格式头无效", "Invalid WAV format header"));
                format = Short.toUnsignedInt(Short.reverseBytes(input.readShort()));
                channels = Short.toUnsignedInt(Short.reverseBytes(input.readShort()));
                rate = Integer.reverseBytes(input.readInt()); input.skipBytes(6);
                bits = Short.toUnsignedInt(Short.reverseBytes(input.readShort()));
            } else if (chunk == 0x64617461) {
                if ((format != 1 || bits != 16) && (format != 3 || bits != 32))
                    throw new IOException(io.droyagent.app.NativeLocale.text("WAV 需使用 PCM16 或 float32", "WAV requires PCM16 or float32"));
                if (channels < 1 || channels > 8 || rate < 8000 || rate > 192000)
                    throw new IOException(io.droyagent.app.NativeLocale.text("WAV 采样格式不支持", "Unsupported WAV sample format"));
                int align = channels * bits / 8;
                if (size % align != 0) throw new IOException(io.droyagent.app.NativeLocale.text("WAV 数据帧未对齐", "WAV data frames are not aligned"));
                byte[] bytes = new byte[4096 * align];
                long left = size;
                while (left > 0) {
                    check.check(); int count = (int) Math.min(bytes.length, left);
                    input.readFully(bytes,0,count); left -= count;
                    frames.accept(mono(ByteBuffer.wrap(bytes,0,count).order(ByteOrder.LITTLE_ENDIAN), channels,
                        bits == 16 ? AudioFormat.ENCODING_PCM_16BIT : AudioFormat.ENCODING_PCM_FLOAT), rate);
                }
                return;
            }
            input.seek(start + size + (size & 1));
        }
        throw new IOException(io.droyagent.app.NativeLocale.text("WAV 没有音频数据", "WAV contains no audio data"));
    }

    private static void decodeCompressed(File file, Frames frames, Check check) throws Exception {
        MediaExtractor extractor = new MediaExtractor(); MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            MediaFormat selected = null;
            for (int i=0;i<extractor.getTrackCount();i++) {
                MediaFormat track = extractor.getTrackFormat(i);
                String mime = track.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { selected=track; extractor.selectTrack(i); break; }
            }
            if (selected == null) throw new IOException(io.droyagent.app.NativeLocale.text("文件没有可解码的音轨", "The file contains no decodable audio track"));
            codec = MediaCodec.createDecoderByType(selected.getString(MediaFormat.KEY_MIME));
            codec.configure(selected,null,null,0); codec.start();
            MediaFormat output = selected; MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnd = false, outputEnd = false;
            while (!outputEnd) {
                check.check();
                if (!inputEnd) {
                    int index = codec.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index); buffer.clear();
                        int length = extractor.readSampleData(buffer,0);
                        if (length < 0) { codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd=true; }
                        else { codec.queueInputBuffer(index,0,length,extractor.getSampleTime(),0); extractor.advance(); }
                    }
                }
                int index = codec.dequeueOutputBuffer(info,10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) output=codec.getOutputFormat();
                else if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            ByteBuffer buffer = codec.getOutputBuffer(index).duplicate().order(ByteOrder.LITTLE_ENDIAN);
                            buffer.position(info.offset); buffer.limit(info.offset+info.size);
                            int encoding = output.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                ? output.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                            frames.accept(mono(buffer,output.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding),
                                output.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                        }
                    } finally { codec.releaseOutputBuffer(index,false); }
                    outputEnd=(info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                }
            }
        } finally { if (codec != null) codec.release(); extractor.release(); }
    }

    private static float[] mono(ByteBuffer bytes, int channels, int encoding) throws IOException {
        int width = encoding == AudioFormat.ENCODING_PCM_16BIT ? 2 : encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 0;
        if (width == 0 || channels < 1 || channels > 8 || bytes.remaining() % (width*channels) != 0)
            throw new IOException(io.droyagent.app.NativeLocale.text("解码器 PCM 格式不支持", "Unsupported decoder PCM format"));
        float[] mono = new float[bytes.remaining()/(width*channels)];
        for (int i=0;i<mono.length;i++) {
            float sum=0;
            for (int j=0;j<channels;j++) sum += width == 2 ? bytes.getShort()/32768f : bytes.getFloat();
            mono[i]=sum/channels;
        }
        return mono;
    }
}
