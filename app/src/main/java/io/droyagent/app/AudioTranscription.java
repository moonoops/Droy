package io.droyagent.app;

import android.content.Context;
import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Android-local SenseVoice jobs. No microphone, network ASR, or provider fallback is opened here. */
final class AudioTranscription {
    private static AudioTranscription instance;
    static synchronized AudioTranscription get(Context context) {
        if (instance == null) instance = new AudioTranscription(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final File models;
    private final JSONArray assets;
    private final long totalBytes;
    private final String modelStamp;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r,"droy-local-asr"));
    private final LinkedHashMap<String,Job> jobs = new LinkedHashMap<>();
    private final Set<String> cancelledStarts = new LinkedHashSet<>();
    private boolean admissionClosed;
    private volatile Job latest;
    private volatile boolean preparing, preparationCancelled;
    private volatile String preparationState="not_prepared", stage="not_prepared", preparationError;
    private volatile long downloadedBytes;

    private AudioTranscription(Context context) {
        this.context=context; models=new File(context.getFilesDir(),"audio-models/sensevoice-int8");
        try (InputStream input=context.getAssets().open("audio-models.json"); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] block=new byte[4096]; int count;
            while ((count=input.read(block))!=-1) bytes.write(block,0,count);
            JSONObject manifest=new JSONObject(bytes.toString("UTF-8")); assets=manifest.getJSONArray("files");
            long size=0; for(int i=0;i<assets.length();i++) size+=assets.getJSONObject(i).getLong("bytes");
            totalBytes=size;
            modelStamp=hex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            File stamp=new File(models,".verified");
            if (stamp.isFile() && modelStamp.equals(new String(Files.readAllBytes(stamp.toPath()),java.nio.charset.StandardCharsets.UTF_8)) && filesPresent()) {
                preparationState="ready"; stage="ready"; downloadedBytes=totalBytes;
            }
        } catch (Exception invalid) { throw new IllegalStateException(io.droyagent.app.NativeLocale.text("本地语音模型清单无效", "Invalid local speech model manifest"),invalid); }
        // RuntimeService constructs this singleton before starting DSH. A DSH-only
        // restart reuses it, so it cannot erase an in-flight native transcription.
        try { cleanAbandonedTemporaryInputs(); }
        catch (IOException failure) { throw new IllegalStateException(io.droyagent.app.NativeLocale.text("无法清理上次临时语音", "Unable to clean up previous temporary speech files"), failure); }
        initialize();
    }

    synchronized void initialize() {
        if (preparing || preparationState.equals("ready")) return;
        try { prepare(); }
        catch (Exception failure) { preparationState="failed"; stage="failed"; preparationError=safeError(failure); }
    }

    synchronized JSONObject request(JSONObject request,String owner) throws Exception {
        return request(request,owner,UUID.randomUUID().toString());
    }

    synchronized JSONObject request(JSONObject request,String owner,String requestId) throws Exception {
        String operation=request.getString("operation");
        if (owner == null || owner.isBlank()) throw new SecurityException(io.droyagent.app.NativeLocale.text("转录缺少可信调用身份", "Transcription requires a trusted caller identity"));
        switch(operation) {
            case "preparation": return preparationStatus();
            case "cancel_preparation": cancelPreparation(); return preparationStatus();
            case "prepare": checkAllowed(); prepare(); return preparationStatus();
            case "transcribe": return start(request,owner,owner,requestId).json(0);
            case "transcript": {
                Job job=job(request.getString("transcriptId"),owner);
                if(request.optBoolean("statusOnly",false)) return job.summary();
                checkAllowed();
                return job.json(request.optInt("cursor",0),request.optBoolean("full",false));
            }
            case "stop_transcription": {
                Job job=job(request.getString("transcriptId"),owner); job.cancel(); return job.summary();
            }
            default: throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("未知语音操作", "Unknown speech operation"));
        }
    }

    synchronized JSONObject requestFromUi(JSONObject request) throws Exception {
        String operation=request.optString("operation");
        if(operation.equals("transcript") || operation.equals("stop_transcription")) {
            Job selected=jobs.get(request.getString("transcriptId"));
            if(selected==null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("转录不存在", "Transcription does not exist"));
            if(operation.equals("stop_transcription")) { selected.cancel(); return selected.summary(); }
            if(request.optBoolean("statusOnly",false)) return selected.summary();
            return selected.json(request.optInt("cursor",0),request.optBoolean("full",false));
        }
        if(!operation.equals("transcribe") || request.optString("captureId").isEmpty())
            return request(request,"product-user-action");
        JSONObject args=new JSONObject(request.toString()); String id=args.getString("captureId");
        AudioCapture capture=AudioCapture.get(context); String captureOwner=capture.ownerForUi(id);
        String state=capture.status(id,captureOwner).getString("state");
        if(Set.of("completed","interrupted","failed").contains(state)) {
            args.remove("captureId"); args.put("inputPath",capture.fileForUi(id).getAbsolutePath());
        }
        return start(args,"product-user-action",captureOwner,UUID.randomUUID().toString()).json(0);
    }

    synchronized JSONObject uiStatus() throws Exception {
        JSONObject result=preparationStatus();
        if (latest != null && !AudioCapture.isTransientOwner(latest.owner)) result.put("job",latest.json(0,true));
        return result;
    }

    synchronized boolean hasActiveJobs() {
        if(preparing) return true;
        for(Job job:jobs.values()) if(!job.terminal()) return true;
        return false;
    }

    synchronized void stopAll(String reason) {
        // Pausing phone operations must not strand the offline resource installation.
        if (!"operations_paused".equals(reason)) cancelPreparation();
        for(Job job:jobs.values()) if(!job.terminal()) { job.cancelReason=reason; job.cancel(); }
    }

    synchronized void cancelStart(String owner,String requestId) {
        if(owner==null || owner.isBlank() || requestId==null || requestId.isBlank()) return;
        String key=owner+'\0'+requestId;
        if(!cancelledStarts.contains(key)) {
            if(cancelledStarts.size()>=1024) admissionClosed=true;
            else cancelledStarts.add(key);
        }
        for(Job job:jobs.values()) if(job.owner.equals(owner) && job.requestId.equals(requestId)) job.cancel();
    }
    synchronized void stopOwner(String owner) {
        for (Job job : jobs.values()) if (job.owner.equals(owner) && !job.terminal()) job.cancel();
    }
    synchronized java.util.concurrent.CompletableFuture<Void> completion(String id, String owner) throws Exception {
        return job(id, owner).settled;
    }

    private Path temporaryInputDirectory() throws IOException {
        Path path = context.getFilesDir().toPath().toRealPath();
        for (String part : new String[]{"dsh-home", "speech-to-text", "droy-input"}) {
            path = path.resolve(part);
            if (Files.isSymbolicLink(path)) throw new IOException(io.droyagent.app.NativeLocale.text("临时语音目录不能是符号链接", "The temporary speech directory must not be a symbolic link"));
        }
        return path;
    }

    private static boolean temporaryInputName(Path path) {
        return path.getFileName().toString().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.wav");
    }

    private void cleanAbandonedTemporaryInputs() throws IOException {
        Path directory = temporaryInputDirectory();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries)
                if (temporaryInputName(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path);
        }
    }

    private void cleanTemporaryInput(Job job) throws IOException {
        if (!job.owner.equals("droy-input-dictation") || job.file == null) return;
        Path path = job.file.toPath();
        if (path.getParent().equals(temporaryInputDirectory()) && temporaryInputName(path)) Files.deleteIfExists(path);
    }

    private JSONObject preparationStatus() throws Exception {
        String state=preparing ? "preparing" : preparationState;
        return new JSONObject().put("provider","sensevoice-local").put("ready",state.equals("ready"))
            .put("state",state).put("status",state).put("stage",stage)
            .put("downloadedBytes",downloadedBytes).put("totalBytes",totalBytes)
            .put("modelName","SenseVoice int8 / Silero VAD").put("runtimeVersion","1.13.8")
            .put("error",preparationError == null ? JSONObject.NULL : preparationError);
    }

    private boolean filesPresent() throws Exception {
        for(int i=0;i<assets.length();i++) {
            JSONObject asset=assets.getJSONObject(i); File file=new File(models,asset.getString("name"));
            if(!file.isFile() || file.length()!=asset.getLong("bytes")) return false;
        }
        return true;
    }

    private synchronized void prepare() throws Exception {
        if(preparing) return;
        if(hasActiveJobs()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先结束当前转录", "Please finish the current transcription first"));
        preparing=true; preparationCancelled=false; preparationError=null; preparationState="preparing";
        stage="verify"; downloadedBytes=0;
        worker.submit(() -> {
            try {
                if(!models.isDirectory() && !models.mkdirs()) throw new IOException(io.droyagent.app.NativeLocale.text("无法创建模型目录", "Unable to create model directory"));
                long completed=0;
                for(int i=0;i<assets.length();i++) {
                    checkPreparation(); JSONObject asset=assets.getJSONObject(i);
                    File file=new File(models,asset.getString("name")); stage="verify";
                    if(!verify(file,asset,true)) extract(file,asset,completed);
                    completed+=asset.getLong("bytes"); downloadedBytes=completed;
                }
                checkPreparation(); stage="load";
                OfflineRecognizer recognizer=createRecognizer("auto");
                recognizer.release();
                try (AudioVoiceActivity detector = createVoiceActivity()) { detector.speech(new float[512]); }
                checkPreparation();
                Files.write(new File(models,".verified").toPath(),modelStamp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                preparationState="ready"; stage="ready";
            } catch(Exception failure) {
                preparationState=preparationCancelled ? "not_prepared" : "failed";
                stage=preparationState; preparationError=preparationCancelled ? null : safeError(failure);
            } catch(LinkageError failure) {
                preparationState="failed"; stage="failed"; preparationError=io.droyagent.app.NativeLocale.text("本地语音运行时无法加载", "Unable to load local speech runtime");
            } finally { preparing=false; RuntimeService.refreshBackgroundPolicy(); }
        });
    }

    private void checkPreparation() throws Exception {
        if(preparationCancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException(io.droyagent.app.NativeLocale.text("模型准备已取消", "Model preparation cancelled"));
    }

    private void cancelPreparation() {
        if(!preparing) return;
        preparationCancelled=true;
        // The worker checks cancellation between chunks; cancelling a queued Future
        // would skip its finally block and leave the preparation state active.
    }

    private boolean verify(File file,JSONObject asset,boolean preparingCheck) throws Exception {
        if(!file.isFile() || file.length()!=asset.getLong("bytes")) return false;
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)) {
            byte[] block=new byte[262144]; int count;
            while((count=input.read(block))!=-1) { if(preparingCheck) checkPreparation(); hash.update(block,0,count); }
        }
        return hex(hash.digest()).equals(asset.getString("sha256"));
    }

    private void extract(File target,JSONObject asset,long completed) throws Exception {
        File part=new File(models,target.getName()+".part");
        try {
            stage="install"; long expected=asset.getLong("bytes"), received=0;
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(InputStream input=context.getAssets().open("audio-models/sensevoice-int8/"+target.getName());
                FileOutputStream output=new FileOutputStream(part)) {
                byte[] block=new byte[131072]; int count;
                while((count=input.read(block))!=-1) {
                    checkPreparation(); received+=count;
                    if(received>expected) throw new IOException(io.droyagent.app.NativeLocale.text("内置语音资源超过预期大小", "Built-in speech resources exceed the expected size"));
                    output.write(block,0,count); hash.update(block,0,count); downloadedBytes=completed+received;
                }
                output.getFD().sync();
            }
            checkPreparation(); stage="verify";
            if(received!=expected || !hex(hash.digest()).equals(asset.getString("sha256")))
                throw new IOException(io.droyagent.app.NativeLocale.text("内置语音资源校验失败，请重新安装应用", "Built-in speech resource verification failed. Please reinstall the app"));
            Files.move(part.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(part.toPath()); }
    }

    private synchronized Job start(JSONObject request,String owner,String captureOwner,String requestId) throws Exception {
        checkAllowed();
        if(requestId==null || requestId.isBlank()) throw new SecurityException(io.droyagent.app.NativeLocale.text("转录缺少可信请求标识", "Transcription requires a trusted request ID"));
        if(cancelledStarts.contains(owner+'\0'+requestId)) throw new InterruptedIOException(io.droyagent.app.NativeLocale.text("转录开始请求已取消", "Transcription start request was cancelled"));
        if(admissionClosed) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("转录取消记录已达本次运行上限，需要重新启动应用进程", "The transcription cancellation limit for this run was reached. Restart the app process"));
        for(Job previous:jobs.values()) if(previous.owner.equals(owner) && previous.requestId.equals(requestId)) return previous;
        if(!preparationState.equals("ready")) throw new IllegalStateException(preparationState.equals("failed")
            ? io.droyagent.app.NativeLocale.text("语音初始化失败：", "Speech initialization failed: ")+preparationError : io.droyagent.app.NativeLocale.text("语音正在初始化，请稍后再试", "Speech is initializing. Please try again shortly"));
        if(hasActiveJobs()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("已有转录正在运行", "A transcription is already running"));
        String language=request.optString("language","auto");
        if(!Set.of("auto","zh","en","ja","ko","yue").contains(language)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("不支持的识别语言", "Unsupported recognition language"));
        String capture=request.optString("captureId","");
        File file=capture.isEmpty() ? controlledFile(context.getFilesDir(),context.getCacheDir(),request.getString("inputPath")) : null;
        long startCursor=0;
        if(!capture.isEmpty()) {
            long next=AudioCapture.get(context).status(capture,captureOwner).getLong("nextCursor");
            // Voice owns a fresh capture. Keep audio collected before its first
            // UI poll so short greetings do not lose the first syllable.
            startCursor=owner.equals(VoiceMedia.OWNER) ? 0 : next;
        }
        Job job=new Job(owner,file,capture,captureOwner,language,startCursor,requestId); jobs.put(job.id,job); latest=job;
        while(jobs.size()>8) jobs.remove(jobs.keySet().iterator().next());
        worker.submit(() -> run(job)); return job;
    }

    private void run(Job job) {
        OfflineRecognizer recognizer=null; AudioVoiceActivity detector=null;
        try {
            job.check(); job.state="running";
            if(!filesPresent()) throw new IOException(io.droyagent.app.NativeLocale.text("本地语音模型缺失，请重新准备", "The local speech model is missing. Please prepare it again"));
            recognizer=createRecognizer(job.language); final OfflineRecognizer ready=recognizer;
            detector=createVoiceActivity(); final AudioVoiceActivity voiceActivity=detector;
            AudioTranscriptionSegments.Detector speechDetector=window -> {
                boolean active=voiceActivity.speech(window); job.speechActive=active; return active;
            };
            AudioTranscriptionSegments.Consumer output=new AudioTranscriptionSegments.Consumer() {
                @Override public void accept(float[] samples,int rate,long from,long to) throws Exception {
                    accept(samples,rate,from,to,true);
                }
                @Override public void accept(float[] samples,int rate,long from,long to,boolean endOfUtterance) throws Exception {
                    job.check(); String text="";
                    if(samples.length>0) {
                        long began=SystemClock.elapsedRealtime(); OfflineStream stream=ready.createStream();
                        try { stream.acceptWaveform(samples,rate); ready.decode(stream); text=AudioTranscriptionSegments.cleanText(ready.getResult(stream).getText()); }
                        finally { stream.release(); }
                        job.check(); job.inferenceMillis+=SystemClock.elapsedRealtime()-began;
                    }
                    if(!text.isBlank() || endOfUtterance) job.add(from+job.offsetMs,to+job.offsetMs,text,endOfUtterance);
                }
            };
            if(!job.capture.isEmpty()) realtime(job,output,speechDetector);
            else {
                final AudioTranscriptionSegments[] segmenter={null}; final int[] rate={0};
                AudioTranscriptionDecoder.decode(job.file,(samples,sampleRate) -> {
                    job.check();
                    if(segmenter[0]==null) { rate[0]=sampleRate; segmenter[0]=new AudioTranscriptionSegments(sampleRate,15,output,speechDetector); }
                    if(rate[0]!=sampleRate) throw new IOException(io.droyagent.app.NativeLocale.text("文件中途改变采样率，无法连续转录", "The file changes sample rate and cannot be transcribed continuously"));
                    segmenter[0].accept(samples); job.audioSamples+=samples.length; job.sampleRate=sampleRate;
                },job::check);
                if(segmenter[0]!=null) segmenter[0].finish();
            }
            job.check(); job.state="completed";
        } catch(Exception failure) {
            job.state=job.cancelled ? "cancelled" : "failed"; job.error=job.cancelled ? job.cancelReason : safeError(failure);
        } catch(LinkageError failure) { job.state="failed"; job.error=io.droyagent.app.NativeLocale.text("本地语音运行时无法加载", "Unable to load local speech runtime"); }
        finally {
            try { if(detector!=null) detector.close(); if(recognizer!=null) recognizer.release(); }
            finally {
                String cleanupError = null;
                try { cleanTemporaryInput(job); }
                catch (IOException failure) {
                    cleanupError = io.droyagent.app.NativeLocale.text("临时语音清理失败：", "Temporary speech cleanup failed: ") + safeError(failure);
                    android.util.Log.w("DroyAudio", "Cannot remove temporary dictation input", failure);
                }
                job.finish(cleanupError); RuntimeService.refreshBackgroundPolicy();
            }
        }
    }

    private void realtime(Job job,AudioTranscriptionSegments.Consumer output,AudioTranscriptionSegments.Detector detector) throws Exception {
        long cursor=job.startCursor; AudioTranscriptionSegments segments=null; int rate=0;
        while(true) {
            job.check(); AudioCapture.FrameRead frames=AudioCapture.get(context).readFrames(job.capture,job.captureOwner,cursor,96000);
            JSONObject meta=frames.metadata;
            if(meta.optBoolean("discontinuity")) throw new IOException(io.droyagent.app.NativeLocale.text("实时音频积压超过缓存，已中断；可使用录音文件补转", "Live audio exceeded the buffer and was interrupted. You can transcribe the recording file instead"));
            cursor=meta.getLong("nextCursor"); int sampleRate=meta.getInt("sampleRate");
            if(segments==null) {
                rate=sampleRate; job.offsetMs=job.startCursor*1000/(sampleRate*meta.getInt("channels")*2L);
                segments=new AudioTranscriptionSegments(rate,6,output,detector);
            }
            if(sampleRate!=rate) throw new IOException(io.droyagent.app.NativeLocale.text("实时音频格式改变，转录已停止", "Live audio format changed. Transcription stopped"));
            if(frames.data.length>0) {
                float[] mono=AudioTranscriptionSegments.pcm16(frames.data,meta.getInt("channels"));
                segments.accept(mono); job.audioSamples+=mono.length; job.sampleRate=rate;
            }
            if(meta.optBoolean("ended")) { segments.finish(); return; }
            if(frames.data.length==0) Thread.sleep(100);
        }
    }

    private AudioVoiceActivity createVoiceActivity() {
        return new AudioVoiceActivity(new File(models,"silero_vad.onnx"));
    }

    private OfflineRecognizer createRecognizer(String language) {
        OfflineSenseVoiceModelConfig sense=new OfflineSenseVoiceModelConfig();
        sense.setModel(new File(models,"model.int8.onnx").getAbsolutePath());
        sense.setLanguage(language); sense.setUseInverseTextNormalization(true);
        OfflineModelConfig model=new OfflineModelConfig(); model.setSenseVoice(sense);
        model.setTokens(new File(models,"tokens.txt").getAbsolutePath()); model.setNumThreads(2); model.setProvider("cpu");
        OfflineRecognizerConfig config=new OfflineRecognizerConfig(); config.setModelConfig(model);
        return new OfflineRecognizer(null,config);
    }

    private Job job(String id,String owner) {
        Job job=jobs.get(id);
        if(job==null || !job.owner.equals(owner)) throw new SecurityException(io.droyagent.app.NativeLocale.text("转录不存在或不属于当前调用者", "Transcription does not exist or belongs to another caller"));
        return job;
    }

    private void checkAllowed() {
        if(ProductPermissions.stopped(context) || !ProductPermissions.allowed(ProductPermissions.preferences(context),"allowAudioTranscription"))
            throw new SecurityException(io.droyagent.app.NativeLocale.text("音频转录已暂停或关闭", "Audio transcription is paused or disabled"));
    }

    static File controlledFile(File files,File cache,String value) throws IOException {
        Path requested=Paths.get(value);
        if(!requested.isAbsolute()) throw new IOException(io.droyagent.app.NativeLocale.text("转录文件必须是应用内绝对路径", "The transcription file must use an absolute path within the app"));
        Path path=requested.normalize();
        for(File allowed:new File[]{files,cache}) {
            Path rawRoot=allowed.toPath().toAbsolutePath().normalize(); Path realRoot=rawRoot.toRealPath();
            Path root=path.startsWith(rawRoot) ? rawRoot : path.startsWith(realRoot) ? realRoot : null;
            if(root==null) continue;
            Path current=root;
            for(Path part:root.relativize(path)) { current=current.resolve(part); if(Files.isSymbolicLink(current)) throw new IOException(io.droyagent.app.NativeLocale.text("转录不读取符号链接", "Transcription does not read symbolic links")); }
            Path real=path.toRealPath();
            if(!real.startsWith(realRoot) || !Files.isRegularFile(real,LinkOption.NOFOLLOW_LINKS)) throw new IOException(io.droyagent.app.NativeLocale.text("转录文件超出应用范围", "The transcription file is outside the app's allowed scope"));
            if(Files.size(real)>2L*1024*1024*1024) throw new IOException(io.droyagent.app.NativeLocale.text("转录文件超过 2 GiB", "The transcription file exceeds 2 GiB"));
            return real.toFile();
        }
        throw new IOException(io.droyagent.app.NativeLocale.text("转录只接受应用内已选择或保存的音频", "Local transcription accepts only audio selected or saved within the app"));
    }

    private static String safeError(Exception failure) {
        if(failure instanceof IOException || failure instanceof SecurityException || failure instanceof IllegalArgumentException)
            return failure.getMessage();
        return io.droyagent.app.NativeLocale.text("本地转录失败：", "Local transcription failed: ")+failure.getClass().getSimpleName();
    }
    private static String hex(byte[] bytes) {
        StringBuilder result=new StringBuilder(); for(byte value:bytes) result.append(String.format(Locale.ROOT,"%02x",value&255)); return result.toString();
    }

    private final class Job {
        final String id=UUID.randomUUID().toString(),owner,capture,captureOwner,language,requestId; final File file;
        final long startCursor;
        final List<JSONObject> segments=new ArrayList<>();
        volatile String state="queued",error,cancelReason=io.droyagent.app.NativeLocale.text("用户停止转录", "The user stopped transcription");
        volatile boolean cancelled,finished,speechActive;
        final java.util.concurrent.CompletableFuture<Void> settled = new java.util.concurrent.CompletableFuture<>();
        volatile long audioSamples,inferenceMillis,offsetMs;
        volatile int sampleRate=16000;
        private int utterance;
        Job(String owner,File file,String capture,String captureOwner,String language,long startCursor,String requestId) {
            this.owner=owner; this.file=file; this.capture=capture; this.captureOwner=captureOwner;
            this.language=language; this.startCursor=startCursor; this.requestId=requestId;
        }
        boolean terminal() { return finished; }
        synchronized void cancel() { if(!terminal()) { cancelled=true; state="cancelling"; } }
        synchronized void finish(String cleanupError) {
            if (cleanupError != null) { state="failed"; error=cleanupError; }
            else if(cancelled) { state="cancelled"; error=cancelReason; }
            finished=true;
            settled.complete(null);
        }
        void check() throws Exception { if(cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException(io.droyagent.app.NativeLocale.text("转录已取消", "Transcription cancelled")); checkAllowed(); }
        synchronized void add(long from,long to,String text,boolean endOfUtterance) throws Exception {
            if(segments.size()>=20000) throw new IOException(io.droyagent.app.NativeLocale.text("转录片段达到本次上限", "The transcription segment limit for this run was reached"));
            segments.add(new JSONObject().put("id",id+":"+segments.size()).put("startMs",from).put("endMs",to)
                .put("text",text).put("final",true).put("timing","segment")
                .put("utteranceId",id+":utterance:"+utterance).put("endOfUtterance",endOfUtterance));
            if(endOfUtterance) utterance++;
        }
        synchronized JSONObject json(int cursor) throws Exception { return json(cursor,false); }
        synchronized JSONObject summary() throws Exception {
            return new JSONObject().put("transcriptId",id).put("provider","sensevoice-local")
                .put("state",state).put("status",state).put("finished",finished).put("speechActive",!finished && speechActive)
                .put("mode",capture.isEmpty()?"file":"segmented").put("segmentCount",segments.size())
                .put("audioSeconds",audioSamples/(double)sampleRate).put("inferenceSeconds",inferenceMillis/1000d)
                .put("error",error==null?JSONObject.NULL:error);
        }
        synchronized JSONObject json(int cursor,boolean full) throws Exception {
            if(cursor<0 || cursor>segments.size()) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("转录游标无效", "Invalid transcription cursor"));
            JSONArray delta=new JSONArray(); int end=Math.min(segments.size(),cursor+256);
            for(int i=cursor;i<end;i++) delta.put(segments.get(i));
            JSONObject result=new JSONObject().put("transcriptId",id).put("provider","sensevoice-local")
                .put("state",state).put("status",state).put("mode",capture.isEmpty()?"file":"segmented")
                .put("finished",finished).put("speechActive",!finished && speechActive)
                .put("cursor",cursor).put("nextCursor",end).put("segments",delta)
                .put("audioSeconds",audioSamples/(double)sampleRate).put("inferenceSeconds",inferenceMillis/1000d)
                .put("error",error==null?JSONObject.NULL:error);
            if(!capture.isEmpty()) result.put("captureId",capture).put("startCursor",startCursor).put("startMs",offsetMs);
            if(full || (cursor==0 && finished)) {
                StringBuilder text=new StringBuilder(); for(JSONObject segment:segments) text.append(segment.optString("text")).append('\n');
                result.put("text",text.toString().stripTrailing());
            }
            return result;
        }
    }
}
