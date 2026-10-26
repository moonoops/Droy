package io.droyagent.app;

/** PCM calculations shared by the native meter and playback boundary. */
final class VoicePcm {
    static double level(byte[] bytes) {
        if ((bytes.length & 1) != 0) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("PCM16 数据未对齐", "PCM16 data is not aligned"));
        double energy = 0;
        for (int index = 0; index < bytes.length; index += 2) {
            double value = (short) ((bytes[index] & 255) | (bytes[index + 1] << 8)) / 32768d;
            energy += value * value;
        }
        return bytes.length == 0 ? 0 : Math.sqrt(energy / (bytes.length / 2));
    }

    static void requirePlayback(byte[] bytes, int rate) {
        if (rate < 8000 || rate > 96000 || bytes.length == 0 || (bytes.length & 1) != 0 || bytes.length > rate * 4)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("语音播放需要两秒以内的单声道 PCM16 数据块", "Speech playback requires mono PCM16 chunks of at most two seconds"));
    }
}
