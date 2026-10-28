function createCompanionInputAdapters({ctx,target,bridge,loadSpeech}) {
  let speech;
  const text = key => {
    const language=/^zh\b/i.test(ctx.locale?.getSnapshot?.().active || '')?'zh':'en';
    return DROY_COMPANION_COPY[language][key] || DROY_COMPANION_COPY.en[key] || key;
  };
  const base64 = bytes => {
    let binary='';
    for(let start=0;start<bytes.length;start+=32768) binary+=String.fromCharCode(...bytes.subarray(start,start+32768));
    return target.btoa(binary);
  };
  async function upload(companionId,file,{userAvatar=false}={}) {
    const bytes=new Uint8Array(await file.arrayBuffer());
    const route=userAvatar?'/droy/companions/user-profile/uploads':'/droy/companions/'+encodeURIComponent(companionId)+'/uploads';
    const response=await target.fetch(route,{
      method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({name:file.name || 'voice.wav',mediaType:file.type || 'application/octet-stream',base64:base64(bytes)}),
    });
    const result=await response.json();
    if(!response.ok) throw new Error(result.message || text('requestFailed'));
    return result;
  }
  async function chooseFile(kind,companionId) {
    const input=target.document.createElement('input');
    input.type='file';input.accept='*/*';
    input.multiple=true;input.style.display='none';
    target.document.body.appendChild(input);
    try {
      const files=await new Promise(resolve=>{
        input.addEventListener('change',()=>resolve([...input.files]),{once:true});
        input.addEventListener('cancel',()=>resolve([]),{once:true});
        input.click();
      });
      const uploads=[];
      for(const file of files)uploads.push(await upload(companionId,file));
      return uploads;
    } finally {input.remove();}
  }
  async function choosePhoto(kind,companionId,remaining=10) {
    const userAvatar=kind==='user-avatar';
    const single=userAvatar||kind==='avatar'||kind==='background';
    const consumer=userAvatar?'profile':'companion';
    const targetSessionId=userAvatar?'user':'im:'+companionId;
    const before=await bridge.request('mediaInput',{operation:'list'});
    const previous=new Set((before.imports??[]).map(row=>row.id));
    return new Promise((resolve,reject)=>{
      let reading=false,finished=false,operationId;
      const finish=(error,value)=>{
        if(finished)return;finished=true;
        target.removeEventListener('droy-media-input-change',changed);
        if(error)reject(error);else resolve(value);
      };
      const changed=async()=>{
        if(reading||finished)return;reading=true;
        let imported,completing=false;
        const uploads=[];
        try{
          const value=await bridge.request('mediaInput',{operation:'list'});
          const row=(value.imports??[]).find(item=>!previous.has(item.id)&&item.consumer===consumer&&item.targetSessionId===targetSessionId
            // Native import failures are published with a separate error-record ID.
            &&(!operationId||item.id===operationId||item.error));
          if(!row){if(operationId&&!value.pending)finish(null,[]);return;}
          imported=row;
          if(row.error)throw new Error(row.error);
          for(const item of row.items??[]){
            const response=await target.fetch('/droy/media-input/'+encodeURIComponent(row.id)+'/'+encodeURIComponent(item.id),{credentials:'same-origin'});
            if(!response.ok)throw new Error(text('selectUnavailable'));
            const blob=await response.blob();
            uploads.push(await upload(companionId,new target.File([blob],item.name,{type:item.mediaType}),{userAvatar}));
          }
          completing=true;
          await bridge.request('mediaInput',{operation:'complete',id:row.id,consumer});
          finish(null,uploads);
        }catch(error){
          if(imported&&!completing) {
            try {await bridge.request('mediaInput',{operation:'complete',id:imported.id,consumer});}
            catch(cleanupError){error=new Error(error.message+' · '+cleanupError.message,{cause:error});}
          }
          if(uploads.length)error.retainedUploads=uploads;
          finish(error);
        }finally{reading=false;}
      };
      target.addEventListener('droy-media-input-change',changed);
      bridge.request('mediaInput',{operation:'pick',source:kind==='camera'?'camera':'album',remaining:single?1:remaining,targetSessionId,consumer})
        .then(result=>{operationId=result.operationId;return changed();}).catch(error=>finish(error));
    });
  }
  async function discard({companionId,voice}) {
    const response=await target.fetch('/droy/companions/'+encodeURIComponent(companionId)+'/uploads/'+encodeURIComponent(voice.attachmentId),
      {method:'DELETE',credentials:'same-origin'});
    if(!response.ok)throw new Error(text('requestFailed'));
  }
  async function recognize({companionId,voice,asText},signal) {
    speech ||= await loadSpeech();
    try {
      const response=await target.fetch(voice.url,{credentials:'same-origin',signal});
      if(!response.ok)throw new Error(text('requestFailed'));
      const bytes=new Uint8Array(await response.arrayBuffer());
      const result=await speech.transcribe({audioBase64:base64(bytes),providerId:'droy-sensevoice-local',language:'auto'},signal);
      if(!result.ok)throw new Error(result.error?.message || text('requestFailed'));
      const transcript=String(result.value.text || '').replace(/<\|[^|]*\|>/gu,'').trim();
      if(!transcript)throw new Error(text('recordingUnavailable'));
      if(asText)return {text:transcript};
      return {text:transcript,voice:{...voice,transcript,transcriptionState:'ready'}};
    } catch(error) {error.retainedVoice=voice;error.asText=!!asText;throw error;}
  }
  function connectedSpeech(signal) {
    // The DSH observer is returned before its first catalog frame arrives.
    signal?.throwIfAborted();
    return new Promise((resolve,reject)=>{
      let stop,settled=false;
      const finish=(error,value)=>{
        if(settled)return;settled=true;stop?.();signal?.removeEventListener('abort',cancel);
        if(error)reject(error);else resolve(value);
      };
      const cancel=()=>finish(signal.reason);
      const check=()=>{
        const value=speech.readiness.getSnapshot();
        if(value.error)return finish(new Error(text('recordingUnavailable')));
        const provider=value.catalog?.providers?.find(item=>item.id==='droy-sensevoice-local');
        if(value.connected&&value.catalog&&provider?.preparation?.phase!=='checking')finish(null,value);
      };
      signal?.addEventListener('abort',cancel,{once:true});
      stop=speech.readiness.subscribe(check);if(settled)stop();else check();
    });
  }
  return {
    media:{pick:(kind,{companionId,remaining=10}={})=>['camera','album','avatar','user-avatar','background'].includes(kind)
      ?choosePhoto(kind,companionId,remaining):chooseFile(kind,companionId)},
    recording:{
      retry:args=>recognize(args),
      discard,
      async retryMessage({companionId,messageId,voice}) {
        const result=await recognize({companionId,voice});
        const response=await target.fetch('/droy/companions/'+encodeURIComponent(companionId)+'/messages/'+encodeURIComponent(messageId)+'/transcript',{
          method:'PATCH',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify({text:result.text}),
        });
        const saved=await response.json();
        if(!response.ok)throw new Error(saved.message || text('requestFailed'));
        return saved;
      },
      async start({companionId,signal}){
        speech ||= await loadSpeech();
        const readiness=await connectedSpeech(signal);
        const provider=readiness.catalog?.providers?.find(item=>item.id==='droy-sensevoice-local');
        if(!readiness.connected || !provider || !['ready','standby','waking'].includes(provider.preparation?.phase))
          throw new Error(text('recordingUnavailable'));
        await target.DroyVoice?.end?.();
        signal?.throwIfAborted();
        const capture=speech.createRecording(),abort=new target.AbortController();
        let ended=false,recordingError;
        await capture.start(error=>{recordingError=error;abort.abort();});
        const started=Date.now();
        return {
          async cancel(){if(ended)return;ended=true;abort.abort();await capture.dispose();},
          async finish({asText}={}){
            if(ended)throw new Error(text('recordingInterrupted'));
            ended=true;
            try{
              if(recordingError)throw recordingError;
              const audio=await capture.stop(readiness.catalog.maxDurationSeconds);
              const durationMs=Date.now()-started;
              const bytes=new Uint8Array(audio);
              if(bytes.byteLength>readiness.catalog.maxAudioBytes)throw new Error(text('recordingUnavailable'));
              const saved=await upload(companionId,new target.File([audio],'voice.wav',{type:'audio/wav'}));
              return await recognize({companionId,asText,voice:{attachmentId:saved.id,url:saved.url,durationMs}},abort.signal);
            }finally{await capture.dispose();}
          },
        };
      },
    },
  };
}
