package io.droyagent.app;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Lives in the test APK UID and produces only a synthetic 1 kHz media tone. */
public final class AudioPlaybackFixtureActivity extends Activity {
    private volatile boolean running;
    private AudioTrack track;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        configure(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); configure(intent);
    }

    private void configure(Intent intent) {
        stopTone();
        if (intent.getBooleanExtra("stop", false)) { finish(); return; }
        boolean allowed = !intent.getBooleanExtra("denyCapture", false);
        int policy = allowed ? AudioAttributes.ALLOW_CAPTURE_BY_ALL : AudioAttributes.ALLOW_CAPTURE_BY_NONE;
        getSystemService(AudioManager.class).setAllowedCapturePolicy(policy);
        TextView text = new TextView(this); text.setTextSize(20); text.setPadding(24, 60, 24, 24);
        text.setText("受控音频测试\n独立测试 UID：" + android.os.Process.myUid()
            + "\n固定 1 kHz 音调\n允许内录：" + allowed + "\n不播放任何私人内容");
        setContentView(text);
        String speech = intent.getStringExtra("speechBase64");
        short[] samples;
        int sampleRate;
        if (speech == null || speech.isEmpty()) {
            sampleRate = 48000; samples = new short[960];
            for (int i = 0; i < samples.length; i++) samples[i] = (short) Math.round(6000 * Math.sin(i * Math.PI * 2 / 48));
        } else {
            byte[] wav = Base64.decode(speech, Base64.DEFAULT);
            ByteBuffer bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
            if (wav.length < 44 || !new String(wav, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                || bytes.getShort(20) != 1 || bytes.getShort(22) != 1 || bytes.getShort(34) != 16)
                throw new IllegalArgumentException("Speech fixture must be mono PCM16 WAV");
            sampleRate = bytes.getInt(24); int offset = 12, dataStart = -1, dataLength = 0;
            while (offset + 8 <= wav.length) {
                int length = bytes.getInt(offset + 4);
                if (length < 0 || offset + 8L + length > wav.length) throw new IllegalArgumentException("Invalid WAV chunk");
                if (new String(wav, offset, 4, StandardCharsets.US_ASCII).equals("data")) { dataStart = offset + 8; dataLength = length; break; }
                offset += 8 + length + (length & 1);
            }
            if (dataStart < 0 || dataLength < 2 || dataLength % 2 != 0) throw new IllegalArgumentException("WAV has no aligned samples");
            samples = new short[dataLength / 2];
            bytes.position(dataStart); for (int i = 0; i < samples.length; i++) samples[i] = bytes.getShort();
        }
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(policy).build();
        AudioFormat format = new AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        AudioTrack output = new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(19200).build();
        track = output; running = true; output.play();
        new Thread(() -> {
            int offset = 0;
            try {
                while (running && track == output) {
                    int written = output.write(samples, offset, Math.min(960, samples.length - offset), AudioTrack.WRITE_BLOCKING);
                    if (written < 0) break;
                    offset = (offset + written) % samples.length;
                }
            } catch (IllegalStateException stopped) { /* The fixture was closed while its blocking write returned. */ }
        }, "audio-fixture-tone").start();
    }

    private void stopTone() {
        running = false;
        AudioTrack old = track; track = null;
        if (old != null) { old.pause(); old.flush(); old.release(); }
    }

    @Override protected void onDestroy() { stopTone(); super.onDestroy(); }
}
