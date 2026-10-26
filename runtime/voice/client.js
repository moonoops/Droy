window.__ModuleLoader__.load({
  id: '@droyagent/voice',
  factory: require => {
    const React = require('react');
    const { Button, Switch } = require('@deepseek-ai/dsh-client-ui-primitives');
    const h = React.createElement;
    let locale;
    const messages = {
      "用户": "User",
      "保存中…": "Saving…",
      "语音服务未就绪": "Voice service is not ready",
      "手机语音服务未就绪，请重新打开 Droy": "Phone voice service is not ready. Reopen Droy.",
      "插入识别结果": "Insert transcript",
      "语音对话": "Voice conversation",
      "按住说话，松开发送": "Hold to speak, release to send",
      "已静音": "Muted",
      "朗读中": "Reading aloud",
      "正在听": "Listening",
      "准备中": "Getting ready",
      "关闭回答朗读": "Turn off answer readout",
      "开启回答朗读": "Turn on answer readout",
      "开启麦克风": "Turn on microphone",
      "静音麦克风": "Mute microphone",
      "退出语音对话": "End voice conversation",
      "松开取消": "Release to cancel",
      "正在识别": "Transcribing",
      "松开发送": "Release to send",
      "上滑取消": "Swipe up to cancel",
      "重新加载": "Reload",
      "正在加载语音设置…": "Loading voice settings…",
      "语音资源暂未就绪。": "Voice resources are not ready.",
      "正在读取语音资源…": "Reading voice resources…",
      "当前版本尚未提供此语音资源。": "These voice resources are not available in this version.",
      "资源已下载，可离线使用。": "Downloaded and available offline.",
      "正在校验资源，完成后即可使用。": "Verifying resources. They will be available when verification finishes.",
      "正在下载 {v0} / {v1}": "Downloading {v0} / {v1}",
      "资源下载失败，请重试。": "Download failed. Try again.",
      "首次使用需下载 {v0} 语音资源，下载完成后可离线使用。": "Download {v0} of voice resources before first use. They will then be available offline.",
      "唤醒设置已保存，朗读方式未切换。": "Wake settings saved. The readout provider was not changed.",
      "已保存": "Saved",
      "语音设置": "Voice settings",
      "语音输入": "Voice input",
      "语音对话入口": "Voice conversation entry",
      "按住说话，松开识别并发送；语音对话会在一句话结束后自动发送。": "Hold to speak, release to transcribe and send. Voice conversations send automatically when a sentence ends.",
      "语音在手机本地识别，自动识别语言，无需配置。": "Speech is transcribed locally on your phone. The language is detected automatically; no configuration is needed.",
      "查看麦克风与语音权限": "View microphone and voice permissions",
      "回答朗读与声音": "Answer readout and voice",
      "每次进入语音对话默认关闭朗读，可在输入框上方开启。": "Readout is off each time you enter a voice conversation. You can turn it on above the input box.",
      "发声方式": "Speech provider",
      "Android 系统声音": "Android system voice",
      "MeloTTS 中文": "MeloTTS Chinese",
      "自定义语音服务": "Custom voice service",
      "发声方式决定由哪个引擎生成语音；朗读音色决定它使用哪种声音，不是语言设置。": "The provider selects the speech engine; the readout voice selects its voice. This is separate from the app language.",
      "语音资源下载进度": "Voice resource download progress",
      "刷新资源状态": "Refresh resource status",
      "取消下载": "Cancel download",
      "重新下载": "Download again",
      "下载资源（{v0}）": "Download resources ({v0})",
      "TTS 地址": "TTS URL",
      "TTS 模型": "TTS model",
      "已保存，留空不修改": "Saved; leave blank to keep",
      "朗读音色": "Readout voice",
      "系统默认": "System default",
      "默认音色": "Default voice",
      "音色 ID": "Voice ID",
      "语速": "Speech rate",
      "慢": "Slow",
      "标准": "Normal",
      "快": "Fast",
      "Qwen3-TTS 使用标准语速。": "Qwen3-TTS uses the normal speech rate.",
      "试听": "Preview",
      "停止试听": "Stop preview",
      "自定义语音服务保存后用于回答朗读。": "The custom voice service is used for answer readout after saving.",
      "关键词唤醒": "Wake word",
      "语音唤醒": "Voice wake-up",
      "唤醒后新建对话，说“我在”并开始语音输入。": "Wake-up creates a new conversation, says “I'm here”, and starts voice input.",
      "唤醒时机": "When to listen",
      "仅亮屏时": "While screen is on",
      "仅在 Droy 内": "Only inside Droy",
      "仅在 Droy 位于前台且屏幕解锁时监听。": "Listen only while Droy is in the foreground and the screen is unlocked.",
      "屏幕亮起且解锁时监听。": "Listen while the screen is on and unlocked.",
      "唤醒关键词": "Wake words",
      "使用 Droy 伙伴名称唤醒": "Wake using the Droy companion's name",
      "使用“你好 + 名称”或“Hi + 名称”唤醒，开启后不再单独设置中英文唤醒词。": "Say “你好 + name” or “Hi + name”. When enabled, separate Chinese and English wake words are not used.",
      "当前伙伴名称：{v0}。": "Current companion name: {v0}.",
      "中文唤醒词": "Chinese wake word",
      "英文唤醒词": "English wake word",
      "唤醒默认关闭": "Wake-up is off by default",
      "唤醒资源准备进度": "Wake resource preparation progress",
      "正在准备唤醒资源": "Preparing wake resources",
      "重试准备": "Retry preparation",
      "保存唤醒设置": "Save wake settings",
      "保存设置": "Save settings",
      "语音服务连接失败：{v0}": "Voice service connection failed: {v0}",
      "语音服务正在连接，请稍候再试": "Voice service is connecting. Try again shortly.",
      "离线识别已就绪": "Offline recognition is ready",
      "语音初始化失败，请重新打开 Droy": "Voice initialization failed. Reopen Droy.",
      "语音初始化已中断，请重新打开 Droy": "Voice initialization was interrupted. Reopen Droy.",
      "语音正在初始化，请稍候再试": "Voice is initializing. Try again shortly.",
      "识别结果已保留在输入框。处理草稿后可开启麦克风继续。": "The transcript is kept in the input box. Handle the draft, then turn on the microphone to continue.",
      "草稿已更改，识别结果已保留，可插入输入框。": "The draft has changed. The transcript is retained and can be inserted into the input box.",
      "识别结果已保留，请确认草稿后发送。": "The transcript is retained. Review the draft before sending.",
      "实时音视频暂未开放": "Live audio and video are not available yet",
      "本次唤醒已取消": "This wake-up was cancelled",
      "本次唤醒已取消，请在亮屏解锁后重新唤醒": "This wake-up was cancelled. Wake again with the screen on and unlocked.",
      "我在": "I'm here",
      "录音超过识别服务大小上限，请缩短后重试": "The recording exceeds the recognition service size limit. Try a shorter recording.",
      "没有听清，请再说一次": "I couldn't hear that clearly. Please say it again.",
      "输入框暂时无法插入，请稍后再试": "Cannot insert into the input box yet. Try again shortly.",
      "语音输入已取消": "Voice input cancelled",
      "请先选择工作区": "Select a space first",
      "语音消息未能提交": "The voice message could not be submitted",
      "任务取消未完成": "The task could not be cancelled",
      "请填写完整的服务地址": "Enter a complete service URL.",
      "服务地址格式不支持": "Unsupported service URL format.",
      "请将凭据填写在 API Key 中": "Enter credentials in the API Key field.",
      "语速应在 0.5 到 2 之间": "Speech rate must be between 0.5 and 2.",
      "开关值无效": "Invalid switch value.",
      "设置格式无效": "Invalid settings format.",
      "Qwen3-TTS 当前仅支持标准语速": "Qwen3-TTS currently supports only the normal speech rate.",
      "请至少填写一个唤醒词": "Enter at least one wake word.",
      "音色配置无效": "Invalid voice configuration.",
      "请填写音色名称和 ID": "Enter a voice name and ID.",
      "参考音频应为 16kHz 单声道 float32": "Reference audio must be 16 kHz mono float32.",
      "参考音频格式无效或超过 30 秒": "Reference audio is invalid or longer than 30 seconds.",
      "参考音频应为 0.5 到 30 秒": "Reference audio must be between 0.5 and 30 seconds.",
      "参考音频包含无效采样值": "Reference audio contains invalid samples.",
      "凭据存储尚未就绪": "Credential storage is not ready.",
      "API Key 格式无效": "Invalid API Key format.",
      "已添加的音色过多，请先移除不用的音色": "Too many saved voices. Remove unused voices first.",
      "有多个同名音色，请选择一个": "Multiple voices have this name. Choose one.",
      "找不到这个音色": "This voice could not be found.",
      "请先在语音设置中下载并准备所选朗读资源": "Download and prepare the selected readout resources in Voice settings first.",
      "朗读文字应为 1 到 4000 字": "Readout text must contain between 1 and 4000 characters.",
      "回复身份格式无效": "Invalid response identity format.",
      "朗读服务无效": "Invalid readout service.",
      "朗读服务未授权，请检查 API Key": "Readout service authorization failed. Check the API Key.",
      "朗读服务未接受请求，请检查配置": "The readout service rejected the request. Check the configuration.",
      "朗读服务没有返回音频": "The readout service returned no audio.",
      "朗读音频过长，请缩短文字": "Readout audio is too long. Shorten the text.",
      "朗读服务返回的 PCM 音频无效": "The readout service returned invalid PCM audio.",
      "无法连接朗读服务，请检查地址与网络": "Cannot connect to the readout service. Check the URL and network.",
      "请先保存自定义朗读服务再试听": "Save the custom readout service before previewing it.",
      "请求内容过大": "The request is too large.",
      "请求格式无效": "Invalid request format.",
      "请先结束实时通话，再试听音色": "End the live call before previewing a voice.",
      "实时通话正在使用模型原声": "The live call is using the model's own voice.",
      "本版暂不开放实时音视频对话": "Live audio and video conversations are not available in this version.",
      "本版暂不开放声纹功能": "Voiceprint features are not available in this version.",
      "本版仅支持普通语音对话": "This version supports standard voice conversations only.",
      "已有实时通话正在进行": "A live call is already in progress.",
      "请先开启语音对话入口": "Enable the voice conversation entry first.",
      "不支持的语音操作": "Unsupported voice operation.",
      "语音资源未能全部释放": "Some voice resources could not be released.",
      "语音操作失败，请重试": "Voice operation failed. Try again.",
      "服务配置格式无效": "Invalid service configuration format.",
      "语音服务不支持": "Unsupported voice service.",
      "唤醒时机不支持": "Unsupported wake-up timing.",
      "唤醒目标不支持": "Unsupported wake-up destination.",
      "唤醒词格式无效": "Invalid wake word format.",
      "音色名称格式无效": "Invalid voice name format.",
      "音色 ID格式无效": "Invalid voice ID format.",
      "朗读服务不支持": "Unsupported readout service."
};
    const namespace = 'droy.voice';
    const interpolate = (text, params) => text.replace(/\{(\w+)\}/g, (match, name) => params && name in params ? String(params[name]) : match);
    const retainedMessages = Object.entries(messages).flatMap(([source, english]) => [source, english].map(template => {
      const names = [];
      const pattern = template.split(/(\{\w+\})/g).map(part => {
        if (/^\{\w+\}$/.test(part)) { names.push(part.slice(1, -1)); return '([\\s\\S]*?)'; }
        return part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      }).join('');
      return { source, english, names, pattern: new RegExp(`^${pattern}$`) };
    }));
    function tr(source, params) {
      if (typeof source !== 'string') return source;
      const english = locale?.getSnapshot().active === 'en';
      if (Object.hasOwn(messages, source)) return interpolate(english ? messages[source] : source, params);
      // Only product status/error surfaces use this path; user records and drafts are never translated.
      for (const message of retainedMessages) {
        const match = message.pattern.exec(source);
        if (match) return interpolate(english ? message.english : message.source,
          Object.fromEntries(message.names.map((name, index) => [name, match[index + 1]])));
      }
      return source;
    }
    function installLocale(ctx) {
      locale = ctx.locale;
      if (!locale) return;
      const dispose = locale.register(namespace, { zh: Object.fromEntries(Object.keys(messages).map(key => [key, key])), en: messages });
      ctx.effect(() => dispose);
    }
    function useLocale() {
      if (locale) React.useSyncExternalStore(listener => locale.subscribe(listener), () => locale.getSnapshot(), () => locale.getSnapshot());
    }

    /* DROY_VOICE_ADAPTER */
    /* DROY_VOICE_CONTROLLER */
    const styles = /* DROY_VOICE_STYLES */ '';

    async function apply(ctx) {
      installLocale(ctx);
      const target = window, doc = target.document;
      const speech = await require('@deepseek-ai/dsh-experimental-client-ui-voice-input').createDroyVoiceInput(ctx);
      const adapter = createDshVoiceAdapter(ctx);
      async function http(operation, args = {}) {
        const response = await target.fetch('/droy/voice', { credentials: 'same-origin', cache: 'no-store', redirect: 'error',
          ...(operation ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ operation, ...args }) } : {}) });
        const body = await response.json();
        if (!response.ok || body.ok !== true) throw new Error(body.error?.message || body.error || tr("语音服务未就绪"));
        return body.result;
      }
      const controller = createVoiceController(adapter, http, {
        speech, translate: tr, btoa: target.btoa.bind(target), get: () => http(),
        setTimeout: target.setTimeout.bind(target), clearTimeout: target.clearTimeout.bind(target),
        openSettings: () => target.dispatchEvent(new target.CustomEvent('droy-mobile-open-settings', { detail: { section: 'droy-voice' } })),
        native(action, args) {
          if (typeof target.DroyMobile?.voiceRequest !== 'function') throw new Error(tr("手机语音服务未就绪，请重新打开 Droy"));
          return target.DroyMobile.voiceRequest(action, args);
        },
      });
      const useVoice = () => React.useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot);
      const useSpeech = () => React.useSyncExternalStore(speech.readiness.subscribe, speech.readiness.getSnapshot, speech.readiness.getSnapshot);
      const act = promise => { void Promise.resolve(promise).catch(controller.report); };
      function icon(name) {
        const paths = {
          conversation: ['M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-5 3v-3a2 2 0 0 1-2-2V7a3 3 0 0 1 3-3Z', 'M8 10v2m4-4v6m4-4v2'],
          hold: ['M6 10a3 3 0 0 1 0 4', 'M10 7a7 7 0 0 1 0 10', 'M14 4a11 11 0 0 1 0 16'],
          mic: ['M9 6a3 3 0 0 1 6 0v6a3 3 0 0 1-6 0V6Z', 'M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8'],
          muted: ['M9 9v3a3 3 0 0 0 5 2M9 5a3 3 0 0 1 6 1v4M5 10v2a7 7 0 0 0 12 5M19 10v2M12 19v3M8 22h8', 'M3 3l18 18'],
          speaker: ['M4 9h4l5-4v14l-5-4H4V9Z', 'M17 8a6 6 0 0 1 0 8M19 5a10 10 0 0 1 0 14'],
          silent: ['M4 9h4l5-4v14l-5-4H4V9Z', 'm17 9 5 6m0-6-5 6'],
          close: ['M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Z', 'm8 8 8 8m0-8-8 8'],
        };
        return h('svg', { width: 22, height: 22, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.7,
          strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true }, paths[name].map((d, key) => h('path', { key, d })));
      }
      function Wave({ level = 0, sequence = 0, active = true, large = false }) {
        const [samples, setSamples] = React.useState(() => Array(24).fill(0));
        React.useEffect(() => {
          // Each measured sample advances the trace, including equal successive levels.
          setSamples(previous => !active || level <= 0 ? Array(24).fill(0)
            : [...previous.slice(1), Math.min(1, Math.sqrt(level) * 3.2)]);
        }, [level, sequence, active]);
        return h('div', { className: `droy-voice-wave${large ? ' is-large' : ''}${!active ? ' is-inactive' : ''}`, 'aria-hidden': true },
          samples.map((sample, key) => h('i', { key, style: { transform: `scaleY(${Math.max(.06, active ? sample : 0)})` } })));
      }
      function LoadingDots() {
        return h('div', { className: 'droy-voice-loading', 'aria-hidden': true }, [0, 1, 2].map(key => h('i', { key })));
      }
      function Feedback({ state }) {
        return state.error ? h('div', { className: 'droy-voice-feedback', role: 'status', 'data-droy-voice-error': true },
          h('span', null, tr(state.error)), state.pendingTranscript ? h(React.Fragment, null,
            h('p', { className: 'droy-voice-pending' }, state.pendingTranscript),
            h(Button, { variant: 'text', onClick: () => act(controller.insertPending()) }, tr("插入识别结果"))) : null) : null;
      }
      function VoiceActivity({ sessionId, inputActions, locked = false }) {
        useLocale();
        const state = useVoice(), readiness = useSpeech(), pointer = React.useRef(null);
        React.useLayoutEffect(() => {
          if (sessionId && inputActions) return adapter.attachInput(sessionId, inputActions);
        }, [sessionId, inputActions]);
        if (!state.config) return null;
        const running = state.mode === 'standard' && !['idle', 'ended', 'error'].includes(state.status);
        const holding = state.mode === 'hold' && !['idle', 'ended', 'error'].includes(state.status);
        const press = event => {
          if (event.button !== 0 || running || holding) return;
          event.preventDefault(); event.currentTarget.setPointerCapture(event.pointerId);
          pointer.current = { id: event.pointerId, y: event.clientY };
          act(controller.beginHold(sessionId));
        };
        const release = (event, cancel = false) => {
          if (pointer.current?.id !== event.pointerId) return;
          event.preventDefault(); pointer.current = null;
          act(controller.finishHold(cancel));
        };
        return h('div', { className: 'droy-voice-activity', 'data-droy-voice-activity': true,
          'data-droy-voice-recognizer-state': controller.inputStatus(readiness).state },
          state.config.entries.standard ? h('button', { type: 'button', className: `droy-voice-icon${running ? ' is-selected' : ''}`,
            'aria-label': tr("语音对话"), title: tr("语音对话"), 'data-droy-voice-start': 'standard', disabled: holding || !!state.busy,
            onMouseDown: event => event.preventDefault(), onClick: () => act(controller.start('standard', sessionId)) }, icon('conversation')) : null,
          h('button', { type: 'button', className: 'droy-voice-icon droy-voice-hold', 'data-droy-voice-hold': true,
            'aria-label': tr("按住说话，松开发送"), title: tr("按住说话，松开发送"), disabled: running || locked || state.status === 'transcribing',
            onPointerDown: press, onPointerMove: event => {
              if (pointer.current?.id === event.pointerId) controller.moveHold(pointer.current.y - event.clientY > 64);
            }, onPointerUp: event => release(event), onPointerCancel: event => release(event, true),
            onLostPointerCapture: event => { if (pointer.current?.id === event.pointerId) release(event, true); },
            onContextMenu: event => event.preventDefault(), onKeyDown: event => {
              if ((event.key === ' ' || event.key === 'Enter') && !event.repeat) { event.preventDefault(); act(controller.beginHold(sessionId)); }
              if (event.key === 'Escape') act(controller.cancelHold());
            }, onKeyUp: event => { if (event.key === ' ' || event.key === 'Enter') { event.preventDefault(); act(controller.finishHold()); } },
          }, h('span', { className: 'droy-voice-hold-face' }, icon('hold'))));
      }
      function VoiceDock() {
        useLocale();
        const state = useVoice();
        const running = state.mode === 'standard' && !['idle', 'ended', 'error'].includes(state.status);
        if (!running) return h(Feedback, { state });
        const label = state.muted ? tr("已静音") : state.paused || state.speaking ? tr("朗读中") : state.status === 'active' ? tr("正在听") : tr("准备中");
        return h('div', { className: 'droy-voice-dock', 'data-droy-voice-dock': true },
          h('div', { className: 'droy-voice-inline', 'data-droy-voice-inline': true },
            h('div', { className: 'droy-voice-signal', role: 'status', 'aria-label': label, title: label },
              h(Wave, { level: state.level, sequence: state.levelSequence, active: state.status === 'active' && !state.muted && !state.paused && !state.speaking })),
            h('div', { className: 'droy-voice-controls' },
              h('button', { type: 'button', className: 'droy-voice-icon', 'data-droy-voice-speaker': true, 'aria-pressed': state.readAloud,
                'aria-label': state.readAloud ? tr("关闭回答朗读") : tr("开启回答朗读"), onClick: () => controller.toggleReadAloud() }, icon(state.readAloud ? 'speaker' : 'silent')),
              h('button', { type: 'button', className: `droy-voice-icon ${state.muted ? 'is-muted' : state.paused ? 'is-paused' : 'is-listening'}`,
                'data-droy-voice-mute': true, 'aria-pressed': !state.muted, disabled: state.status !== 'active',
                'aria-label': state.muted ? tr("开启麦克风") : tr("静音麦克风"), onClick: () => act(controller.mute()) }, icon(state.muted ? 'muted' : 'mic')),
              h('button', { type: 'button', className: 'droy-voice-icon', 'data-droy-voice-end': true, 'aria-label': tr("退出语音对话"),
                onClick: () => act(controller.end()) }, icon('close')))), h(Feedback, { state }));
      }
      function HoldOverlay() {
        useLocale();
        const state = useVoice();
        if (state.mode !== 'hold' || !['starting', 'recording', 'transcribing'].includes(state.status)) return null;
        const cancelling = state.holdCancelled && state.status !== 'transcribing';
        const phase = cancelling ? 'cancelling' : state.status;
        const label = cancelling ? tr("松开取消") : phase === 'starting' ? tr("准备中") : phase === 'transcribing' ? tr("正在识别") : tr("松开发送");
        return h('div', { className: `droy-voice-hold-overlay${cancelling ? ' is-cancelling' : ''}`,
          'data-droy-voice-hold-overlay': true, 'data-droy-voice-hold-state': phase },
          h('div', { className: 'droy-voice-hold-sheet', role: 'status', 'aria-live': 'polite' },
            h('div', { className: 'droy-voice-hold-stage' }, cancelling ? h('span', { className: 'droy-voice-cancel-cue', 'aria-hidden': true }, icon('close'))
              : phase === 'recording' ? h(Wave, { level: state.level, sequence: state.levelSequence, large: true }) : h(LoadingDots)),
            h('p', { className: 'droy-voice-hold-label' }, label),
            h('p', { className: 'droy-voice-hold-hint', 'aria-hidden': phase !== 'recording' }, phase === 'recording' ? tr("上滑取消") : '\u00a0')));
      }
      function Toggle({ label, checked, onChange, name, disabled = false }) {
        return h('label', { className: 'droy-voice-toggle', 'data-droy-voice-entry-toggle': name }, h('span', null, label), h(Switch, { label, checked: !!checked, onChange, disabled }));
      }
      function Field({ label, value, onChange, type = 'text', placeholder, children, disabled = false }) {
        return h('div', { className: 'droy-voice-field' }, h('span', null, label), children || h('input', {
          value: value ?? '', type, placeholder, 'aria-label': label, autoCapitalize: 'none', autoComplete: 'off', autoCorrect: 'off',
          spellCheck: false, disabled, onChange: event => onChange(event.target.value),
        }));
      }
      function Choice({ label, value, options, onChange, disabled = false, ...attributes }) {
        const selected = options.find(option => String(option.value) === String(value));
        const close = event => { const details = event.currentTarget.closest('details'); details.open = false; details.querySelector('summary').focus(); };
        return h('details', { className: 'droy-voice-choice', onBlur: event => {
          if (!event.currentTarget.contains(event.relatedTarget)) event.currentTarget.open = false;
        }, onKeyDown: event => {
          if (disabled) return;
          if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(event); }
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault(); const details = event.currentTarget; details.open = true;
            const items = Array.from(details.querySelectorAll('[role=option]'));
            const index = items.indexOf(doc.activeElement), direction = event.key === 'ArrowDown' ? 1 : -1;
            items[index < 0 ? direction > 0 ? 0 : items.length - 1 : (index + direction + items.length) % items.length]?.focus();
          }
        } }, h('summary', { 'aria-label': label, 'aria-haspopup': 'listbox', 'aria-disabled': disabled,
          tabIndex: disabled ? -1 : 0, onClick: event => { if (disabled) event.preventDefault(); }, 'data-value': value, ...attributes },
          h('span', null, selected?.label ?? value), h('span', { 'aria-hidden': true }, '⌄')),
          disabled ? null : h('div', { role: 'listbox', 'aria-label': label }, options.map(option => h('button', {
            key: option.value, type: 'button', role: 'option', 'data-value': option.value, 'aria-selected': String(option.value) === String(value),
            onClick: event => { onChange(option.value); close(event); },
          }, option.label, String(option.value) === String(value) ? h('span', { 'aria-hidden': true }, '✓') : null))));
      }
      function VoiceSettings({ renderSlot }) {
        useLocale();
        const state = useVoice(), readiness = useSpeech(), config = state.config;
        const [draft, setDraft] = React.useState(null), [notice, setNotice] = React.useState('');
        const [saving, setSaving] = React.useState(false);
        const [preview, setPreview] = React.useState('idle');
        const [models, setModels] = React.useState(null), [modelError, setModelError] = React.useState('');
        const [modelAction, setModelAction] = React.useState(false);
        const mounted = React.useRef(false);
        const previewOwner = React.useRef(null);
        const ttsValue = JSON.stringify(config?.tts);
        const wakeValue = JSON.stringify([config?.wake?.chinese, config?.wake?.english, config?.wake?.timing, config?.wake?.useCompanionName]);
        const refreshModels = async () => {
          try {
            const result = await controller.request('tts.models');
            if (mounted.current) { setModels(result.models); setModelError(''); }
          } catch (error) { if (mounted.current) setModelError(error.message); }
        };
        React.useEffect(() => {
          mounted.current = true;
          controller.openSettings(false); act(controller.loadVoices()); void refreshModels();
          return () => {
            mounted.current = false;
            controller.closeSettings();
            if (previewOwner.current) { previewOwner.current = null; act(controller.request('stop_speaking')); }
          };
        }, []);
        const downloading = models?.some(model => ['downloading', 'verifying'].includes(model.state)) || false;
        React.useEffect(() => {
          if (!downloading) return;
          let cancelled = false, timer;
          const check = async () => {
            try {
              const result = await controller.request('tts.models');
              if (cancelled) return;
              setModels(result.models); setModelError('');
              if (!result.models.some(model => ['downloading', 'verifying'].includes(model.state))) await controller.loadVoices();
            } catch (error) { if (!cancelled) setModelError(error.message); }
          };
          timer = target.setTimeout(check, 500);
          return () => { cancelled = true; target.clearTimeout(timer); };
        }, [models]);
        React.useEffect(() => {
          if (preview !== 'playing') return;
          const owner = previewOwner.current;
          let cancelled = false, timer;
          const check = async () => {
            try {
              const result = await controller.request('status');
              if (cancelled || previewOwner.current !== owner) return;
              if (result.speechError) throw new Error(result.speechError);
              if (result.speaking) timer = target.setTimeout(check, 250);
              else { previewOwner.current = null; setPreview('idle'); }
            } catch (error) {
              if (cancelled || previewOwner.current !== owner) return;
              previewOwner.current = null; setPreview('idle'); controller.report(error);
            }
          };
          void check();
          return () => { cancelled = true; target.clearTimeout(timer); };
        }, [preview]);
        React.useEffect(() => {
          const panel = doc.querySelector('[data-droy-settings-panel]');
          if (!panel) return;
          const reveal = () => {
            const field = doc.activeElement;
            if (field?.matches('input,select,textarea') && field.closest('[data-droy-voice-settings]'))
              field.scrollIntoView({ block: 'nearest', inline: 'nearest' });
          };
          const observer = new target.ResizeObserver(reveal);
          observer.observe(panel); panel.addEventListener('focusin', reveal);
          target.visualViewport?.addEventListener('resize', reveal);
          return () => { observer.disconnect(); panel.removeEventListener('focusin', reveal); target.visualViewport?.removeEventListener('resize', reveal); };
        }, []);
        React.useEffect(() => {
          if (config) setDraft(previous => ({ ...previous, tts: { ...config.tts, key: '' } }));
        }, [ttsValue]);
        React.useEffect(() => {
          if (config) setDraft(previous => ({ ...previous, wake: { ...config.wake } }));
        }, [wakeValue]);
        if (!config || !draft) return h('div', { className: 'droy-voice-settings' }, h(Feedback, { state }),
          state.error ? h(Button, { variant: 'outline', onClick: () => act(controller.refresh()) }, tr("重新加载"))
            : h('p', { role: 'status' }, tr("正在加载语音设置…")));
        const patch = (section, key, value) => setDraft(previous => ({ ...previous, [section]: { ...previous[section], [key]: value } }));
        const recognition = controller.inputStatus(readiness), prep = state.wake?.preparation;
        const localVoice = ['melo', 'qwen3'].includes(draft.tts.provider);
        const model = models?.find(item => item.provider === draft.tts.provider);
        const ttsReady = !localVoice || model?.ready === true;
        const wakeDirty = ['chinese', 'english', 'timing', 'useCompanionName'].some(key => draft.wake[key] !== config.wake[key]);
        const modelBusy = model && ['downloading', 'verifying'].includes(model.state);
        const size = bytes => `${(bytes / 1048576).toFixed(bytes >= 1073741824 ? 0 : 1)} MB`;
        const modelMessage = modelError ? tr("语音资源暂未就绪。") : !models ? tr("正在读取语音资源…") : !model ? tr("当前版本尚未提供此语音资源。")
          : model.ready ? tr("资源已下载，可离线使用。") : model.state === 'verifying' ? tr("正在校验资源，完成后即可使用。")
          : model.state === 'downloading' ? tr("正在下载 {v0} / {v1}", {v0: size(model.downloadedBytes || 0), v1: size(model.totalBytes)})
          : model.state === 'failed' ? tr("资源下载失败，请重试。") : tr("首次使用需下载 {v0} 语音资源，下载完成后可离线使用。", {v0: size(model.totalBytes)});
        const modelRequest = async operation => {
          if (modelAction) return;
          setModelAction(true); setModelError('');
          try {
            const result = await controller.request(operation, { provider: draft.tts.provider });
            if (mounted.current) setModels(result.models);
          } catch (error) { if (mounted.current) setModelError(error.message); }
          finally { if (mounted.current) setModelAction(false); }
        };
        const previewVoice = async () => {
          if (previewOwner.current) {
            previewOwner.current = null; setPreview('idle');
            await controller.request('stop_speaking'); return;
          }
          if (!ttsReady) return;
          const owner = {}; previewOwner.current = owner; setPreview('preparing');
          try {
            await controller.request('preview_voice', { provider: draft.tts.provider, voiceId: draft.tts.voiceId || '', rate: draft.tts.rate || 1 });
            if (previewOwner.current === owner) setPreview('playing');
          } catch (error) {
            if (previewOwner.current === owner) { previewOwner.current = null; setPreview('idle'); throw error; }
          }
        };
        const save = async (wakeOnly = false) => {
          if (saving || (!wakeOnly && !ttsReady)) return false;
          setSaving(true); setNotice('');
          try {
            const wake = { chinese: draft.wake.chinese, english: draft.wake.english,
              timing: draft.wake.timing || 'screen_on', useCompanionName: !!draft.wake.useCompanionName, target: 'standard' };
            await controller.request('wake.configure', { ...wake, enabled: state.wake?.enabled ?? config.wake.enabled });
            const saved = await controller.saveConfig(wakeOnly ? { wake } : { tts: draft.tts, wake });
            if (!saved) return false;
            setNotice(wakeOnly ? tr("唤醒设置已保存，朗读方式未切换。") : tr("已保存")); return true;
          } finally { setSaving(false); }
        };
        return h('div', { className: 'droy-voice-settings', 'data-droy-voice-settings': true },
          h('h2', { className: 'droy-section-title' }, tr("语音设置")),
          h(Feedback, { state }), notice ? h('p', { className: 'droy-description', role: 'status' }, tr(notice)) : null,
          h('section', null, h('h3', null, tr("语音输入")),
            h(Toggle, { name: 'standard', label: tr("语音对话入口"), checked: config.entries.standard, onChange: value => act(controller.saveConfig({ entries: { standard: value } })) }),
            h('p', { className: 'droy-description' }, tr("按住说话，松开识别并发送；语音对话会在一句话结束后自动发送。")),
            h('p', { className: 'droy-description' }, tr("语音在手机本地识别，自动识别语言，无需配置。")),
            h('p', { className: recognition.state === 'failed' ? 'droy-voice-error' : 'droy-description', role: 'status',
              'data-droy-voice-recognizer-state': recognition.state }, tr(recognition.message)),
            h(Button, { variant: 'text', onClick: () => target.dispatchEvent(new target.CustomEvent('droy-mobile-open-settings', { detail: { section: 'droy-permissions' } })) }, tr("查看麦克风与语音权限"))),
          h('section', null, h('h3', null, tr("回答朗读与声音")),
            h('p', { className: 'droy-description' }, tr("每次进入语音对话默认关闭朗读，可在输入框上方开启。")),
            h(Field, { label: tr("发声方式"), children: h(Choice, { label: tr("发声方式"), value: draft.tts.provider, onChange: value => setDraft(previous => ({
              ...previous, tts: { ...previous.tts, provider: value, voiceId: value === 'qwen3' ? 'Serena' : '',
                rate: value === 'qwen3' ? 1 : previous.tts.rate },
            })), options: [{ value: 'system', label: tr("Android 系统声音") }, { value: 'melo', label: tr("MeloTTS 中文") },
              { value: 'qwen3', label: 'Qwen3-TTS' }, { value: 'openai', label: tr("自定义语音服务") }] }) }),
            h('p', { className: 'droy-description' }, tr("发声方式决定由哪个引擎生成语音；朗读音色决定它使用哪种声音，不是语言设置。")),
            localVoice ? h('div', { className: 'droy-voice-model', 'data-droy-voice-model': draft.tts.provider,
              'data-droy-voice-model-state': model?.state || 'unavailable' },
              h('p', { className: 'droy-description', role: 'status' }, modelMessage),
              modelBusy ? h('progress', { max: model.totalBytes || 1, value: model.downloadedBytes || 0, 'aria-label': tr("语音资源下载进度") }) : null,
              model?.error || modelError ? h('p', { className: 'droy-voice-error', role: 'status' }, tr(modelError || model.error)) : null,
              h('div', { className: 'droy-voice-actions' },
                modelError ? h(Button, { variant: 'text', onClick: () => { void refreshModels(); } }, tr("刷新资源状态")) : null,
                model && !model.ready ? h(Button, { variant: 'outline', disabled: modelAction, 'data-droy-voice-download': true,
                  onClick: () => { void modelRequest(modelBusy ? 'tts.cancel_download' : 'tts.download'); } },
                  modelBusy ? tr("取消下载") : model.state === 'failed' ? tr("重新下载") : tr("下载资源（{v0}）", {v0: size(model.totalBytes)})) : null)) : null,
            draft.tts.provider === 'openai' ? h(React.Fragment, null,
              h(Field, { label: tr("TTS 地址"), value: draft.tts.url, onChange: value => patch('tts', 'url', value) }),
              h(Field, { label: tr("TTS 模型"), value: draft.tts.model, onChange: value => patch('tts', 'model', value) }),
              h(Field, { label: 'TTS API Key', value: draft.tts.key, type: 'password', placeholder: config.tts.hasKey ? tr("已保存，留空不修改") : 'API Key', onChange: value => patch('tts', 'key', value) })) : null,
            h(Field, { label: tr("朗读音色"), children: ['system', 'melo', 'qwen3'].includes(draft.tts.provider) ? h(Choice, { label: tr("朗读音色"), value: draft.tts.voiceId || '', onChange: value => patch('tts', 'voiceId', value),
              options: [{ value: '', label: draft.tts.provider === 'system' ? tr("系统默认") : tr("默认音色") },
                ...state.voices.filter(voice => voice.provider === draft.tts.provider).map(voice => ({ value: voice.voiceId || voice.id, label: voice.name || voice.id }))] })
              : h('input', { value: draft.tts.voiceId || '', 'aria-label': tr("朗读音色"), placeholder: tr("音色 ID"), onChange: event => patch('tts', 'voiceId', event.target.value) }) }),
            h(Field, { label: tr("语速"), children: h(Choice, { label: tr("语速"), value: draft.tts.provider === 'qwen3' ? 1 : draft.tts.rate || 1, disabled: draft.tts.provider === 'qwen3', onChange: value => patch('tts', 'rate', Number(value)),
              options: [{ value: .85, label: tr("慢") }, { value: 1, label: tr("标准") }, { value: 1.15, label: tr("快") }] }) }),
            draft.tts.provider === 'qwen3' ? h('p', { className: 'droy-description' }, tr("Qwen3-TTS 使用标准语速。")) : null,
            ['system', 'melo', 'qwen3'].includes(draft.tts.provider) || preview !== 'idle' ? h('div', { className: 'droy-voice-actions' },
              h(Button, { variant: 'outline', 'data-droy-voice-preview': true, 'aria-busy': preview === 'preparing', disabled: preview === 'idle' && !ttsReady,
                onClick: () => act(previewVoice()) }, preview === 'idle' ? tr("试听") : tr("停止试听")))
              : h('p', { className: 'droy-description' }, tr("自定义语音服务保存后用于回答朗读。"))),
          h('section', null, h('h3', null, tr("关键词唤醒")),
            h(Toggle, { name: 'wake', label: tr("语音唤醒"), checked: state.wake?.enabled ?? config.wake.enabled, disabled: saving || !!state.busy, onChange: value => act((async () => {
              if (!value || await save(true)) await controller.toggleWake(value);
            })()) }),
            h('p', { className: 'droy-description' }, tr("唤醒后新建对话，说“我在”并开始语音输入。")),
            h(Field, { label: tr("唤醒时机"), children: h(Choice, { label: tr("唤醒时机"), value: draft.wake.timing || 'screen_on', 'data-droy-voice-wake-timing': true, onChange: value => patch('wake', 'timing', value),
              options: [{ value: 'screen_on', label: tr("仅亮屏时") }, { value: 'foreground', label: tr("仅在 Droy 内") }] }) }),
            h('p', { className: 'droy-description' }, draft.wake.timing === 'foreground' ? tr("仅在 Droy 位于前台且屏幕解锁时监听。") : tr("屏幕亮起且解锁时监听。")),
            h('h4', null, tr("唤醒关键词")),
            h(Toggle, { name: 'useCompanionName', label: tr("使用 Droy 伙伴名称唤醒"), checked: draft.wake.useCompanionName,
              onChange: value => patch('wake', 'useCompanionName', value) }),
            h('p', { className: 'droy-description' }, tr("使用“你好 + 名称”或“Hi + 名称”唤醒，开启后不再单独设置中英文唤醒词。"),
              state.wake?.companionName ? ' ' + tr("当前伙伴名称：{v0}。", {v0: state.wake.companionName}) : ''),
            h(Field, { label: tr("中文唤醒词"), value: draft.wake.chinese, disabled: !!draft.wake.useCompanionName, onChange: value => patch('wake', 'chinese', value) }),
            h(Field, { label: tr("英文唤醒词"), value: draft.wake.english, disabled: !!draft.wake.useCompanionName, onChange: value => patch('wake', 'english', value) }),
            h('p', { className: 'droy-description', role: 'status' }, tr(state.wake?.message) || tr("唤醒默认关闭")),
            prep?.state === 'preparing' ? h('progress', { max: prep.totalBytes || 1, value: prep.downloadedBytes || 0, 'aria-label': tr("唤醒资源准备进度") }) : null,
            prep?.error ? h('p', { className: 'droy-voice-error' }, tr(prep.error)) : null,
            prep?.error ? h(Button, { variant: 'outline', disabled: prep?.state === 'preparing', 'data-droy-voice-wake-prepare': true,
              onClick: () => act(controller.request('wake.prepare').then(() => setNotice(tr("正在准备唤醒资源")))) }, tr("重试准备")) : null,
            !ttsReady && wakeDirty ? h('div', { className: 'droy-voice-actions' },
              h(Button, { variant: 'outline', 'data-droy-voice-save-wake': true, 'aria-busy': saving, disabled: saving || !!state.busy,
                onClick: () => act(save(true)) }, saving ? tr("保存中…") : tr("保存唤醒设置"))) : null),
          h('div', { className: 'droy-voice-actions' }, h(Button, { 'data-droy-voice-save': true, 'aria-busy': saving, disabled: saving || !!state.busy || !ttsReady, onClick: () => act(save()) }, saving ? tr("保存中…") : tr("保存设置"))),
          renderSlot('droy.voice.audio', {}));
      }
      const style = doc.createElement('style'); style.id = 'droy-voice-style'; style.textContent = styles; doc.head.appendChild(style);
      ctx.slots.inject('conversation.input.activity', () => ctx.slots.register({ name: 'conversation.input.activity', id: 'droy-voice' }, VoiceActivity));
      ctx.slots.inject('conversation.input.dock', () => ctx.slots.register({ name: 'conversation.input.dock', id: 'droy-voice', order: 30 }, VoiceDock));
      ctx.slots.inject('droy.voice.activity', () => ctx.slots.register({ name: 'droy.voice.activity', id: 'droy-voice' }, VoiceActivity));
      ctx.slots.inject('droy.voice.hero', () => ctx.slots.register({ name: 'droy.voice.hero', id: 'droy-voice' }, VoiceDock));
      ctx.slots.inject('settings.section', () => ctx.slots.register({ name: 'settings.section', id: 'droy-voice', order: 16, label: () => tr("语音设置"),
        children: { 'droy.voice.audio': { kind: 'list', scope: 'root' } } }, VoiceSettings));
      ctx.slots.inject('shell.overlay', () => ctx.slots.register({ name: 'shell.overlay', id: 'droy-voice' }, HoldOverlay));
      const previous = target.DroyVoice; target.DroyVoice = controller;
      const onStop = () => act(controller.end()), onWake = event => act(controller.handleWake(event.detail || {}));
      const onBlur = () => { if (controller.getSnapshot().mode === 'hold') act(controller.cancelHold()); };
      const onHidden = () => { if (doc.hidden) onBlur(); };
      target.addEventListener('droy-voice-stop', onStop); target.addEventListener('droy-voice-wake', onWake);
      target.addEventListener('blur', onBlur); doc.addEventListener('visibilitychange', onHidden);
      controller.initialize();
      ctx.effect(() => () => {
        style.remove(); target.removeEventListener('droy-voice-stop', onStop); target.removeEventListener('droy-voice-wake', onWake);
        target.removeEventListener('blur', onBlur); doc.removeEventListener('visibilitychange', onHidden);
        if (target.DroyVoice === controller) target.DroyVoice = previous;
        act(controller.dispose());
      });
    }
    return { apply, inject: ['locale', 'slots', 'sessions', 'uiWorkspace', 'workspaces', 'conversation', 'remote'], createDshVoiceAdapter, createVoiceController };
  },
});
