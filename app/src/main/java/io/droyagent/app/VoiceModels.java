package io.droyagent.app;

import android.content.Context;
import android.icu.text.Transliterator;
import com.k2fsa.sherpa.onnx.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Pinned release models are bundled separately and unpacked only when requested. */
final class VoiceModels {
    private static final String VERSION = "kws-20251220-zh-en-v2";
    private static final String[] NAMES = {"encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "en.phone"};
    private static final long[] SIZES = {4600657, 759829, 86629, 1928, 3330061};
    private static final String[] HASHES = {
        "2ca84d6bfe73e1ea3c9c49f600f7cad1c9ddd423c53c906b8bfe802444dd78d5",
        "63a22dd60f40fff082ac3e09afa507f6787da36df76ded2fbe145fa233e22c21",
        "190d4067b4cc20b72a42a1916e69d92052000fb7051a427ebb1bc72a69207dc1",
        "2d3f32311f9b692b964da3c90e830258d3e78e013cb0c992dbfb15cd5a1a71b0",
        "f7000ec3a90544c0c7c16090d8951779c2b322e14dad5006290f498567d439ea"
    };
    private final Context context;
    private final File directory;
    private volatile String state = "not_prepared", stage = "not_prepared", error;
    private volatile long completed;
    private volatile boolean cancelled, preparationRunning;
    private Runnable prepareAfterCancellation;

    VoiceModels(Context context) {
        this.context = context;
        directory = new File(context.getFilesDir(), "audio-models/voice-wake");
        if (filesPresent() && markerMatches()) { state = "ready"; stage = "ready"; completed = totalBytes(); }
    }

    boolean ready() { return state.equals("ready"); }
    boolean preparing() { return preparationRunning; }

    synchronized JSONObject status() throws Exception {
        return new JSONObject().put("state", state).put("ready", ready()).put("stage", stage)
            .put("downloadedBytes", completed).put("totalBytes", totalBytes())
            .put("modelName", io.droyagent.app.NativeLocale.text("中英文关键词唤醒", "Chinese and English keyword wake-up")).put("error", error == null ? JSONObject.NULL : error);
    }

    synchronized void prepare(Runnable finished) {
        if (preparing()) {
            // A quick off/on waits for the cancelled worker to finish touching the model files.
            if (cancelled) prepareAfterCancellation = finished;
            return;
        }
        if (ready()) { finished.run(); return; }
        preparationRunning = true;
        state = "preparing"; stage = "extract"; completed = 0; error = null; cancelled = false;
        Thread thread = new Thread(() -> {
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException(io.droyagent.app.NativeLocale.text("无法创建唤醒资源目录", "Unable to create wake-word resources directory"));
                Set<String> copied = new HashSet<>();
                try (ZipInputStream archive = new ZipInputStream(context.getAssets().open("voice-models.zip"))) {
                    ZipEntry entry;
                    while ((entry = archive.getNextEntry()) != null) {
                        check(); int index = Arrays.asList(NAMES).indexOf(entry.getName());
                        if (index < 0 || entry.isDirectory() || !copied.add(entry.getName()))
                            throw new IOException(io.droyagent.app.NativeLocale.text("唤醒资源包内容无效", "Invalid wake-word resource archive"));
                        File part = new File(directory, NAMES[index] + ".part");
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        long written = 0;
                        try (FileOutputStream output = new FileOutputStream(part)) {
                            byte[] buffer = new byte[131072]; int count;
                            while ((count = archive.read(buffer)) != -1) {
                                check(); written += count;
                                if (written > SIZES[index]) throw new IOException(io.droyagent.app.NativeLocale.text("唤醒资源大小无效", "Invalid wake-word resource size"));
                                output.write(buffer, 0, count); digest.update(buffer, 0, count); completed += count;
                            }
                            output.getFD().sync();
                        }
                        if (written != SIZES[index] || !hex(digest.digest()).equals(HASHES[index]))
                            throw new IOException(io.droyagent.app.NativeLocale.text("唤醒资源校验失败", "Wake-word resource verification failed"));
                        Files.move(part.toPath(), file(NAMES[index]).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                if (copied.size() != NAMES.length) throw new IOException(io.droyagent.app.NativeLocale.text("唤醒资源不完整，请重新安装完整版本", "Wake-word resources are incomplete. Please reinstall the full version"));
                check(); stage = "load";
                KeywordSpotter spotter = createSpotter(false);
                spotter.release();
                check(); Files.write(file(".verified").toPath(), VERSION.getBytes(StandardCharsets.UTF_8));
                completed = totalBytes(); state = "ready"; stage = "ready";
            } catch (FileNotFoundException missing) {
                state = "failed"; stage = "failed"; error = io.droyagent.app.NativeLocale.text("安装包缺少唤醒资源，请安装完整版本", "Wake-word resources are missing. Please install the full version");
            } catch (Exception failure) {
                state = cancelled ? "not_prepared" : "failed"; stage = state;
                error = cancelled ? null : failure.getMessage();
            } catch (LinkageError unavailable) {
                state = "failed"; stage = "failed"; error = io.droyagent.app.NativeLocale.text("本地语音运行时无法加载", "Unable to load local speech runtime");
            } finally {
                for (String name : NAMES) file(name + ".part").delete();
                finishPreparation(finished);
            }
        }, "droy-voice-models");
        thread.start(); RuntimeService.refreshBackgroundPolicy();
    }

    private synchronized void finishPreparation(Runnable finished) {
        preparationRunning = false;
        Runnable next = prepareAfterCancellation;
        prepareAfterCancellation = null;
        if (next != null) prepare(next);
        else finished.run();
        RuntimeService.refreshBackgroundPolicy();
    }

    synchronized void cancel() {
        cancelled = true;
        prepareAfterCancellation = null;
    }

    KeywordSpotter createSpotter() { return createSpotter(true); }

    private KeywordSpotter createSpotter(boolean requireReady) {
        if (requireReady && !ready()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先准备唤醒资源", "Please prepare wake-word resources first"));
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(file("encoder.onnx").getAbsolutePath());
        transducer.setDecoder(file("decoder.onnx").getAbsolutePath());
        transducer.setJoiner(file("joiner.onnx").getAbsolutePath());
        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer); model.setTokens(file("tokens.txt").getAbsolutePath());
        model.setNumThreads(1); model.setProvider("cpu"); model.setModelType("zipformer2");
        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.setFeatConfig(new FeatureConfig(16000, 80, 0f)); config.setModelConfig(model);
        File initialKeywords = file("initial-keywords.txt");
        try { Files.write(initialKeywords.toPath(), "D R OY1 @DROY\n".getBytes(StandardCharsets.UTF_8)); }
        catch (IOException failure) { throw new IllegalStateException(io.droyagent.app.NativeLocale.text("无法准备唤醒词", "Unable to prepare wake word"), failure); }
        config.setKeywordsFile(initialKeywords.getAbsolutePath());
        return new KeywordSpotter(null, config);
    }

    String keywords(String chinese, String english) throws Exception {
        if (!ready()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先准备唤醒资源", "Please prepare wake-word resources first"));
        Set<String> tokens = new HashSet<>();
        for (String line : Files.readAllLines(file("tokens.txt").toPath(), StandardCharsets.UTF_8))
            if (!line.isBlank()) tokens.add(line.trim().split("\\s+")[0]);
        List<List<String>> phrases = new ArrayList<>();
        Set<String> words = new HashSet<>();
        for (String phrase : new String[]{chinese, english}) {
            List<String> segments = keywordSegments(phrase);
            phrases.add(segments);
            for (String segment : segments)
                if (!Character.UnicodeScript.of(segment.codePointAt(0)).equals(Character.UnicodeScript.HAN))
                    words.add(segment.toUpperCase(Locale.ROOT));
        }
        Map<String, String> phones = new HashMap<>();
        // Droy is the product name, absent from the general pronunciation dictionary.
        phones.put("DROY", "D R OY1");
        try (BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(file("en.phone")), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                String[] pair = line.trim().split("\\s+", 2);
                if (pair.length == 2 && words.contains(pair[0]) && !phones.containsKey(pair[0])) phones.put(pair[0], pair[1]);
            }
        }
        List<String> result = new ArrayList<>();
        String[] labels = {chinese, english};
        for (int index = 0; index < phrases.size(); index++) {
            List<String> encoded = new ArrayList<>();
            for (String segment : phrases.get(index)) {
                if (Character.UnicodeScript.of(segment.codePointAt(0)).equals(Character.UnicodeScript.HAN)) {
                    String phonetic = Normalizer.normalize(Transliterator.getInstance("Han-Latin; Lower()")
                        .transliterate(segment), Normalizer.Form.NFC);
                    for (String syllable : phonetic.trim().split("\\s+")) encoded.addAll(splitPhones(syllable, tokens));
                } else {
                    String word = segment.toUpperCase(Locale.ROOT);
                    String value = phones.get(word);
                    if (value == null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("暂不支持英文单词“", "The English word “") + segment + io.droyagent.app.NativeLocale.text("”，请换一个唤醒词或伙伴名称", "” is not supported yet. Please choose another wake word or companion name"));
                    for (String phone : value.split("\\s+")) {
                        if (!tokens.contains(phone)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("该英文唤醒词的发音暂不受支持", "This English wake-word pronunciation is not supported yet"));
                        encoded.add(phone);
                    }
                }
            }
            if (!encoded.isEmpty()) result.add(String.join(" ", encoded) + " @" + labels[index].strip().replaceAll("\\s+", "_"));
        }
        if (result.isEmpty()) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("请先设置一个唤醒词", "Please set a wake word first"));
        return String.join("\n", result);
    }

    static List<String> keywordSegments(String phrase) {
        List<String> result = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\p{IsHan}]+|[a-zA-Z]+(?:['-][a-zA-Z]+)*|\\s+").matcher(phrase);
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("唤醒词或伙伴名称含不支持的符号，请使用中文或英文文字", "The wake word or companion name contains unsupported symbols. Please use Chinese or English letters"));
            String segment = matcher.group();
            if (!segment.isBlank()) result.add(segment);
            end = matcher.end();
        }
        if (end != phrase.length()) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("唤醒词或伙伴名称含不支持的符号，请使用中文或英文文字", "The wake word or companion name contains unsupported symbols. Please use Chinese or English letters"));
        return result;
    }

    static List<String> splitPhones(String syllable, Set<String> tokens) {
        List<String> result = new ArrayList<>();
        for (int start = 0; start < syllable.length();) {
            String found = null;
            for (int end = syllable.length(); end > start; end--)
                if (tokens.contains(syllable.substring(start, end))) { found = syllable.substring(start, end); break; }
            if (found == null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("这个中文唤醒词暂不受支持，请换一个词组", "This Chinese wake word is not supported yet. Please choose another phrase"));
            result.add(found); start += found.length();
        }
        return result;
    }

    private File file(String name) { return new File(directory, name); }
    private boolean markerMatches() {
        try { return new String(Files.readAllBytes(file(".verified").toPath()), StandardCharsets.UTF_8).equals(VERSION); }
        catch (IOException missing) { return false; }
    }
    private boolean filesPresent() {
        for (int i = 0; i < NAMES.length; i++) if (file(NAMES[i]).length() != SIZES[i]) return false;
        return true;
    }
    private void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException(io.droyagent.app.NativeLocale.text("资源准备已取消", "Resource preparation cancelled"));
        if (ProductPermissions.stopped(context) || BackgroundRuntime.explicitlyStopped(context))
            throw new InterruptedIOException(io.droyagent.app.NativeLocale.text("Droy 已停止", "Droy has stopped"));
    }
    private static long totalBytes() { long size = 0; for (long value : SIZES) size += value; return size; }
    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte part : value) result.append(String.format(Locale.ROOT, "%02x", part & 255));
        return result.toString();
    }
}
